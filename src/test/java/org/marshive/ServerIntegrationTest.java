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
            String settlement = "SETTLE|integration-test|1|PLANT_WIN|100|0,P,K,0|plant_name=Test\n";
            assertEquals("OK", metrics(base, settlement));
            assertEquals("OK_DUP", metrics(base, settlement));
            assertEquals("OK", metrics(base, "SETTLE|event-limit|1|PLANT_WIN|100|"
                    + "0,P,K,0;".repeat(512) + "|plant_name=Test\n"));

            String stream = httpGet(base + 4000, "/api/stream");
            assertTrue(stream.contains("retry: 15000"));
            assertTrue(stream.contains("event: settle"));
            assertTrue(httpGet(base + 4000, "/api/recent-matches").contains("Test"));
            verifyExtendedCardStatistics(base);
            verifyModeSettlements(base, directory);
            verifyVersionStatistics(base);
            rejectControlFlood(base);
            relayRoundTrip(base);
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
                        "SETTLE|after-version|1|PLANT_WIN|100||game_mode=NORMAL&netplay_version=3212\n"));
                assertEquals("{\"items\":[3212]}", httpGet(base + 4000, "/api/versions"));
                assertTrue(httpGet(base + 4000, "/api/recent-matches").contains("Historical"));
                assertFalse(httpGet(base + 4000, "/api/recent-matches?netplay_version=3212").contains("Historical"));
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
                     java.sql.Statement st = conn.createStatement();
                     java.sql.ResultSet rs = st.executeQuery("SELECT settle_id,netplay_version FROM match_results ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("before-version", rs.getString(1));
                    assertEquals(0, rs.getLong(2));
                    assertTrue(rs.next());
                    assertEquals("after-version", rs.getString(1));
                    assertEquals(3212, rs.getLong(2));
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

    private Process startServer(int base, Path directory) throws IOException {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> new File(p).getAbsolutePath()).reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
        return new ProcessBuilder(java, "-Xmx64m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", classpath, ServerApp.class.getName(), "--base=" + base, "--shards=1", "--dashboard=true")
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
        // Without external language files, the existing loader falls back to English keys.
        String names = httpGet(base + 4000, "/api/seed-names");
        assertTrue(names.contains("\"seed_type\":209,\"en\":\"ENDURIAN\",\"zh\":\"ENDURIAN\""));
        assertTrue(names.contains("\"seed_type\":102,\"en\":\"CROSSING_GUARD_ZOMBIE\",\"zh\":\"CROSSING_GUARD_ZOMBIE\""));
        for (int excluded : new int[]{105, 106, 107, 108, 109, 110, 211, 212}) {
            assertFalse(names.contains("\"seed_type\":" + excluded + ","));
        }

        // Use deliberately reversed side labels to verify classification by the game's card IDs.
        String events = "0,Z,K,209;1,Z,K,210;2,Z,K,1;3,Z,K,2;4,Z,K,0;"
                + "5,P,K,102;6,P,K,103;7,P,K,104;8,P,K,62;9,P,K,64";
        String meta = "bg=0&addon_ep=0&addon_bp=1&addon_ban=0&plant_use=209:3,210:2&zombie_use=102:3,103:2";
        assertEquals("OK", metrics(base, "SETTLE|extended-cards|2|PLANT_WIN|100|" + events + "|" + meta + "\n"));
        String filters = "&extra_packet=false&ban_mode=false&mode=DAY";
        String plants = httpGet(base + 4000, "/api/card-stats?side=PLANT" + filters);
        String zombies = httpGet(base + 4000, "/api/card-stats?side=ZOMBIE" + filters);
        assertTrue(plants.contains("\"seed_en\":\"RED_STINGER\""));
        assertFalse(plants.contains("\"seed_type\":102,"));
        assertTrue(zombies.contains("\"seed_en\":\"SCIENTIST_ZOMBIE\""));
        assertTrue(zombies.contains("\"seed_en\":\"GRAVEDIGGER_ZOMBIE\""));
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
                    + "&plant_name=player-" + mode + "\n"));
        }
        assertEquals("ERR", metrics(base, "SETTLE|invalid-mode|3|PLANT_WIN|100||game_mode=INVALID\n"));
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
                + "|bg=1&addon_es=0&addon_bp=0&addon_ep=0&addon_ban=0&plant_use=0:1,2:1\n"));
        // These must stay outside the selected map / ban / extra-slot scope.
        for (String variant : new String[]{"bg=0&addon_ep=0&addon_ban=0",
                "bg=1&addon_ep=0&addon_ban=1", "bg=1&addon_ep=1&addon_ban=0"}) {
            assertEquals("OK", metrics(base, "SETTLE|other-" + variant + "|3|ZOMBIE_WIN|100|"
                    + events + ";5,P,K,6|game_mode=NORMAL&" + variant + "&plant_use=3:500,4:400\n"));
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
                + "game_mode=SHUFFLE&bg=4&plant_use=0:2&addon_ep=0&addon_ban=0\n"));
        String empty = "?mode=ROOF&extra_packet=false&ban_mode=false";
        assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/card-stats" + empty));
        assertEquals("{\"items\":[],\"total_plays\":0}", httpGet(base + 4000, "/api/top-decks" + empty));
        String recent = httpGet(base + 4000, "/api/recent-matches");
        assertTrue(recent, recent.contains("player-NORMAL"));
        assertFalse(recent, recent.contains("player-SHUFFLE"));
        assertFalse(recent, recent.contains("player-CLASSIC"));

        String html = httpGet(base + 4000, "/");
        assertTrue(html.contains("id='extraSel'"));
        assertTrue(html.contains("id='banSel'"));
        assertTrue(html.contains("id='modeSel'"));
        assertFalse(html.contains("gameModeSel"));
        assertFalse(html.contains("game_mode"));
        assertFalse(html.contains("bpSel"));
        assertFalse(html.contains("balance_patch"));
        Files.writeString(directory.resolve("dashboard.html"), html);
        Files.writeString(directory.resolve("dashboard.js"), html.substring(html.indexOf("<script>") + 8, html.indexOf("</script>")));
    }

    private void verifyVersionStatistics(int base) throws Exception {
        assertEquals("{\"items\":[]}", httpGet(base + 4000, "/api/versions"));
        String events = "0,P,K,0;1,P,K,2;2,P,K,3;3,P,K,4;4,P,K,5";
        String meta = "bg=2&game_mode=NORMAL&addon_ep=0&addon_ban=0";
        assertEquals("OK", metrics(base, "SETTLE|v-legacy|5|ZOMBIE_WIN|100|" + events
                + "|" + meta + "&plant_name=ver-unknown\n"));
        for (int version : new int[]{9, 10, 3212}) {
            String winner = version == 10 ? "ZOMBIE_WIN" : "PLANT_WIN";
            String usage = version == 10 ? "3:200,4:100" : "0:20,2:10";
            assertEquals("OK", metrics(base, "SETTLE|v-" + version + "|5|" + winner + "|100|" + events
                    + "|" + meta + "&netplay_version=" + version + "&plant_name=ver-" + version + "&plant_use=" + usage + "\n"));
            if (version == 9) assertEquals("{\"items\":[9]}", httpGet(base + 4000, "/api/versions"));
        }
        assertEquals("OK", metrics(base, "SETTLE|v-duplicate-version|5|ZOMBIE_WIN|100|" + events
                + "|" + meta + "&netplay_version=3212&plant_name=ver-3212-second&plant_use=0:20,2:10\n"));
        // Higher versions reported only by excluded modes must not displace visible versions.
        for (String mode : new String[]{"CLASSIC", "SHUFFLE"}) {
            assertEquals("OK", metrics(base, "SETTLE|v-" + mode + "|5|ZOMBIE_WIN|100|" + events
                    + "|" + meta.replace("NORMAL", mode) + "&netplay_version=4000&plant_name=ver-hidden&plant_use=3:900,4:800\n"));
        }
        assertEquals("{\"items\":[3212,10]}", httpGet(base + 4000, "/api/versions"));
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
        assertEquals("OK", metrics(base, "SETTLE|v-new|5|PLANT_WIN|100||game_mode=NORMAL&netplay_version=3213\n"));
        assertEquals("{\"items\":[3213,3212]}", httpGet(base + 4000, "/api/versions"));
        String html = httpGet(base + 4000, "/");
        assertTrue(html.contains("id='versionSel'"));
        assertTrue(html.contains("value='all'"));
        assertTrue(html.contains("/api/versions"));
        assertTrue(html.contains("&netplay_version="));
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
