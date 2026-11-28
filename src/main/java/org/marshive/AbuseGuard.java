package org.marshive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.text.Normalizer;
import java.util.Locale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

final class AbuseGuard {
    private static final int MAX_CONNECTIONS_PER_IP = 24;
    private static final int MAX_CREATES_PER_10S = 3;
    private static final int MAX_CREATES_PER_MINUTE = 8;
    private static final int MAX_ACTIVE_ROOMS_PER_IP = 4;
    private static final int MAX_WAITING_ROOMS_PER_NAME = 8;
    private static final long CREATE_SHORT_WINDOW_MS = 10000L;
    private static final long CREATE_LONG_WINDOW_MS = 60000L;
    private static final long CREATE_BLOCK_MS = 60000L;
    static final long IP_IDLE_TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, IpState> IP_STATES = new HashMap<>();
    private static int connections;
    private static final Map<Room, RoomGroup> ROOM_GROUP_BY_ROOM = new HashMap<>();
    private static final Map<String, RoomGroup> ROOM_GROUPS = new HashMap<>();
    private static final Map<String, Integer> ACTIVE_ROOMS_BY_IP = new HashMap<>();
    static final int MAX_SPAM_ROOMS_PER_SWEEP = 20;
    private static long logWindowStarted;
    private static int rejectionLogs;
    private static long suppressedLogs;
    private static final long NAME_WINDOW_MS = 30000L;
    private static final long NAME_BLOCK_MS = 120000L;
    private static final Map<String, NameState> NAME_STATES = new HashMap<>();
    private static long lastNameCleanupAt;

    private AbuseGuard() {}

    // Admission, close and eviction share a lock so eviction cannot lose live counts.
    static synchronized boolean tryOpenConnection(String ip) {
        if (connections >= ResourceLimits.MAX_CONNECTIONS) {
            logRejection(ip, -1, "CONNECTIONS_GLOBAL", System.currentTimeMillis());
            return false;
        }
        IpState state = stateFor(ip, System.currentTimeMillis());
        if (state == null || state.connections >= MAX_CONNECTIONS_PER_IP) {
            logRejection(ip, -1, state == null ? "IP_TABLE_FULL" : "CONNECTIONS_IP", System.currentTimeMillis());
            return false;
        }
        state.connections++;
        connections++;
        return true;
    }

    static synchronized void closeConnection(String ip) {
        IpState state = IP_STATES.get(normalizeIp(ip));
        if (state == null || state.connections == 0) return;
        state.connections--;
        connections--;
        state.lastSeenMillis = System.currentTimeMillis();
    }

    static synchronized boolean allowCreate(String ip, long nowMillis) {
        return createRateRejection(ip, nowMillis) == null;
    }

    // Caller holds the same monitor until registerRoom, so cross-shard admission is atomic.
    static synchronized String createRejection(String ip, String name, long nowMillis) {
        String source = normalizeIp(ip);
        if (ACTIVE_ROOMS_BY_IP.getOrDefault(source, 0) >= MAX_ACTIVE_ROOMS_PER_IP) return "ACTIVE_ROOMS_IP";
        IpState ipState = IP_STATES.get(source);
        if (ipState != null && ipState.createBlockedUntilMillis > nowMillis) return "CREATE_COOLDOWN";
        String targetName = roomNameKey(name);
        NameState nameState = NAME_STATES.get(targetName);
        if (nameState != null) {
            if (nowMillis < nameState.blockedUntil) return "NAME_COOLDOWN";
            pruneNameCreates(nameState, nowMillis);
            if (nameState.creates.size() >= 4 && hasThreeSources(nameState, source)) {
                nameState.blockedUntil = nowMillis + NAME_BLOCK_MS;
                return "NAME_BURST";
            }
        } else if (NAME_STATES.size() >= 8192) {
            // A full history table must not cause an 8192-entry scan on every rejected request.
            if (nowMillis < lastNameCleanupAt || nowMillis - lastNameCleanupAt >= 1000L) cleanupNames(nowMillis);
            if (NAME_STATES.size() >= 8192) return "NAME_TABLE_FULL";
        }
        RoomGroup group = ROOM_GROUPS.get(targetName);
        if (group != null && group.waiting.size() >= MAX_WAITING_ROOMS_PER_NAME) return "WAITING_ROOMS_NAME";
        return createRateRejection(ip, nowMillis);
    }

