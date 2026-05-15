package org.marshive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SettlementReplicator {
    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();

    private static String host = "";
    private static int port = 0;
    private static int batchSize = 10;
    private static boolean enabled = false;
    private static long lastRetryAt = 0L;

    private SettlementReplicator() {
    }

    static void configure(String targetHost, int targetPort, int batch) {
        synchronized (LOCK) {
            host = targetHost == null ? "" : targetHost.trim();
            port = targetPort;
            batchSize = Math.max(1, batch);
            enabled = !host.isEmpty() && port > 0;
            pending.clear();
            lastRetryAt = 0L;
        }
        if (enabled) {
            System.out.println("[REPL] enabled target=" + host + ":" + port + " batch=" + batchSize);
        }
    }

    static boolean isEnabled() {
        synchronized (LOCK) {
            return enabled;
        }
    }

    static void onAcceptedSettlementLine(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (LOCK) {
            if (!enabled) return;
            pending.add(line);
        }
        tryFlush(false);
    }

    static void onTick(long now) {
        boolean shouldRetry;
        synchronized (LOCK) {
            shouldRetry = enabled && !pending.isEmpty() && (now - lastRetryAt >= 5000L);
        }
        if (shouldRetry) {
            tryFlush(true);
        }
    }

    private static void tryFlush(boolean force) {
        List<String> batch;
        String h;
        int p;
        synchronized (LOCK) {
            if (!enabled) return;
            if (!force && pending.size() < batchSize) return;
            if (pending.isEmpty()) return;
            int n = force ? Math.min(pending.size(), batchSize) : batchSize;
            batch = new ArrayList<>(pending.subList(0, n));
            h = host;
            p = port;
        }

        int okCount = 0;
        for (String line : batch) {
            if (sendOne(h, p, line)) {
                okCount++;
            } else {
                break;
            }
        }

        synchronized (LOCK) {
            lastRetryAt = System.currentTimeMillis();
            if (okCount > 0) {
                pending.subList(0, okCount).clear();
                System.out.println("[REPL] pushed=" + okCount + " pending=" + pending.size());
            }
        }
    }

    private static boolean sendOne(String h, int p, String line) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(h, p), 2000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write(line.getBytes(StandardCharsets.UTF_8));
            if (!line.endsWith("\n")) out.write('\n');
            out.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String resp = r.readLine();
            return "OK".equals(resp) || "OK_DUP".equals(resp);
        } catch (Exception e) {
            System.out.println("[REPL] send failed: " + e.getMessage());
            return false;
        }
    }
}

