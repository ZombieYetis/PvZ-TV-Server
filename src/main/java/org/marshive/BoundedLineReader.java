package org.marshive;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class BoundedLineReader {
    private BoundedLineReader() {}

    /** One request per socket; both a byte limit and a total read deadline apply. */
    static String read(Socket socket, int maxBytes, int timeoutMillis) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] line = new byte[maxBytes];
        byte[] chunk = new byte[Math.min(4096, maxBytes + 1)];
        int length = 0;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Line read deadline exceeded");
            socket.setSoTimeout((int) Math.max(1, (remaining + 999999L) / 1000000L));
            int count = input.read(chunk);
            if (count < 0) return length == 0 ? null : decode(line, length);
            for (int i = 0; i < count; i++) {
                if (chunk[i] == '\n') return decode(line, length);
                if (length == maxBytes) throw new IOException("Line exceeds " + maxBytes + " bytes");
                line[length++] = chunk[i];
            }
        }
    }

    private static String decode(byte[] bytes, int length) {
        if (length > 0 && bytes[length - 1] == '\r') length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