    static synchronized void registerRoom(Room room) {
        if (room.getHost() == null || ROOM_GROUP_BY_ROOM.containsKey(room)) return;
        String name = roomNameKey(room.getName());
        RoomGroup group = ROOM_GROUPS.computeIfAbsent(name, RoomGroup::new);
        ROOM_GROUP_BY_ROOM.put(room, group);
        group.rooms.add(room);
        if (!room.isGaming()) group.waiting.add(room);
        ACTIVE_ROOMS_BY_IP.merge(room.getHost().getRemoteIp(), 1, Integer::sum);
        NameState state = NAME_STATES.computeIfAbsent(name, key -> new NameState());
        long now = room.getCreatedAtMillis();
        pruneNameCreates(state, now);
        state.creates.addLast(new NameCreate(now, room.getHost().getRemoteIp()));
    }

    static synchronized void releaseRoom(Room room) {
        RoomGroup group = ROOM_GROUP_BY_ROOM.remove(room);
        if (group == null) return;
        group.rooms.remove(room);
        group.waiting.remove(room);
        if (group.rooms.isEmpty()) ROOM_GROUPS.remove(group.name);
        ACTIVE_ROOMS_BY_IP.computeIfPresent(room.getHost().getRemoteIp(), (ip, count) -> count == 1 ? null : count - 1);
    }

    // Room.setGaming changes the flag and this index under the admission monitor.
    static synchronized void roomGamingChanged(Room room) {
        RoomGroup group = ROOM_GROUP_BY_ROOM.get(room);
        if (group == null) return;
        if (room.isGaming()) group.waiting.remove(room);
        else group.waiting.add(room);
    }

    static boolean emptyWaitingRoom(Room room) {
        return !room.isGaming() && room.getGuest() == null && room.spectatorCount() == 0
                && room.reconnect.state == RelayReconnect.NONE;
    }

    static List<Room> spamRooms(long nowMillis) {
        List<Room> candidates = new ArrayList<>();
        synchronized (AbuseGuard.class) {
            for (RoomGroup group : ROOM_GROUPS.values()) {
                NameState state = NAME_STATES.get(group.name);
                if (state == null || state.blockedUntil <= nowMillis) continue;
                Room oldest = null;
                for (Room candidate : group.rooms) {
                    if (oldest == null || candidate.getCreatedAtMillis() < oldest.getCreatedAtMillis()
                            || candidate.getCreatedAtMillis() == oldest.getCreatedAtMillis() && candidate.getId() < oldest.getId()) {
                        oldest = candidate;
                    }
                }
                for (Room room : group.waiting) {
                    if (room != oldest) candidates.add(room);
                }
            }
        }
        // Starting a game can hold the Room monitor before updating the admission index.
        // Never take a Room monitor (spectatorCount) while holding the admission monitor.
        List<Room> result = new ArrayList<>();
        for (Room room : candidates) {
            if (emptyWaitingRoom(room)) {
                result.add(room);
                if (result.size() == MAX_SPAM_ROOMS_PER_SWEEP) break;
            }
        }
        return result;
    }

    private static void cleanupNames(long nowMillis) {
        lastNameCleanupAt = nowMillis;
        NAME_STATES.values().removeIf(state -> state.blockedUntil <= nowMillis
                && (state.creates.isEmpty() || state.creates.peekLast().time < nowMillis - NAME_WINDOW_MS));
    }

    private static void pruneNameCreates(NameState state, long nowMillis) {
        while (!state.creates.isEmpty() && state.creates.peekFirst().time < nowMillis - NAME_WINDOW_MS) {
            state.creates.removeFirst();
        }
    }

    private static boolean hasThreeSources(NameState state, String source) {
        String second = null;
        for (NameCreate create : state.creates) {
            if (source.equals(create.ip)) continue;
            if (second == null) second = create.ip;
            else if (!second.equals(create.ip)) return true;
        }
        return false;
    }

