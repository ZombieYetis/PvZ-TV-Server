package org.marshive;

import org.junit.After;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RoomAdmissionTest {
    private final RoomManager first = new RoomManager(0);
    private final RoomManager second = new RoomManager(1);
    private final List<ClientHandler> clients = new ArrayList<>();

    private ClientHandler host(RoomManager manager, String ip) throws Exception {
        ClientHandler host = new ClientHandler(SocketChannel.open(), manager, manager == first ? 0 : 1, 0, 0, ip);
        clients.add(host);
        return host;
    }

    @After public void cleanup() {
        for (RoomManager manager : List.of(first, second)) {
            for (Room room : manager.allRooms()) manager.removeRoom(room.getId());
        }
        clients.forEach(ClientHandler::close);
        AbuseGuard.cleanup(Long.MAX_VALUE);
    }

    @Test public void activeRoomLimitSpansShardsAndReleasesExactlyOnce() throws Exception {
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoomManager manager = i % 2 == 0 ? first : second;
            rooms.add(manager.createRoom("room" + i, host(manager, "shared-ip"), 3215));
        }
        assertEquals("ACTIVE_ROOMS_IP", second.tryCreateRoom("renamed", host(second, "shared-ip"), 3215).rejection());
        first.removeRoom(rooms.get(0).getId());
        first.removeRoom(rooms.get(0).getId());
        assertNotNull(second.tryCreateRoom("replacement", host(second, "shared-ip"), 3215).room());
        assertEquals("ACTIVE_ROOMS_IP", first.tryCreateRoom("another", host(first, "shared-ip"), 3215).rejection());
        assertNotNull(first.tryCreateRoom("other", host(first, "other-ip"), 3215).room());
    }

    @Test public void repeatedNamesAcrossIpsAndModesAreLimitedOnlyWhileWaiting() throws Exception {
        Room firstRoom = null;
        for (int i = 0; i < 8; i++) {
            Room room = first.createRoom("[VS]repeat", host(first, "name-ip-" + i), 3215);
            assertNotNull(room);
            if (i == 0) firstRoom = room;
        }
        AbuseGuard.cleanup(System.currentTimeMillis() + 31000);
        assertEquals("WAITING_ROOMS_NAME",
                second.tryCreateRoom("[COOP]ＲＥＰＥＡＴ", host(second, "ninth-ip"), 3215).rejection());
        assertEquals("WAITING_ROOMS_NAME",
                second.tryCreateRoom("[VS]re\u200Dpeat", host(second, "invisible-ip"), 3215).rejection());
        assertEquals("WAITING_ROOMS_NAME",
                second.tryCreateRoom("[VS]\u00A0repeat\u00A0", host(second, "space-ip"), 3215).rejection());
        assertNotNull(second.tryCreateRoom("different", host(second, "tenth-ip"), 3215).room());
        firstRoom.setGaming(true);
        assertNotNull(second.tryCreateRoom("[COOP]repeat", host(second, "eleventh-ip"), 3215).room());
        assertSame(firstRoom, first.getRoom(firstRoom.getId()));
        assertEquals(7, first.removeStaleNotGamingRooms(Long.MAX_VALUE, 1));
        assertSame(firstRoom, first.getRoom(firstRoom.getId()));
        assertNotNull(first.tryCreateRoom("repeat", host(first, "after-cleanup"), 3215).room());
    }

    @Test public void rotatingIpsTriggerNameCooldownAndClearOnlyEmptyDuplicates() throws Exception {
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoomManager manager = i % 2 == 0 ? first : second;
            rooms.add(manager.tryCreateRoom("[VS]advert", host(manager, "proxy-" + i), 3215).room());
            assertNotNull(rooms.get(i));
        }
        rooms.get(1).setGuest(host(second, "legitimate-guest"));
        rooms.get(2).addSpectator(host(first, "viewer"));
        assertEquals("NAME_BURST", second.tryCreateRoom("[COOP]ＡＤＶＥＲＴ", host(second, "proxy-5"), 3215).rejection());
        long now = System.currentTimeMillis();
        List<Room> disposable = AbuseGuard.spamRooms(now);
        assertEquals(List.of(rooms.get(3)), disposable);
        // Joining between selection and cleanup protects the room.
        rooms.get(3).setGuest(host(second, "late-guest"));
        second.removeSpamRoom(rooms.get(3));
        assertNotNull(second.getRoom(rooms.get(3).getId()));
        rooms.get(3).setGuest(null);
        second.removeSpamRoom(rooms.get(3));
        assertNull(second.getRoom(rooms.get(3).getId()));
        assertNotNull(first.getRoom(rooms.get(0).getId()));
        assertNotNull(second.getRoom(rooms.get(1).getId()));
        assertNotNull(first.getRoom(rooms.get(2).getId()));
        // Emptying every room must not clear the name's cooldown.
        first.removeRoom(rooms.get(0).getId());
        second.removeRoom(rooms.get(1).getId());
        first.removeRoom(rooms.get(2).getId());
        assertEquals("NAME_COOLDOWN", AbuseGuard.createRejection("new-proxy", "advert", now + 1000));
        assertNull(AbuseGuard.createRejection("normal-ip", "different", now + 1000));
        assertNull(AbuseGuard.createRejection("new-proxy", "advert", now + 120001));
    }

    @Test public void burstCleanupProtectsGamesReconnectsAndNamesCreatedAtNormalPace() throws Exception {
        Room retained = first.tryCreateRoom("burst", host(first, "slow-1"), 3215).room();
        Room playing = first.tryCreateRoom("burst", host(first, "slow-2"), 3215).room();
        Room reconnecting = second.tryCreateRoom("burst", host(second, "slow-3"), 3215).room();
        first.tryCreateRoom("burst", host(first, "slow-4"), 3215);
        playing.setGaming(true);
        reconnecting.reconnect.state = RelayReconnect.WAITING;
        long now = System.currentTimeMillis();
        assertEquals("NAME_BURST", AbuseGuard.createRejection("slow-5", "burst", now));
        for (Room room : AbuseGuard.spamRooms(now)) {
            assertNotSame(retained, room);
            assertNotSame(playing, room);
            assertNotSame(reconnecting, room);
        }
        assertNull(AbuseGuard.createRejection("slow-5", "burst", now + 120001));
        // Old history has expired; a fresh name can be used again.
        assertEquals(List.of(), AbuseGuard.spamRooms(now + 120001));
    }

    @Test public void removingRoomsDoesNotEraseRecentNameHistory() throws Exception {
        for (int i = 0; i < 4; i++) {
            Room room = first.tryCreateRoom("churn", host(first, "churn-" + i), 3215).room();
            assertNotNull(room);
            first.removeRoom(room.getId());
        }
        assertEquals("NAME_BURST", second.tryCreateRoom("churn", host(second, "churn-5"), 3215).rejection());
        long afterWindow = System.currentTimeMillis() + 31000;
        assertNull(AbuseGuard.createRejection("ordinary", "other", afterWindow));
        assertEquals("NAME_COOLDOWN", AbuseGuard.createRejection("churn-6", "churn", afterWindow));
    }

    @Test public void concurrentCrossShardCreatesCannotOvershootRateLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RoomManager.CreateResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                RoomManager manager = i % 2 == 0 ? first : second;
                ClientHandler host = host(manager, "concurrent-ip");
                String name = "room" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return manager.tryCreateRoom(name, host, 3215);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<RoomManager.CreateResult> result : results) {
                if (result.get(5, TimeUnit.SECONDS).room() != null) accepted++;
            }
            assertEquals(3, accepted);
            assertEquals(3, first.roomCount() + second.roomCount());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void disconnectAndRenameDoNotResetRateAndCooldownExpires() {
        long now = System.currentTimeMillis();
        assertTrue(AbuseGuard.tryOpenConnection("rate-ip"));
        assertNull(AbuseGuard.createRejection("rate-ip", "one", now));
        assertNull(AbuseGuard.createRejection("rate-ip", "two", now));
        assertNull(AbuseGuard.createRejection("rate-ip", "three", now));
        AbuseGuard.closeConnection("rate-ip");
        assertTrue(AbuseGuard.tryOpenConnection("rate-ip"));
        try {
            assertEquals("CREATES_10S", AbuseGuard.createRejection("rate-ip", "renamed", now));
            assertEquals("CREATE_COOLDOWN", AbuseGuard.createRejection("rate-ip", "again", now + 1000));
            assertNull(AbuseGuard.createRejection("rate-ip", "later", now + 60001));
        } finally {
            AbuseGuard.closeConnection("rate-ip");
        }
        for (int i = 0; i < 8; i++) {
            assertNull(AbuseGuard.createRejection("minute-ip", "room", now + i * 7000));
        }
        assertEquals("CREATES_60S", AbuseGuard.createRejection("minute-ip", "room", now + 56000));
    }

    @Test public void rejectionLogsIncludeSourceAndAreBoundedAcrossManyIps() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        long now = System.currentTimeMillis() + 100000;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            for (int i = 0; i < 1000; i++) {
                AbuseGuard.logRejection("many-ip-" + i, 1, "WAITING_ROOMS_NAME", now);
            }
            AbuseGuard.logRejection("later-ip", 1, "CREATES_10S", now + 10001);
        } finally {
            System.setOut(previous);
        }
        String log = output.toString(StandardCharsets.UTF_8);
        assertEquals(21, log.lines().count());
        assertTrue(log.contains("ip=many-ip-0 shard=1 reason=WAITING_ROOMS_NAME"));
        assertTrue(log.contains("suppressedSinceLastLog=980"));
    }
}
