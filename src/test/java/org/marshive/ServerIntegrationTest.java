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
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> new File(p).getAbsolutePath()).reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
        Process process = new ProcessBuilder(java, "-Xmx64m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", classpath, ServerApp.class.getName(), "--base=" + base, "--shards=1", "--dashboard=true")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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
        String filters = "&extra_packet=false&balance_patch=true&ban_mode=false&mode=DAY";
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