    private static String roomNameKey(String name) {
        if (name.startsWith("[VS]")) name = name.substring(4);
        else if (name.startsWith("[COOP]")) name = name.substring(6);
        return Normalizer.normalize(NameMatchText.withoutIgnorables(name), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static String createRateRejection(String ip, long nowMillis) {
        IpState state = stateFor(ip, nowMillis);
        if (state == null) return "IP_TABLE_FULL";
        if (state.createBlockedUntilMillis > nowMillis) return "CREATE_COOLDOWN";
        pruneOlderThan(state.creates, nowMillis - CREATE_LONG_WINDOW_MS);
        int shortCount = 0;
        for (Long t : state.creates) if (t >= nowMillis - CREATE_SHORT_WINDOW_MS) shortCount++;
        if (shortCount >= MAX_CREATES_PER_10S || state.creates.size() >= MAX_CREATES_PER_MINUTE) {
            state.createBlockedUntilMillis = nowMillis + CREATE_BLOCK_MS;
            return shortCount >= MAX_CREATES_PER_10S ? "CREATES_10S" : "CREATES_60S";
        }
        state.creates.addLast(nowMillis);
        return null;
    }

    static synchronized void logRejection(String ip, int shard, String reason, long nowMillis) {
        logRejection(ip, shard, reason, nowMillis, null);
    }

    static synchronized void logRejection(String ip, int shard, String reason, long nowMillis, String name) {
        IpState state = IP_STATES.get(normalizeIp(ip));
        if (nowMillis - logWindowStarted >= 10000L) {
            logWindowStarted = nowMillis;
            rejectionLogs = 0;
        }
        if (rejectionLogs >= 20 || state != null && nowMillis < state.nextRejectionLogMillis) {
            suppressedLogs++;
            return;
        }
        rejectionLogs++;
        if (state != null) state.nextRejectionLogMillis = nowMillis + 10000L;
        long retryMillis = state == null ? 0 : Math.max(0, state.createBlockedUntilMillis - nowMillis);
        NameState nameState = name == null ? null : NAME_STATES.get(roomNameKey(name));
        long nameRetryMillis = nameState == null ? 0 : Math.max(0, nameState.blockedUntil - nowMillis);
        System.out.println("[ABUSE_REJECT] time=" + Instant.ofEpochMilli(nowMillis)
                + " ip=" + normalizeIp(ip) + " shard=" + shard + " reason=" + reason
                + " createCooldownMs=" + retryMillis + " suppressedSinceLastLog=" + suppressedLogs
                + (name == null ? "" : " name=[" + name + "] nameCooldownMs=" + nameRetryMillis));
        suppressedLogs = 0;
    }

    static synchronized int cleanup(long nowMillis) {
        cleanupNames(nowMillis);
        int before = IP_STATES.size();
        IP_STATES.values().removeIf(state -> {
            pruneOlderThan(state.creates, nowMillis - CREATE_LONG_WINDOW_MS);
            return state.connections == 0 && state.creates.isEmpty()
                    && state.createBlockedUntilMillis <= nowMillis
                    && nowMillis - state.lastSeenMillis >= IP_IDLE_TTL_MS;
        });
        return before - IP_STATES.size();
    }

    static synchronized int connectionCount() { return connections; }
    static synchronized int trackedIpCount() { return IP_STATES.size(); }

    private static IpState stateFor(String ip, long nowMillis) {
        String key = normalizeIp(ip);
        IpState state = IP_STATES.get(key);
        if (state == null) {
            if (IP_STATES.size() >= ResourceLimits.MAX_TRACKED_IPS) return null;
            state = new IpState();
            IP_STATES.put(key, state);
        }
        state.lastSeenMillis = nowMillis;
        return state;
    }

    private static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private static void pruneOlderThan(Deque<Long> deque, long cutoffMillis) {
        while (!deque.isEmpty() && deque.peekFirst() < cutoffMillis) deque.removeFirst();
    }

    private static final class IpState {
        int connections;
        final Deque<Long> creates = new ArrayDeque<>();
        long createBlockedUntilMillis;
        long lastSeenMillis;
        long nextRejectionLogMillis;
    }

    private record NameCreate(long time, String ip) {}

    private static final class RoomGroup {
        final String name;
        final Set<Room> rooms = new HashSet<>();
        final Set<Room> waiting = new HashSet<>();

        RoomGroup(String name) { this.name = name; }
    }

    private static final class NameState {
        final Deque<NameCreate> creates = new ArrayDeque<>();
        long blockedUntil;
    }
}
