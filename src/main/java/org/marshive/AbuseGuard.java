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
    private static final Map<Room, String> ROOM_NAMES = new HashMap<>();
    private static long logWindowStarted;
    private static int rejectionLogs;
    private static long suppressedLogs;
    private static final long NAME_WINDOW_MS = 30000L;
    private static final long NAME_BLOCK_MS = 120000L;
    private static final Map<String, NameState> NAME_STATES = new HashMap<>();

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
        int active = 0;
        int sameName = 0;
        String targetName = roomNameKey(name);
        for (Map.Entry<Room, String> entry : ROOM_NAMES.entrySet()) {
            Room room = entry.getKey();
            if (normalizeIp(ip).equals(room.getHost().getRemoteIp())) active++;
            if (!room.isGaming() && targetName.equals(entry.getValue())) sameName++;
        }
        if (active >= MAX_ACTIVE_ROOMS_PER_IP) return "ACTIVE_ROOMS_IP";
        NameState nameState = NAME_STATES.get(targetName);
        if (nameState != null) {
            if (nowMillis < nameState.blockedUntil) return "NAME_COOLDOWN";
            nameState.creates.removeIf(c -> c.time < nowMillis - NAME_WINDOW_MS);
            Set<String> sources = new HashSet<>();
            sources.add(normalizeIp(ip));
            for (NameCreate create : nameState.creates) sources.add(create.ip);
            if (nameState.creates.size() >= 4 && sources.size() >= 3) {
                nameState.blockedUntil = nowMillis + NAME_BLOCK_MS;
                return "NAME_BURST";
            }
        } else if (NAME_STATES.size() >= 8192) {
            cleanupNames(nowMillis);
            if (NAME_STATES.size() >= 8192) return "NAME_TABLE_FULL";
        }
        if (sameName >= MAX_WAITING_ROOMS_PER_NAME) return "WAITING_ROOMS_NAME";
        return createRateRejection(ip, nowMillis);
    }

    static synchronized void registerRoom(Room room) {
        if (room.getHost() == null) return;
        String name = roomNameKey(room.getName());
        ROOM_NAMES.put(room, name);
        NameState state = NAME_STATES.computeIfAbsent(name, key -> new NameState());
        long now = room.getCreatedAtMillis();
        state.creates.removeIf(c -> c.time < now - NAME_WINDOW_MS);
        state.creates.addLast(new NameCreate(now, room.getHost().getRemoteIp()));
    }

    static synchronized void releaseRoom(Room room) {
        ROOM_NAMES.remove(room);
    }

    static boolean emptyWaitingRoom(Room room) {
        return !room.isGaming() && room.getGuest() == null && room.spectatorCount() == 0
                && room.reconnect.state == RelayReconnect.NONE;
    }

    static synchronized List<Room> spamRooms(long nowMillis) {
        Map<String, Room> oldest = new HashMap<>();
        for (Map.Entry<Room, String> entry : ROOM_NAMES.entrySet()) {
            NameState state = NAME_STATES.get(entry.getValue());
            if (state == null || state.blockedUntil <= nowMillis) continue;
            Room candidate = entry.getKey();
            Room previous = oldest.get(entry.getValue());
            if (previous == null || candidate.getCreatedAtMillis() < previous.getCreatedAtMillis()
                    || candidate.getCreatedAtMillis() == previous.getCreatedAtMillis() && candidate.getId() < previous.getId()) {
                oldest.put(entry.getValue(), candidate);
            }
        }
        List<Room> result = new ArrayList<>();
        for (Map.Entry<Room, String> entry : ROOM_NAMES.entrySet()) {
            Room retained = oldest.get(entry.getValue());
            if (retained != null && retained != entry.getKey() && emptyWaitingRoom(entry.getKey())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static void cleanupNames(long nowMillis) {
        NAME_STATES.values().removeIf(state -> state.blockedUntil <= nowMillis
                && (state.creates.isEmpty() || state.creates.peekLast().time < nowMillis - NAME_WINDOW_MS));
    }

    private static String roomNameKey(String name) {
        if (name.startsWith("[VS]")) name = name.substring(4);
        else if (name.startsWith("[COOP]")) name = name.substring(6);
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("\u200D", "").replace("\uFE0E", "").replace("\uFE0F", "").strip();
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

    private static final class NameState {
        final Deque<NameCreate> creates = new ArrayDeque<>();
        long blockedUntil;
    }
}
