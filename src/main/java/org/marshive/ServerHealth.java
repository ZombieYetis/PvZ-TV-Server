package org.marshive;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ServerHealth {
    private static volatile long lastLoopNanos;
    private static final ScheduledThreadPoolExecutor SOCKET_DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "socket-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    static {
        SOCKET_DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private ServerHealth() {}

    static void installFailureHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                System.err.println("[FATAL] uncaught failure in " + thread.getName());
                error.printStackTrace();
            } finally {
                // Do not wait for unrelated non-daemon threads or shutdown hooks after OOM.
                Runtime.getRuntime().halt(1);
            }
        });
    }

    static void startWatchdog() {
        int timeoutSeconds = Integer.getInteger("pvz.loopTimeoutSeconds", 90);
        if (timeoutSeconds < 5) throw new IllegalArgumentException("pvz.loopTimeoutSeconds must be >= 5");
        loopCompleted();
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.nanoTime() - lastLoopNanos > TimeUnit.SECONDS.toNanos(timeoutSeconds)) {
                    try {
                        System.err.println("[FATAL] game loop watchdog timeout");
                    } finally {
                        Runtime.getRuntime().halt(2);
                    }
                }
            }
        }, "game-loop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    static void loopCompleted() { lastLoopNanos = System.nanoTime(); }

    static ScheduledFuture<?> closeAfter(Socket socket, int timeoutMillis) {
        return SOCKET_DEADLINES.schedule(() -> {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    static void logMemory(int expiredIps) {
        Runtime runtime = Runtime.getRuntime();
        long mib = 1024 * 1024;
        System.out.println("[MEMORY] heapMiB=" + (runtime.totalMemory() - runtime.freeMemory()) / mib
                + "/" + runtime.maxMemory() / mib
                + " connections=" + AbuseGuard.connectionCount()
                + " trackedIps=" + AbuseGuard.trackedIpCount() + " expiredIps=" + expiredIps
                + " writeBytes=" + ClientHandler.queuedWriteBytes()
                + " writeBuffers=" + ClientHandler.queuedWriteBuffers()
                + " replicationBytes=" + SettlementReplicator.queuedBytes());
    }
}
