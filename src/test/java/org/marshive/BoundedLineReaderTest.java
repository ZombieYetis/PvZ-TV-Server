package org.marshive;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class BoundedLineReaderTest {
    private String readBytes(byte[] bytes, int limit) throws IOException {
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            @Override public void setSoTimeout(int timeout) {}
        };
        try (socket) { return BoundedLineReader.read(socket, limit, 1000); }
    }

    @Test public void handlesUtf8CrlfEofAndExactLimit() throws Exception {
        assertEquals("中文", readBytes("中文\r\n".getBytes(StandardCharsets.UTF_8), 8));
        assertEquals("abcd", readBytes("abcd\n".getBytes(StandardCharsets.UTF_8), 4));
        assertEquals("abcd", readBytes("abcd".getBytes(StandardCharsets.UTF_8), 4));
        assertEquals("", readBytes(new byte[]{10}, 4));
        assertNull(readBytes(new byte[0], 4));
        assertThrows(IOException.class, () -> readBytes("abcde\n".getBytes(StandardCharsets.UTF_8), 4));
    }

    @Test public void rejectsOversizedLineWithoutWaitingForNewline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket sender = new Socket("127.0.0.1", listener.getLocalPort());
             Socket receiver = listener.accept()) {
            sender.getOutputStream().write(new byte[65]);
            assertThrows(IOException.class, () -> BoundedLineReader.read(receiver, 64, 1000));
        }
    }

    @Test public void deadlineStopsSlowDripEvenWhenEveryReadMakesProgress() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0);
             Socket sender = new Socket("127.0.0.1", listener.getLocalPort());
             Socket receiver = listener.accept()) {
            Future<?> writer = worker.submit(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        sender.getOutputStream().write('a');
                        Thread.sleep(30);
                    }
                } catch (IOException | InterruptedException ignored) {}
            });
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> BoundedLineReader.read(receiver, 1024, 200));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            writer.cancel(true);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void socketDeadlineClosesAnIdleConnection() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket sender = new Socket("127.0.0.1", listener.getLocalPort());
             Socket receiver = listener.accept()) {
            ScheduledFuture<?> deadline = ServerHealth.closeAfter(receiver, 100);
            try {
                assertThrows(IOException.class, () -> receiver.getInputStream().read());
                assertTrue(receiver.isClosed());
            } finally {
                deadline.cancel(false);
            }
        }
    }
}
