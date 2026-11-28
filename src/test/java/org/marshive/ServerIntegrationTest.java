package org.marshive;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ServerIntegrationTest {
    @Test public void serverSurvivesMalformedTrafficAndStillRelaysGames() throws Exception {
        int base = availableBase();
        Path directory = Files.createTempDirectory(Path.of("target"), "server-integration-").toAbsolutePath();
        Path log = directory.resolve("server.log");
        Process process = startServer(base, directory);
        try {
            awaitReady(base, process);
            // Churn must release IP connection slots rather than exhausting the per-IP limit.
            for (int i = 0; i < 300; i++) query(base);

            try (Socket bad = connect(base + 3000)) {
                try {
                    bad.getOutputStream().write(new byte[ResourceLimits.MAX_SETTLEMENT_BYTES + 1]);
                    assertEquals(-1, bad.getInputStream().read());
                } catch (SocketException expectedClose) {
                    // A reset is also a valid rejection of an oversized request.
                }
            }
            assertEquals("ERR", metrics(base, "SETTLE|broken\n"));
            String settlement = "SETTLE|integration-test|1|PLANT_WIN|100|0,P,K,0|plant_name=Test&netplay_version=3227\n";
            assertEquals("OK", metrics(base, settlement));
            assertEquals("OK_DUP", metrics(base, settlement));
            assertEquals("OK", metrics(base, "SETTLE|event-limit|1|PLANT_WIN|100|"
                    + "0,P,K,0;".repeat(512) + "|plant_name=Test&netplay_version=3227\n"));
            for (String version : List.of("", "&netplay_version=3228", "&netplay_version=3307",
                    "&netplay_version=3307&netplay_version=3227")) {
                assertEquals("ERR", metrics(base, "SETTLE|rejected-version|1|PLANT_WIN|100|0,P,K,0"
                        + "|plant_name=WrongVersion" + version + "\n"));
            }
            assertEquals("ERR", metrics(base, "SETTLE|1|PLANT_WIN|100|0,P,K,0\n"));

            String stream = httpGet(base + 4000, "/api/stream");
            assertTrue(stream.contains("retry: 15000"));
            assertTrue(stream.contains("event: settle"));
            assertTrue(httpGet(base + 4000, "/api/recent-matches").contains("Test"));
            assertFalse(httpGet(base + 4000, "/api/recent-matches").contains("WrongVersion"));
            verifyExtendedCardStatistics(base);
            verifyModeSettlements(base, directory);
            verifyVersionStatistics(base, directory);
            rejectControlFlood(base);
            relayRoundTrip(base);
            reconnectRoundTrip(base);
            query(base);
            assertTrue(process.isAlive());
            assertFalse(Files.readString(log).contains("OutOfMemoryError"));
        } catch (Throwable error) {
            throw new AssertionError("Server log:\n" + Files.readString(log), error);
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    @Test public void acceptsAdditionalExplicitlyConfiguredSettlementVersions() throws Exception {
        int base = availableBase();
        Path directory = Files.createTempDirectory(Path.of("target"), "metrics-version-allowlist-").toAbsolutePath();
        Process process = startServer(base, directory, "--metrics-versions=3227,3307");
        try {
            awaitReady(base, process);
            for (int version : List.of(3227, 3307, 3228)) {
                assertEquals(version == 3228 ? "ERR" : "OK", metrics(base,
                        "SETTLE|allowlist-" + version + "|1|PLANT_WIN|100||netplay_version=" + version + "\n"));
            }
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/pvz_metrics.db"));
                 var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT netplay_version FROM match_results ORDER BY netplay_version")) {
                assertTrue(rows.next());
                assertEquals(3227, rows.getLong(1));
                assertTrue(rows.next());
                assertEquals(3307, rows.getLong(1));
                assertFalse(rows.next());
            }
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    @Test public void upgradesAnExistingDatabaseWithoutLosingMatchesAndCanRestart() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "server-version-upgrade-").toAbsolutePath();
        Files.createDirectories(directory.resolve("data"));
        String dbUrl = "jdbc:sqlite:" + directory.resolve("data/pvz_metrics.db");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
             java.sql.Statement st = conn.createStatement()) {
            // The deployed table before version reporting was added.
            st.execute("CREATE TABLE match_results (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "settle_id TEXT NOT NULL UNIQUE,room_id INTEGER NOT NULL,winner TEXT NOT NULL,"
                    + "duration_text TEXT NOT NULL DEFAULT '00:00',bg TEXT NOT NULL DEFAULT 'UNKNOWN',"
                    + "battle_type TEXT NOT NULL DEFAULT 'UNKNOWN',game_mode TEXT NOT NULL DEFAULT 'NORMAL',"
                    + "extra_packet TEXT NOT NULL DEFAULT 'false',extended_seeds TEXT NOT NULL DEFAULT 'false',"
                    + "ban_mode TEXT NOT NULL DEFAULT 'false',balance_patch TEXT NOT NULL DEFAULT 'false',"
                    + "mower_loss INTEGER NOT NULL DEFAULT 0,target_loss INTEGER NOT NULL DEFAULT 0,"
                    + "sunflower_loss INTEGER NOT NULL DEFAULT 0,grave_loss INTEGER NOT NULL DEFAULT 0,"
                    + "plant_name TEXT NOT NULL DEFAULT '',zombie_name TEXT NOT NULL DEFAULT '',finished_at TEXT NOT NULL)");
            st.execute("INSERT INTO match_results(settle_id,room_id,winner,plant_name,finished_at) "
                    + "VALUES('before-version',1,'PLANT','Historical','2026-10-01T00:00:00Z')");
        }
        for (int restart = 0; restart < 2; restart++) {
            int base = availableBase();
            Process process = startServer(base, directory);
            try {
                awaitReady(base, process);
                assertEquals(restart == 0 ? "OK" : "OK_DUP", metrics(base,
                        "SETTLE|after-version|1|PLANT_WIN|100||game_mode=NORMAL&netplay_version=3227\n"));
                assertEquals("{\"items\":[3227]}", httpGet(base + 4000, "/api/versions"));
                assertTrue(httpGet(base + 4000, "/api/recent-matches").contains("Historical"));
                assertFalse(httpGet(base + 4000, "/api/recent-matches?netplay_version=3227").contains("Historical"));
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
                     java.sql.Statement st = conn.createStatement();
                     java.sql.ResultSet rs = st.executeQuery("SELECT settle_id,netplay_version FROM match_results ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("before-version", rs.getString(1));
                    assertEquals(0, rs.getLong(2));
                    assertTrue(rs.next());
                    assertEquals("after-version", rs.getString(1));
                    assertEquals(3227, rs.getLong(2));
                    assertFalse(rs.next());
                }
            } catch (Throwable error) {
                throw new AssertionError("Server log:\n" + Files.readString(directory.resolve("server.log")), error);
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void roomNamesAreMaskedInListsJoinResponsesAndLogs() throws Exception {
        for (boolean custom : new boolean[]{false, true}) {
            int base = availableBase();
            Path directory = Files.createTempDirectory(Path.of("target"), "server-room-names-").toAbsolutePath();
            if (custom) {
                Files.createDirectories(directory.resolve("words"));
                Files.writeString(directory.resolve("words/custom.txt"), "\uFEFFtest,坏词\r\n");
            }
            Process process = startServer(base, directory, custom ? new String[]{"--sensitive-words-dir=words"} : new String[0]);
            try {
                awaitReady(base, process);
                try (Socket host = connect(base); Socket viewer = connect(base); Socket otherHost = connect(base)) {
                    String original = custom ? "[VS]Ｔ\nｅST坏词😊" : "[VS] Ｑ\nｑ好友😊";
                    String expected = custom ? "[VS]******😊" : "[VS] **好友😊";
                    byte[] create = createRoomCommand(withInvalidByte(original));
                    // Exercise fragmented CREATE packets as well as the usual complete packet.
                    host.getOutputStream().write(create, 0, 5);
                    host.getOutputStream().write(create, 5, create.length - 5);
                    int roomId = ByteBuffer.wrap(frame(host, 0x81)).getInt();
                    viewer.getOutputStream().write(2);
                    ByteBuffer list = ByteBuffer.wrap(frame(viewer, 0x82));
                    assertEquals(1, Byte.toUnsignedInt(list.get()));
                    assertEquals(roomId, list.getInt());
                    list.get(); // flags
                    assertEquals(3215, list.getInt());
                    byte[] roomName = new byte[Byte.toUnsignedInt(list.get())];
                    list.get(roomName);
                    assertEquals(expected, new String(roomName, StandardCharsets.UTF_8));
                    assertFalse(list.hasRemaining());

                    viewer.getOutputStream().write(ByteBuffer.allocate(11).put((byte) 3).putInt(roomId)
                            .putInt(3215).put((byte) 1).put((byte) 'G').array());
                    byte[] joined = frame(viewer, 0x83);
                    assertEquals(1, joined[0]);
                    assertEquals(expected, new String(joined, 10, Byte.toUnsignedInt(joined[9]), StandardCharsets.UTF_8));

                    String normal = "[COOP] 阳光好友😊";
                    otherHost.getOutputStream().write(createRoomCommand(normal.getBytes(StandardCharsets.UTF_8)));
                    frame(otherHost, 0x81);
                    otherHost.getOutputStream().write(2);
                    assertTrue(new String(frame(otherHost, 0x82), StandardCharsets.UTF_8).contains(normal));
                }
                String log = new String(Files.readAllBytes(directory.resolve("server.log")), StandardCharsets.UTF_8);
                assertTrue(log, log.contains("[ROOM_NAME_FILTER] loaded"));
                assertFalse(log, log.contains(custom ? "ＴｅST" : "Ｑｑ"));
                assertFalse(log, log.contains(custom ? "Ｔ\nｅ" : "Ｑ\nｑ"));
                assertTrue(log, log.contains(custom ? "[VS]******" : "[VS] **"));
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void longNamesAreTruncatedAndPlayersCanStillCreateJoinSpectateAndPlay() throws Exception {
        int base = availableBase();
        Path directory = Files.createTempDirectory(Path.of("target"), "server-name-limits-").toAbsolutePath();
        Files.createDirectories(directory.resolve("words"));
        Files.writeString(directory.resolve("words/custom.txt"), "坏词,对战资源站,COOP");
        Process process = startServer(base, directory, "--sensitive-words-dir=words");
        try {
            awaitReady(base, process);
            try (Socket host = connect(base); Socket guest = connect(base); Socket spectator = connect(base)) {
                String roomName = "[COOP]爱dev1ce老霸";
                host.getOutputStream().write(createRoomCommand("[COOP]爱dev1ce老霸对战资源站43.226.58.2".getBytes(StandardCharsets.UTF_8)));
                int roomId = ByteBuffer.wrap(frame(host, 0x81)).getInt();
                assertEquals(1001, roomId);
                guest.getOutputStream().write(2);
                ByteBuffer rooms = ByteBuffer.wrap(frame(guest, 0x82));
                assertEquals(1, rooms.get());
                assertEquals(roomId, rooms.getInt());
                rooms.get(); // flags
                assertEquals(3215, rooms.getInt());
                byte[] listedName = new byte[Byte.toUnsignedInt(rooms.get())];
                rooms.get(listedName);
                assertEquals(roomName, new String(listedName, StandardCharsets.UTF_8));
                assertFalse(rooms.hasRemaining());
                host.getOutputStream().write(new byte[]{0x0E, 1}); // Allow spectators.
                frame(host, 0x8E);

                String guestName = "Abcdef1234*";
                guest.getOutputStream().write(joinRoomCommand(3, roomId, withInvalidByte("Abcdef1234坏\n词Extra")));
                byte[] joined = frame(guest, 0x83);
                assertEquals(1, joined[0]);
                assertEquals(roomName, new String(joined, 10, Byte.toUnsignedInt(joined[9]), StandardCharsets.UTF_8));
                byte[] notification = frame(host, 0x84);
                assertEquals(guestName, new String(notification, 5, Byte.toUnsignedInt(notification[4]), StandardCharsets.UTF_8));

                String spectatorName = "阳**友AB12";
                spectator.getOutputStream().write(joinRoomCommand(0x0F, roomId, withInvalidByte("阳坏\u0000词友AB12多余")));
                assertEquals(1, frame(spectator, 0x83)[0]);
                byte[] list = frame(spectator, 0x8F);
                assertEquals(1, list[4]);
                assertEquals(spectatorName, new String(list, 6, Byte.toUnsignedInt(list[5]), StandardCharsets.UTF_8));

                // Parsing must consume the full submitted name so later game commands still work.
                host.getOutputStream().write(5);
                byte[] epoch = frame(host, 0x85);
                assertArrayEquals(epoch, frame(guest, 0x85));
                byte[] ready = ByteBuffer.allocate(5).put((byte) 0x0B).put(epoch).array();
                host.getOutputStream().write(ready);
                guest.getOutputStream().write(ready);
                assertArrayEquals(epoch, frame(host, 0x8B));
                assertArrayEquals(epoch, frame(guest, 0x8B));
                byte[] action = {27, 5, 1, 0, 0};
                guest.getOutputStream().write(action);
                assertArrayEquals(action, rawEvent(host));
                host.getOutputStream().write(action);
                assertArrayEquals(action, rawEvent(guest));
            }
        } catch (Throwable error) {
            throw new AssertionError("Server log:\n" + Files.readString(directory.resolve("server.log")), error);
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    @Test public void createFloodIsLoggedButGuestsCanStillJoinAndPlay() throws Exception {
        int base = availableBase();
        Path directory = Files.createTempDirectory(Path.of("target"), "server-create-limit-").toAbsolutePath();
        Process process = startServer(base, directory);
        try {
            awaitReady(base, process);
            try (Socket host = connect(base); Socket second = connect(base);
                 Socket third = connect(base); Socket limited = connect(base)) {
                host.getOutputStream().write(createRoomCommand("first".getBytes(StandardCharsets.UTF_8)));
                int roomId = ByteBuffer.wrap(frame(host, 0x81)).getInt();
                second.getOutputStream().write(createRoomCommand("second".getBytes(StandardCharsets.UTF_8)));
                frame(second, 0x81);
                third.getOutputStream().write(createRoomCommand("third".getBytes(StandardCharsets.UTF_8)));
                frame(third, 0x81);
                limited.getOutputStream().write(createRoomCommand("fourth".getBytes(StandardCharsets.UTF_8)));
                frame(limited, 0xFF);
                limited.getOutputStream().write(joinRoomCommand(3, roomId, "guest".getBytes(StandardCharsets.UTF_8)));
                assertEquals(1, frame(limited, 0x83)[0]);
                frame(host, 0x84);
                host.getOutputStream().write(5);
                byte[] epoch = frame(host, 0x85);
                assertArrayEquals(epoch, frame(limited, 0x85));
                byte[] ready = ByteBuffer.allocate(5).put((byte) 0x0B).put(epoch).array();
                host.getOutputStream().write(ready);
                limited.getOutputStream().write(ready);
                frame(host, 0x8B);
                frame(limited, 0x8B);
                byte[] action = {27, 5, 1, 0, 0};
                limited.getOutputStream().write(action);
                assertArrayEquals(action, rawEvent(host));
            }
            String log = Files.readString(directory.resolve("server.log"));
            assertTrue(log, log.contains("ip=127.0.0.1 version=3215"));
            assertTrue(log, log.contains("[ABUSE_REJECT] time="));
            assertTrue(log, log.contains("ip=127.0.0.1 shard=0 reason=CREATES_10S"));
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    private static byte[] withInvalidByte(String name) {
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(text.length + 1).put((byte) 0xff).put(text).array();
    }

    @Test public void rotatingSourceIpsCannotKeepDuplicateEmptyRoomsInLobby() throws Exception {
        int base = availableBase();
        Path directory = Files.createTempDirectory(Path.of("target"), "server-name-burst-").toAbsolutePath();
        Process process = startServer(base, directory);
        List<Socket> hosts = new ArrayList<>();
        try {
            awaitReady(base, process);
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Socket socket = new Socket();
                hosts.add(socket);
                socket.bind(new InetSocketAddress("127.0.0." + (i + 2), 0));
                socket.connect(new InetSocketAddress("127.0.0.1", base), 2000);
                socket.setSoTimeout(5000);
                if (i < 4) {
                    socket.getOutputStream().write(createRoomCommand("repeat-ad".getBytes(StandardCharsets.UTF_8)));
                    ids.add(ByteBuffer.wrap(frame(socket, 0x81)).getInt());
                }
            }
            try (Socket guest = connect(base)) {
                guest.getOutputStream().write(joinRoomCommand(3, ids.get(1), "guest".getBytes(StandardCharsets.UTF_8)));
                assertEquals(1, frame(guest, 0x83)[0]);
                frame(hosts.get(1), 0x84);
                Socket attacker = hosts.get(4);
                attacker.getOutputStream().write(createRoomCommand("repeat-ad".getBytes(StandardCharsets.UTF_8)));
                frame(attacker, 0xFF);
                assertEquals(0, frame(hosts.get(2), 0x86).length);
                assertEquals(0, frame(hosts.get(3), 0x86).length);
                attacker.getOutputStream().write(2);
                byte[] listed = frame(attacker, 0x82);
                // A full room with spectating disabled is intentionally hidden by the lobby.
                assertEquals(1, listed[0]);
                assertEquals(ids.get(0).intValue(), ByteBuffer.wrap(listed, 1, 4).getInt());
                hosts.get(2).getOutputStream().write(createRoomCommand("repeat-ad".getBytes(StandardCharsets.UTF_8)));
                frame(hosts.get(2), 0xFF);
                hosts.get(2).getOutputStream().write(createRoomCommand("normal".getBytes(StandardCharsets.UTF_8)));
                frame(hosts.get(2), 0x81);
                // The room that had a guest remains playable after cleanup.
                hosts.get(1).getOutputStream().write(5);
                byte[] epoch = frame(hosts.get(1), 0x85);
                assertArrayEquals(epoch, frame(guest, 0x85));
            }
            String log = Files.readString(directory.resolve("server.log"));
            assertTrue(log, log.contains("reason=NAME_BURST"));
            assertTrue(log, log.contains("name=[repeat-ad] nameCooldownMs="));
            assertTrue(log, log.contains("[ROOM_SPAM_CLEAN]"));
        } finally {
            for (Socket host : hosts) host.close();
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    private static byte[] joinRoomCommand(int command, int roomId, byte[] name) {
        return ByteBuffer.allocate(10 + name.length).put((byte) command).putInt(roomId)
                .putInt(3215).put((byte) name.length).put(name).array();
    }

    @Test public void missingConfiguredDictionaryPreventsServerStartup() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "server-missing-words-").toAbsolutePath();
        Process process = startServer(availableBase(), directory, "--sensitive-words-dir=missing");
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertFalse(Files.readString(directory.resolve("server.log")).contains("NIO Game Server Started"));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] createRoomCommand(byte[] name) {
        return ByteBuffer.allocate(6 + name.length).put((byte) 1).put((byte) name.length).put(name).putInt(3215).array();
    }

    private Process startServer(int base, Path directory, String... options) throws IOException {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> new File(p).getAbsolutePath()).reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx64m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", classpath, ServerApp.class.getName(), "--base=" + base, "--shards=1", "--dashboard=true"));
        command.addAll(Arrays.asList(options));
        return new ProcessBuilder(command)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("server.log").toFile()).start();
    }

    private void rejectControlFlood(int base) throws Exception {
        try (Socket host = connect(base); Socket guest = connect(base)) {
            host.getOutputStream().write(ByteBuffer.allocate(7).put((byte) 1).put((byte) 1)
                    .put((byte) 'F').putInt(3154).array());
            int room = ByteBuffer.wrap(frame(host, 0x81)).getInt();
            guest.getOutputStream().write(ByteBuffer.allocate(11).put((byte) 3).putInt(room)
                    .putInt(3154).put((byte) 1).put((byte) 'G').array());
            assertEquals(1, frame(guest, 0x83)[0]);
            host.getOutputStream().write(5);
            frame(host, 0x85);
            frame(guest, 0x85);
            // Reproduce the observed invalid P2P_OK stream while waiting for relay readiness.
            byte[] flood = new byte[4096];
            Arrays.fill(flood, (byte) 0x09);
            host.getOutputStream().write(flood);
            try {
                assertEquals(-1, host.getInputStream().read());
            } catch (SocketException expectedReset) {
                // Closing with unread input may reset instead of sending EOF.
            }
            query(base);
        }
    }

    private void verifyExtendedCardStatistics(int base) throws Exception {
        // Bundled game strings provide localized names without external files.
        String names = httpGet(base + 4000, "/api/seed-names");
        assertTrue(names.contains("\"seed_type\":209,\"en\":\"ENDURIAN\",\"zh\":\"榴莲\""));
        assertTrue(names.contains("\"seed_type\":102,\"en\":\"CROSSING_GUARD_ZOMBIE\",\"zh\":\"交通协管僵尸\""));
        assertTrue(names.contains("\"seed_type\":106,\"en\":\"PIGEON_FEEDER_ZOMBIE\",\"zh\":\"喂鸽僵尸\""));
        assertTrue(names.contains("\"seed_type\":211,\"en\":\"LIGHTNING_REED\",\"zh\":\"闪电芦苇\""));
        for (int excluded : new int[]{107, 108, 109, 110, 111, 112, 212, 213, 214}) {
            assertFalse(names.contains("\"seed_type\":" + excluded + ","));
        }

        // Use deliberately reversed side labels to verify classification by the game's card IDs.
        String events = "0,Z,K,209;1,Z,K,210;2,Z,K,1;3,Z,K,211;4,Z,K,0;"
                + "5,P,K,102;6,P,K,103;7,P,K,104;8,P,K,62;9,P,K,106";
        String meta = "bg=0&addon_ep=0&addon_bp=1&addon_ban=0&plant_use=209:3,210:2&zombie_use=102:3,103:2"
                + "&netplay_version=3227";
        assertEquals("OK", metrics(base, "SETTLE|extended-cards|2|PLANT_WIN|100|" + events + "|" + meta + "\n"));
        String filters = "&extra_packet=false&ban_mode=false&mode=DAY";
        String plants = httpGet(base + 4000, "/api/card-stats?side=PLANT" + filters);
        String zombies = httpGet(base + 4000, "/api/card-stats?side=ZOMBIE" + filters);
        assertTrue(plants.contains("\"seed_en\":\"RED_STINGER\""));
        assertTrue(plants.contains("\"seed_en\":\"LIGHTNING_REED\""));
        assertFalse(plants.contains("\"seed_type\":102,"));
        assertTrue(zombies.contains("\"seed_en\":\"SCIENTIST_ZOMBIE\""));
        assertTrue(zombies.contains("\"seed_en\":\"GRAVEDIGGER_ZOMBIE\""));
        assertTrue(zombies.contains("\"seed_en\":\"PIGEON_FEEDER_ZOMBIE\""));
        assertFalse(zombies.contains("\"seed_type\":209,"));
        String decks = httpGet(base + 4000, "/api/top-decks?side=PLANT" + filters);
        assertTrue(decks.contains("\"core_en\":\"ENDURIANRED_STINGER\""));
    }

    private void verifyModeSettlements(int base, Path directory) throws Exception {
        String events = "0,P,K,0;1,P,K,2;2,P,K,3;3,P,K,4;4,P,K,5";
        for (String mode : new String[]{"NORMAL", "SHUFFLE", "CLASSIC"}) {
            String winner = "CLASSIC".equals(mode) ? "ZOMBIE_WIN" : "PLANT_WIN";
            String usage = switch (mode) {
                case "NORMAL" -> "0:20,2:10";
                case "CLASSIC" -> "3:40,4:30";
                default -> "5:60,2:50";
            };
            assertEquals("OK", metrics(base, "SETTLE|mode-" + mode + "|3|" + winner + "|100|" + events
                    + "|bg=1&battle=10&game_mode=" + mode + "&addon_ep=0&addon_ban=0&plant_use=" + usage
                    + "&plant_name=player-" + mode + "&netplay_version=3227\n"));
        }
        assertEquals("ERR", metrics(base, "SETTLE|invalid-mode|3|PLANT_WIN|100||game_mode=INVALID&netplay_version=3227\n"));
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("data/pvz_metrics.db"));
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT game_mode, extended_seeds, balance_patch FROM match_results WHERE settle_id LIKE 'mode-%'")) {
            int count = 0;
            while (rs.next()) {
                boolean classic = "CLASSIC".equals(rs.getString("game_mode"));
                assertEquals(Boolean.toString(!classic), rs.getString("extended_seeds"));
                assertEquals(Boolean.toString(!classic), rs.getString("balance_patch"));
                count++;
            }
            assertEquals(3, count);
        }
        // An old balance-off match remains NORMAL; it must not be inferred to be CLASSIC.
        assertEquals("OK", metrics(base, "SETTLE|legacy-switches|3|PLANT_WIN|100|" + events
                + "|bg=1&addon_es=0&addon_bp=0&addon_ep=0&addon_ban=0&plant_use=0:1,2:1&netplay_version=3227\n"));
        // These must stay outside the selected map / ban / extra-slot scope.
        for (String variant : new String[]{"bg=0&addon_ep=0&addon_ban=0",
                "bg=1&addon_ep=0&addon_ban=1", "bg=1&addon_ep=1&addon_ban=0"}) {
            assertEquals("OK", metrics(base, "SETTLE|other-" + variant + "|3|ZOMBIE_WIN|100|"
                    + events + ";5,P,K,6|game_mode=NORMAL&" + variant + "&plant_use=3:500,4:400&netplay_version=3227\n"));
        }
        String filters = "?side=PLANT&extra_packet=false&ban_mode=false&mode=NIGHT";
        String cards = httpGet(base + 4000, "/api/card-stats" + filters);
        assertTrue(cards, cards.contains("\"seed_type\":5,\"picked\":2,\"banned\":0,\"won\":2"));
        assertTrue(cards, cards.contains("\"pick_rate\":1.000000"));
        String decks = httpGet(base + 4000, "/api/top-decks" + filters);
        assertTrue(decks, decks.contains("\"total_plays\":2"));
        assertTrue(decks, decks.contains("\"plays\":2,\"wins\":2"));
        assertTrue(decks, decks.contains("\"core_en\":\"" + SeedTypeNames.nameOf(0) + SeedTypeNames.nameOf(2) + "\""));
        assertTrue(httpGet(base + 4000, "/api/card-stats" + filters.replace("side=PLANT", "side=ZOMBIE")).contains("\"items\":[]"));
        // Removed switches and unsupported mode parameters cannot alter the fixed NORMAL scope.
        for (String endpoint : new String[]{"/api/card-stats", "/api/top-decks"}) {
            String expected = httpGet(base + 4000, endpoint + filters);
            for (String ignored : new String[]{"balance_patch=true", "balance_patch=false",
                    "game_mode=SHUFFLE", "game_mode=CLASSIC"}) {
                assertEquals(expected, httpGet(base + 4000, endpoint + filters + "&" + ignored));
            }
        }
        // A database with only shuffle data for this map must yield no dashboard statistics.
        assertEquals("OK", metrics(base, "SETTLE|shuffle-no-picks|3|PLANT_WIN|100||"
                + "game_mode=SHUFFLE&bg=4&plant_use=0:2&addon_ep=0&addon_ban=0&netplay_version=3227\n"));
        String empty = "?mode=ROOF&extra_packet=false&ban_mode=false";
        assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/card-stats" + empty));
        assertEquals("{\"items\":[],\"total_plays\":0}", httpGet(base + 4000, "/api/top-decks" + empty));
        String recent = httpGet(base + 4000, "/api/recent-matches");
        assertTrue(recent, recent.contains("player-NORMAL"));
        assertFalse(recent, recent.contains("player-SHUFFLE"));
        assertFalse(recent, recent.contains("player-CLASSIC"));

        String html = httpGet(base + 4000, "/");
        assertTrue(html.contains("对战资料站"));
        assertTrue(html.contains("data-server-path=\"/data/\""));
        assertTrue(html.contains("data-server-path=\"/almanac/\""));
        String dashboard = httpGet(base + 4000, "/data/");
        assertTrue(dashboard.contains("id=\"extraSel\""));
        assertTrue(dashboard.contains("id=\"banSel\""));
        assertTrue(dashboard.contains("id=\"modeSel\""));
        assertTrue(dashboard.contains("id=\"deckTitle\""));
        assertTrue(html.contains("class=\"destination atlas-destination\""));
        assertFalse(html.contains("gameModeSel"));
        assertFalse(html.contains("game_mode"));
        assertFalse(html.contains("bpSel"));
        assertFalse(html.contains("balance_patch"));
        assertTrue(httpGet(base + 4000, "/site/data.js").contains("function renderDecks"));
        assertTrue(httpGet(base + 4000, "/site/ui/data-scene.webp").length() > 0);
        assertTrue(httpGet(base + 4000, "/assets/site/ui/data-scene.webp").length() > 0);
        assertHttpStatus(base + 4000, "/assets/cards/SEED_PACKET_00.PNG", 200);
        assertHttpStatus(base + 4000, "/assets/cards/SEED_PACKET_106.PNG", 200);
        assertHttpStatus(base + 4000, "/assets/cards/SEED_PACKET_211.PNG", 200);
        assertHttpStatus(base + 4000, "/assets/cards/SEED_PACKET_91_MOUND_LEVEL_0.PNG", 200);
        assertHttpStatus(base + 4000, "/assets/cards/%2e%2e%2fsite%2fdata.js", 404);
        String almanac = httpGet(base + 4000, "/almanac/");
        assertTrue(almanac.contains("<title>植物图鉴 · PvZ TV</title>"));
        assertTrue(almanac.contains("V3227"));
        assertTrue(almanac.contains("hostname.endsWith(\"github.io\")"));
        Files.writeString(directory.resolve("dashboard.html"), dashboard);
    }

    private void verifyVersionStatistics(int base, Path directory) throws Exception {
        assertEquals("{\"items\":[3227]}", httpGet(base + 4000, "/api/versions"));
        String events = "0,P,K,0;1,P,K,2;2,P,K,3;3,P,K,4;4,P,K,5";
        String meta = "bg=2&game_mode=NORMAL&addon_ep=0&addon_ban=0&netplay_version=3227";
        assertEquals("OK", metrics(base, "SETTLE|v-legacy|5|ZOMBIE_WIN|100|" + events
                + "|" + meta + "&plant_name=ver-unknown\n"));
        for (int version : new int[]{9, 10, 3212}) {
            String winner = version == 10 ? "ZOMBIE_WIN" : "PLANT_WIN";
            String usage = version == 10 ? "3:200,4:100" : "0:20,2:10";
            assertEquals("OK", metrics(base, "SETTLE|v-" + version + "|5|" + winner + "|100|" + events
                    + "|" + meta + "&plant_name=ver-" + version + "&plant_use=" + usage + "\n"));
        }
        assertEquals("OK", metrics(base, "SETTLE|v-duplicate-version|5|ZOMBIE_WIN|100|" + events
                + "|" + meta + "&plant_name=ver-3212-second&plant_use=0:20,2:10\n"));
        // Higher versions reported only by excluded modes must not displace visible versions.
        for (String mode : new String[]{"CLASSIC", "SHUFFLE"}) {
            assertEquals("OK", metrics(base, "SETTLE|v-" + mode + "|5|ZOMBIE_WIN|100|" + events
                    + "|" + meta.replace("NORMAL", mode) + "&plant_name=ver-hidden&plant_use=3:900,4:800\n"));
        }
        // Model historical rows without weakening the live settlement allowlist.
        try (var database = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/pvz_metrics.db"));
             var update = database.prepareStatement("UPDATE match_results SET netplay_version=? WHERE settle_id=?")) {
            Map<String, Integer> historical = Map.of("v-legacy", 0, "v-9", 9, "v-10", 10,
                    "v-3212", 3212, "v-duplicate-version", 3212, "v-CLASSIC", 4000, "v-SHUFFLE", 4000);
            for (var row : historical.entrySet()) {
                update.setInt(1, row.getValue());
                update.setString(2, row.getKey());
                assertEquals(1, update.executeUpdate());
            }
        }
        assertEquals("{\"items\":[3227,3212]}", httpGet(base + 4000, "/api/versions"));
        String filters = "?side=PLANT&extra_packet=false&ban_mode=false&mode=POOL";
        String allCards = httpGet(base + 4000, "/api/card-stats" + filters);
        assertTrue(allCards, allCards.contains("\"seed_type\":5,\"picked\":5,\"banned\":0,\"won\":2"));
        String allDecks = httpGet(base + 4000, "/api/top-decks" + filters);
        assertTrue(allDecks, allDecks.contains("\"total_plays\":5"));
        assertTrue(allDecks, allDecks.contains("\"plays\":5,\"wins\":2"));
        for (String endpoint : new String[]{"/api/top-decks", "/api/card-stats", "/api/recent-matches"}) {
            assertEquals(httpGet(base + 4000, endpoint + filters),
                    httpGet(base + 4000, endpoint + filters + "&netplay_version=all"));
            for (String invalid : new String[]{"0", "-1", "4294967296", "abc", ""}) {
                assertHttpStatus(base + 4000, endpoint + filters + "&netplay_version=" + invalid, 400);
            }
        }
        for (int version : new int[]{9, 10, 3212}) {
            String selected = filters + "&netplay_version=" + version;
            int plays = version == 3212 ? 2 : 1;
            int wins = version == 10 ? 0 : 1;
            String cards = httpGet(base + 4000, "/api/card-stats" + selected);
            assertTrue(cards, cards.contains("\"seed_type\":5,\"picked\":" + plays + ",\"banned\":0,\"won\":" + wins));
            assertTrue(cards, cards.contains("\"pick_rate\":1.000000"));
            String decks = httpGet(base + 4000, "/api/top-decks" + selected);
            assertTrue(decks, decks.contains("\"total_plays\":" + plays));
            String core = version == 10 ? SeedTypeNames.nameOf(3) + SeedTypeNames.nameOf(4)
                    : SeedTypeNames.nameOf(0) + SeedTypeNames.nameOf(2);
            assertTrue(decks, decks.contains("\"core_en\":\"" + core + "\""));
            String recent = httpGet(base + 4000, "/api/recent-matches?netplay_version=" + version);
            assertTrue(recent, recent.contains("ver-" + version));
            assertFalse(recent, recent.contains("ver-unknown"));
            assertFalse(recent, recent.contains("ver-hidden"));
            for (int other : new int[]{9, 10, 3212}) {
                if (other != version) assertFalse(recent, recent.contains("ver-" + other));
            }
        }
        assertTrue(httpGet(base + 4000, "/api/recent-matches").contains("ver-unknown"));
        assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/card-stats" + filters + "&netplay_version=999"));
        assertEquals("{\"items\":[],\"total_plays\":0}", httpGet(base + 4000, "/api/top-decks" + filters + "&netplay_version=999"));
        assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/recent-matches?netplay_version=999"));
        for (String changed : new String[]{filters.replace("POOL", "ROOF"),
                filters.replace("ban_mode=false", "ban_mode=true"), filters.replace("extra_packet=false", "extra_packet=true")}) {
            assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/card-stats" + changed + "&netplay_version=3212"));
        }
        assertEquals("ERR", metrics(base, "SETTLE|v-invalid|5|PLANT_WIN|100||netplay_version=4294967296\n"));
        assertEquals("OK", metrics(base, "SETTLE|v-new|5|PLANT_WIN|100||game_mode=NORMAL&netplay_version=3227\n"));
        assertEquals("{\"items\":[3227,3212]}", httpGet(base + 4000, "/api/versions"));
        String dashboard = httpGet(base + 4000, "/data/");
        assertTrue(dashboard.contains("id=\"versionSel\""));
        assertTrue(dashboard.contains("value=\"all\""));
        assertTrue(dashboard.contains("/site/data.js"));
        String script = httpGet(base + 4000, "/site/data.js");
        assertTrue(script.contains("/api/versions"));
        assertTrue(script.contains("netplay_version"));
    }

    private void relayRoundTrip(int base) throws Exception {
        try (Socket host = connect(base); Socket guest = connect(base)) {
            host.getOutputStream().write(ByteBuffer.allocate(7).put((byte) 1).put((byte) 1)
                    .put((byte) 'H').putInt(3154).array());
            int room = ByteBuffer.wrap(frame(host, 0x81)).getInt();
            guest.getOutputStream().write(ByteBuffer.allocate(11).put((byte) 3).putInt(room)
                    .putInt(3154).put((byte) 1).put((byte) 'G').array());
            assertEquals(1, frame(guest, 0x83)[0]);
            host.getOutputStream().write(5);
            byte[] epoch = frame(host, 0x85);
            assertArrayEquals(epoch, frame(guest, 0x85));
            byte[] ready = ByteBuffer.allocate(5).put((byte) 0x0B).put(epoch).array();
            host.getOutputStream().write(ready);
            guest.getOutputStream().write(ready);
            assertArrayEquals(epoch, frame(host, 0x8B));
            assertArrayEquals(epoch, frame(guest, 0x8B));
            byte[] payload = new byte[8192];
            new Random(1234).nextBytes(payload);
            host.getOutputStream().write(payload);
            assertArrayEquals(payload, guest.getInputStream().readNBytes(payload.length));
            guest.getOutputStream().write(payload);
            assertArrayEquals(payload, host.getInputStream().readNBytes(payload.length));
        }
    }

    private byte[] rawEvent(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int type = in.readUnsignedByte(), size = in.readUnsignedByte();
        if (size < 2) throw new IOException("Invalid event size");
        byte[] data = new byte[size]; data[0] = (byte) type; data[1] = (byte) size;
        in.readFully(data, 2, size - 2); return data;
    }

    private void reconnectRoundTrip(int base) throws Exception {
        byte[] identity = ByteBuffer.allocate(16).putLong(0xba1a2c90c0774a84L).putLong(0x88096e23c5834265L).array();
        try (Socket host = connect(base); Socket guest = connect(base); Socket rejoined = connect(base)) {
            host.getOutputStream().write(ByteBuffer.allocate(23).put((byte) 1).put((byte) 1)
                    .put((byte) 'H').putInt(RelayReconnect.PLAYER_ID_VERSION).put(new byte[16]).array());
            int room = ByteBuffer.wrap(frame(host, 0x81)).getInt();
            guest.getOutputStream().write(ByteBuffer.allocate(27).put((byte) 3).putInt(room)
                    .putInt(RelayReconnect.PLAYER_ID_VERSION).put((byte) 1).put((byte) 'G').put(identity).array());
            assertEquals(1, frame(guest, 0x83)[0]);
            host.getOutputStream().write(5);
            byte[] relayEpoch = frame(host, 0x85); frame(guest, 0x85);
            byte[] ready = ByteBuffer.allocate(5).put((byte) 0x0B).put(relayEpoch).array();
            host.getOutputStream().write(ready); guest.getOutputStream().write(ready);
            frame(host, 0x8B); frame(guest, 0x8B);
            guest.close();
            byte[] lost = rawEvent(host);
            assertEquals(RelayReconnect.CONTROL, lost[0] & 255);
            int epoch = RelayReconnect.intAt(lost, 4), transfer = RelayReconnect.intAt(lost, 8);
            host.getOutputStream().write(RelayReconnect.control(RelayReconnect.FROZEN, 0, epoch, transfer, 1));
            byte[] waiting = rawEvent(host);
            assertEquals(RelayReconnect.WAITING, waiting[3]);
            rejoined.getOutputStream().write(ByteBuffer.allocate(25).put((byte) 0x12).putInt(room)
                    .putInt(RelayReconnect.PLAYER_ID_VERSION).put(identity).array());
            byte[] paired = frame(rejoined, 0x91); assertEquals(0, paired[0]);
            transfer = RelayReconnect.intAt(paired, 9);
            assertEquals(RelayReconnect.ATTACHING, rawEvent(host)[3]);
            rejoined.getOutputStream().write(ByteBuffer.allocate(9).put((byte) 0x13).putInt(epoch).putInt(transfer).array());
            frame(rejoined, 0x92); assertEquals(RelayReconnect.SYNC, rawEvent(host)[2]);
            byte[] data = {1,2,3,4}; int hash = 0x811c9dc5; for (byte b : data) hash = (hash ^ (b & 255)) * 16777619;
            byte[] begin = ByteBuffer.allocate(26).put((byte) RelayReconnect.BEGIN).put((byte) 26).putInt(epoch).putInt(transfer)
                    .putShort((short) 1).putShort((short) 76).putInt(4).putInt(hash).putInt(99).array();
            byte[] chunk = ByteBuffer.allocate(20).put((byte) RelayReconnect.CHUNK).put((byte) 20).putInt(epoch).putInt(transfer)
                    .putInt(0).putShort((short) 4).put(data).array();
            byte[] end = ByteBuffer.allocate(10).put((byte) RelayReconnect.END).put((byte) 10).putInt(epoch).putInt(transfer).array();
            for (byte[] event : new byte[][]{begin, chunk, end}) {
                host.getOutputStream().write(event); assertArrayEquals(event, rawEvent(rejoined));
            }
            rejoined.getOutputStream().write(RelayReconnect.control(RelayReconnect.LOADED, 0, epoch, transfer, 1));
            assertEquals(RelayReconnect.LOADED, rawEvent(host)[2]);
            host.getOutputStream().write(RelayReconnect.control(RelayReconnect.COMMIT, 0, epoch, transfer, 0));
            assertEquals(RelayReconnect.RESUME, rawEvent(host)[2]); assertEquals(RelayReconnect.RESUME, rawEvent(rejoined)[2]);
            byte[] action = {27,5,1,0,0}; rejoined.getOutputStream().write(action); assertArrayEquals(action, rawEvent(host));
            host.getOutputStream().write(action); assertArrayEquals(action, rawEvent(rejoined));
        }
    }

    private byte[] frame(Socket socket, int expectedType) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        for (int i = 0; i < 30; i++) {
            int type = input.readUnsignedByte();
            byte[] payload = new byte[input.readUnsignedShort()];
            input.readFully(payload);
            if (type == expectedType) return payload;
            if (type == 0xff) throw new IOException("Game returned error " + Arrays.toString(payload));
        }
        throw new IOException("Expected frame " + expectedType);
    }

    private Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(5000);
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }

    private void query(int port) throws IOException {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(2);
            frame(socket, 0x82);
        }
    }

    private String metrics(int base, String text) throws IOException {
        try (Socket socket = connect(base + 3000)) {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private String httpGet(int port, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(5000);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    private void assertHttpStatus(int port, String path, int status) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(5000);
        try {
            assertEquals(status, connection.getResponseCode());
        } finally { connection.disconnect(); }
    }

    private void awaitReady(int base, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { query(base); return; }
            catch (IOException e) { Thread.sleep(100); }
        }
        fail("Server did not become ready");
    }

    private int availableBase() throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base = 20000 + new Random().nextInt(20000);
            List<ServerSocket> sockets = new ArrayList<>();
            try {
                for (int offset : new int[]{0, 1000, 1004, 3000, 4000}) sockets.add(new ServerSocket(base + offset));
                return base;
            } catch (IOException unavailable) {
                // Try another complete port set.
            } finally {
                for (ServerSocket socket : sockets) socket.close();
            }
        }
        throw new IOException("No available port set for integration test");
    }
}
