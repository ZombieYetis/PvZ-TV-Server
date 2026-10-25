package org.marshive;

import java.util.ArrayDeque;

/** Accounts conservatively for UTF-16 storage and per-entry overhead. */
final class SettlementQueue {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final long maxBytes;
    private final int maxEntries;
    private long bytes;

    SettlementQueue(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    synchronized boolean offer(String line) {
        long charge = charge(line);
        if (line.length() > ResourceLimits.MAX_SETTLEMENT_BYTES || lines.size() >= maxEntries
                || charge > maxBytes - bytes) return false;
        lines.addLast(line);
        bytes += charge;
        notifyAll();
        return true;
    }

    synchronized String poll(long timeoutMillis) throws InterruptedException {
        if (lines.isEmpty() && timeoutMillis > 0) wait(timeoutMillis);
        String line = lines.pollFirst();
        if (line != null) bytes -= charge(line);
        return line;
    }

    synchronized void clear() { lines.clear(); bytes = 0; }
    synchronized long bytes() { return bytes; }
    synchronized int size() { return lines.size(); }
    private static long charge(String line) { return 64L + 2L * line.length(); }
}
