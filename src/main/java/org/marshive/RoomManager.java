package org.marshive;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class RoomManager {
    static final int MAX_ROOMS_PER_SHARD = 512;
    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger idGen;
    private final int shardId;
    private final PlayerNamePolicy names;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    public RoomManager(int shardId) {
        this(shardId, SensitiveWordFilter.bundled());
    }

    RoomManager(int shardId, SensitiveWordFilter roomNameFilter) {
        this.shardId = shardId;
        this.names = new PlayerNamePolicy(roomNameFilter);

        // ✅ 让不同端口 roomId 不撞：高位塞 shardId
        // 例如 shardId=1 的 roomId 都 >= 0x01000000
        int base = (shardId & 0x7F) << 24; // 0..127 shards 安全
        this.idGen = new AtomicInteger(base + 1000);
    }

    String playerName(String raw) {
        return names.playerName(raw);
    }

    record CreateResult(Room room, String rejection, String name) {}

    CreateResult tryCreateRoom(String name, ClientHandler host, int version) {
        synchronized (AbuseGuard.class) {
            String cleaned = names.roomName(name);
            String rejection = rooms.size() >= MAX_ROOMS_PER_SHARD ? "ROOMS_SHARD"
                    : AbuseGuard.createRejection(host.getRemoteIp(), cleaned, System.currentTimeMillis());
            if (rejection != null) return new CreateResult(null, rejection, cleaned);
            return new CreateResult(createCleanedRoom(cleaned, host, version), null, cleaned);
        }
    }

    public Room createRoom(String name, ClientHandler host, int protocolVersion) {
        return createCleanedRoom(names.roomName(name), host, protocolVersion);
    }

    private Room createCleanedRoom(String name, ClientHandler host, int protocolVersion) {
        int id = idGen.incrementAndGet();
        Room r = new Room(id, name, host, protocolVersion);
        rooms.put(id, r);
        AbuseGuard.registerRoom(r);
        String createdAt = TS_FMT.format(Instant.ofEpochMilli(r.getCreatedAtMillis()));
        System.out.println("[shard=" + shardId + "] Room Created: " + id + " [" + name + "] createdAt=" + createdAt
                + " ip=" + (host == null ? "unknown" : host.getRemoteIp()) + " version=" + protocolVersion);
        return r;
    }

    public Room getRoom(int id) {
        return rooms.get(id);
    }

    public synchronized boolean joinRoom(int id, ClientHandler guest) {
        Room r = rooms.get(id);
        if (r != null && !r.isFull() && !r.isGaming()) {
            r.setGuest(guest);
            return true;
        }
        return false;
    }

    public synchronized boolean leaveAsGuest(int id, ClientHandler guest) {
        Room r = rooms.get(id);
        if (r == null) return false;
        if (r.getGuest() != guest) return false;
        if (r.isGaming()) return false;
        r.setGuest(null);
        return true;
    }

    public void removeRoom(int id) {
        Room r = rooms.remove(id);
        if (r != null) {
            AbuseGuard.releaseRoom(r);
            System.out.println("[shard=" + shardId + "] Room Removed: " + id
                    + " ip=" + (r.getHost() == null ? "unknown" : r.getHost().getRemoteIp())
                    + " removedAt=" + TS_FMT.format(Instant.now()));
        }
    }

    public Iterable<Room> allRooms() {
        return rooms.values();
    }

    public int roomCount() {
        return rooms.size();
    }

    void removeSpamRoom(Room room) {
        if (rooms.get(room.getId()) != room || !AbuseGuard.emptyWaitingRoom(room)) return;
        room.getHost().onRoomRemovedByServer();
        removeRoom(room.getId());
        AnalyticsCollector.onRoomClosed(room.getId());
        System.out.println("[ROOM_SPAM_CLEAN] room=" + room.getId() + " ip=" + room.getHost().getRemoteIp()
                + " name=[" + room.getName() + "] time=" + Instant.now());
    }

    public int hostedRoomCountByIp(String ip) {
        String target = ip == null || ip.isBlank() ? "unknown" : ip;
        int count = 0;
        for (Room room : rooms.values()) {
            if (room.getHost() != null && target.equals(room.getHost().getRemoteIp())) count++;
        }
        return count;
    }

    public int removeStaleNotGamingRooms(long nowMillis, long ttlMillis) {
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, Room> e : rooms.entrySet()) {
            Room r = e.getValue();
            if (r == null) continue;
            if (r.isGaming()) continue;
            if (nowMillis - r.getCreatedAtMillis() >= ttlMillis) {
                toRemove.add(e.getKey());
            }
        }
        for (Integer roomId : toRemove) {
            Room r = rooms.get(roomId);
            if (r != null) {
                ClientHandler host = r.getHost();
                ClientHandler guest = r.getGuest();
                if (host != null) host.onRoomRemovedByServer();
                if (guest != null && guest != host) guest.onRoomRemovedByServer();
                for (ClientHandler s : r.snapshotSpectators()) {
                    if (s != null) s.onRoomRemovedByServer();
                }
            }
            removeRoom(roomId);
        }
        return toRemove.size();
    }
}
