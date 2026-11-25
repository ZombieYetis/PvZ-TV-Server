package org.marshive;

import org.junit.After;
import org.junit.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class RoomAdmissionScaleTest {
    private final List<ClientHandler> clients = new ArrayList<>();
    private final List<Room> rooms = new ArrayList<>();

    private ClientHandler host(String ip, RoomManager manager, int shard) throws Exception {
        ClientHandler client = new ClientHandler(SocketChannel.open(), manager, shard, 0, 0, ip);
        clients.add(client);
        return client;
    }

    @After public void cleanup() throws Exception {
        rooms.forEach(AbuseGuard::releaseRoom);
        for (ClientHandler client : clients) client.ch.close();
        AbuseGuard.cleanup(Long.MAX_VALUE);
    }

    @Test public void thousandRoomsDoNotGetScannedByCreateChecksAndIndexesFollowLifecycle() throws Exception {
        for (int i = 0; i < 1000; i++) {
            Room room = new CountedRoom(i, i < 8 ? "shared" : "room" + i,
                    host("scale-" + i, null, 0));
            rooms.add(room);
            AbuseGuard.registerRoom(room);
            AbuseGuard.registerRoom(room); // Repeated registration must not consume another slot.
        }
        // Let the burst history expire, without dropping the live-room indexes.
        long now = System.currentTimeMillis() + 31000L;
        AbuseGuard.cleanup(now);
        CountedRoom.gamingReads = 0;
        for (int i = 0; i < 10000; i++) {
            assertEquals("WAITING_ROOMS_NAME", AbuseGuard.createRejection("visitor", "[COOP]ＳＨＡＲＥＤ", now));
        }
        assertEquals("Admission must use the index instead of reading 1000 rooms", 0, CountedRoom.gamingReads);
        Room first = rooms.get(0);
        first.setGaming(true);
        first.setGaming(true);
        assertNull(AbuseGuard.createRejection("visitor", "shared", now));
        first.setGaming(false);
        first.setGaming(false);
        assertEquals("WAITING_ROOMS_NAME", AbuseGuard.createRejection("visitor", "shared", now));
        AbuseGuard.releaseRoom(first);
        AbuseGuard.releaseRoom(first);
        first.setGaming(true); // Removed rooms cannot recreate stale index entries.
        assertNull(AbuseGuard.createRejection("visitor", "shared", now));
        rooms.forEach(AbuseGuard::releaseRoom);
        AbuseGuard.cleanup(Long.MAX_VALUE);
        assertNull(AbuseGuard.createRejection("scale-0", "shared", now));
    }

    @Test public void thousandRoomCleanupIsBatchedAndProtectsActiveAndNewlyOccupiedRooms() throws Exception {
        RoomManager[] managers = {new RoomManager(0), new RoomManager(1), new RoomManager(2), new RoomManager(3)};
        Set<Room> protectedRooms = new HashSet<>();
        PrintStream previous = System.out;
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(quiet);
            for (int i = 0; i < 1000; i++) {
                int shard = i % 4;
                Room room = managers[shard].createRoom("ad" + i / 4,
                        host("batch-" + i, managers[shard], shard), 3226);
                rooms.add(room);
                if (i % 4 == 0) protectedRooms.add(room); // Oldest of each name.
            }
            // Distinct protection cases in different groups.
            rooms.get(1).setGaming(true);
            rooms.get(5).setGuest(clients.get(0));
            rooms.get(9).addSpectator(clients.get(0));
            rooms.get(13).reconnect.state = RelayReconnect.WAITING;
            protectedRooms.addAll(List.of(rooms.get(1), rooms.get(5), rooms.get(9), rooms.get(13)));
            long now = System.currentTimeMillis();
            for (int group = 0; group < 250; group++) {
                assertEquals("NAME_BURST", AbuseGuard.createRejection("rotating-ip", "ad" + group, now));
            }
            List<Room> batch = AbuseGuard.spamRooms(now);
            assertEquals(20, batch.size());
            Room lateJoin = batch.get(0);
            lateJoin.setGuest(clients.get(0));
            protectedRooms.add(lateJoin);
            managers[lateJoin.getId() >>> 24].removeSpamRoom(lateJoin);
            assertSame(lateJoin, managers[lateJoin.getId() >>> 24].getRoom(lateJoin.getId()));

            int rounds = 0;
            while (!AbuseGuard.spamRooms(now).isEmpty()) {
                int before = countRooms(managers);
                int processed = ServerApp.cleanSpamRooms(managers, now);
                int removed = before - countRooms(managers);
                assertTrue(processed > 0 && processed <= 20);
                assertTrue(removed > 0 && removed <= 20);
                assertTrue("Cleanup must make progress across all names", ++rounds <= 1000);
            }
            assertEquals(protectedRooms.size(), countRooms(managers));
            for (Room room : protectedRooms) {
                assertSame(room, managers[room.getId() >>> 24].getRoom(room.getId()));
            }
            assertTrue("The backlog must span multiple ticks", rounds >= 38);
            assertNull(AbuseGuard.createRejection("ordinary-player", "normal", now));
        } finally {
            System.setOut(previous);
        }
    }

    @Test public void cleanupDoesNotTakeRoomMonitorsWhileHoldingTheAdmissionMonitor() throws Exception {
        for (int i = 0; i < 4; i++) {
            Room room = new CountedRoom(i, "lock-check", host("lock-" + i, null, 0));
            rooms.add(room);
            AbuseGuard.registerRoom(room);
            // The real start-game path already holds the room monitor.
            synchronized (room) {
                room.setGaming(true);
                room.setGaming(false);
            }
        }
        long now = System.currentTimeMillis();
        assertEquals("NAME_BURST", AbuseGuard.createRejection("lock-extra", "lock-check", now));
        assertEquals(3, AbuseGuard.spamRooms(now).size());
    }

    private static int countRooms(RoomManager[] managers) {
        int total = 0;
        for (RoomManager manager : managers) total += manager.roomCount();
        return total;
    }

    private static final class CountedRoom extends Room {
        static int gamingReads;

        CountedRoom(int id, String name, ClientHandler host) { super(id, name, host, 3226); }

        @Override public boolean isGaming() {
            gamingReads++;
            return super.isGaming();
        }

        @Override public int spectatorCount() {
            assertFalse("Room/admission lock inversion can deadlock game starts", Thread.holdsLock(AbuseGuard.class));
            return super.spectatorCount();
        }
    }
}
