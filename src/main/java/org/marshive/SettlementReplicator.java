package org.marshive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class SettlementReplicator {
    private static final Object LOCK = new Object();
    private static final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<>(50_000);

    private static String host = "";
    private static int port = 0;
    private static int batchSize = 10;
    private static boolean enabled = false;
    private static volatile Thread worker;
    private static volatile String retryLine;
    private static volatile long retryAfterAt = 0L;

    private SettlementReplicator() {
    }

    static void configure(String targetHost, int targetPort, int batch) {
        synchronized (LOCK) {
            host = targetHost == null ? "" : targetHost.trim();
            port = targetPort;
            batchSize = Math.max(1, batch);
            enabled = !host.isEmpty() && port > 0;
            pending.clear();
            retryLine = null;
            retryAfterAt = 0L;
        }
        if (enabled) {
            System.out.println("[REPL] enabled target=" + host + ":" + port + " batch=" + batchSize + " mode=async");
            ensureWorkerStarted();
        }
    }

    static boolean isEnabled() {
        synchronized (LOCK) {
            return enabled;
        }
    }

    static void onAcceptedSettlementLine(String line) {
        if (line == null || line.isEmpty()) return;
        boolean ok;
        synchronized (LOCK) {
            if (!enabled) return;
            ok = pending.offer(line);
        }
        if (!ok) {
            System.out.println("[REPL] queue full, drop one settlement");
            return;
        }
        ensureWorkerStarted();
    }

    static void onTick(long now) {
        // kept for compatibility; replication is now fully asynchronous in worker thread
    }

    private static void ensureWorkerStarted() {
        synchronized (LOCK) {
            if (!enabled) return;
            if (worker != null && worker.isAlive()) return;
            worker = new Thread(SettlementReplicator::runWorker, "settlement-repl");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void runWorker() {
        while (true) {
            String line = null;
            String h;
            int p;
            int drainBurst;
            synchronized (LOCK) {
                if (!enabled) {
                    sleepQuiet(500L);
                    continue;
                }
                h = host;
                p = port;
                drainBurst = batchSize;
            }

            try {
                long now = System.currentTimeMillis();
                String pendingRetry = retryLine;
                if (pendingRetry != null) {
                    if (now < retryAfterAt) {
                        sleepQuiet(Math.min(500L, retryAfterAt - now));
                        continue;
                    }
                    line = pendingRetry;
                } else {
                    line = pending.poll(500, TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                }
            } catch (InterruptedException ignored) {
                continue;
            }

            if (sendOne(h, p, line)) {
                retryLine = null;
                retryAfterAt = 0L;
                int drained = 1;
                while (drained < drainBurst) {
                    String next = pending.poll();
                    if (next == null) break;
                    if (!sendOne(h, p, next)) {
                        retryLine = next;
                        retryAfterAt = System.currentTimeMillis() + 5000L;
                        break;
                    }
                    drained++;
                }
                if (drained > 0) {
                    System.out.println("[REPL] pushed=" + drained + " pending=" + pending.size());
                }
            } else {
                retryLine = line;
                retryAfterAt = System.currentTimeMillis() + 5000L;
            }
        }
    }

    private static boolean sendOne(String h, int p, String line) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(h, p), 2000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
