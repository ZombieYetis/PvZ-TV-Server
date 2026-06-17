package org.marshive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class AbuseGuard {
    private static final int MAX_CONNECTIONS_PER_IP = 24;
    private static final int MAX_CREATES_PER_10S = 3;
    private static final int MAX_CREATES_PER_MINUTE = 8;
    private static final long CREATE_SHORT_WINDOW_MS = 10_000L;
    private static final long CREATE_LONG_WINDOW_MS = 60_000L;
    private static final long CREATE_BLOCK_MS = 60_000L;

    private static final ConcurrentHashMap<String, IpState> IP_STATES = new ConcurrentHashMap<>();

    private AbuseGuard() {
    }

    static boolean tryOpenConnection(String ip) {
        IpState state = stateFor(ip);
        int count = state.connections.incrementAndGet();
        if (count <= MAX_CONNECTIONS_PER_IP) {
            return true;
        }
        state.connections.decrementAndGet();
        System.out.println("[ABUSE] reject connection ip=" + ip + " connections=" + count);
        return false;
    }

    static void closeConnection(String ip) {
        IpState state = IP_STATES.get(normalizeIp(ip));
        if (state == null) return;
        state.connections.updateAndGet(v -> Math.max(0, v - 1));
    }

    static boolean allowCreate(String ip, long nowMillis) {
        IpState state = stateFor(ip);
        synchronized (state) {
            if (state.createBlockedUntilMillis > nowMillis) {
                return false;
            }

            pruneOlderThan(state.creates, nowMillis - CREATE_LONG_WINDOW_MS);
            int createsInMinute = state.creates.size();
            int createsInShortWindow = 0;
            long shortCutoff = nowMillis - CREATE_SHORT_WINDOW_MS;
            for (Long t : state.creates) {
                if (t >= shortCutoff) createsInShortWindow++;
            }

            if (createsInShortWindow >= MAX_CREATES_PER_10S || createsInMinute >= MAX_CREATES_PER_MINUTE) {
                state.createBlockedUntilMillis = nowMillis + CREATE_BLOCK_MS;
                System.out.println("[ABUSE] create rate limited ip=" + normalizeIp(ip)
                        + " short=" + createsInShortWindow
                        + " minute=" + createsInMinute);
                return false;
            }

            state.creates.addLast(nowMillis);
            return true;
        }
    }

    private static IpState stateFor(String ip) {
        return IP_STATES.computeIfAbsent(normalizeIp(ip), ignored -> new IpState());
    }

    private static String normalizeIp(String ip) {
        return (ip == null || ip.isBlank()) ? "unknown" : ip;
    }

    private static void pruneOlderThan(Deque<Long> deque, long cutoffMillis) {
        while (!deque.isEmpty() && deque.peekFirst() < cutoffMillis) {
            deque.removeFirst();
        }
    }

    private static final class IpState {
        final AtomicInteger connections = new AtomicInteger();
        final Deque<Long> creates = new ArrayDeque<>();
        long createBlockedUntilMillis = 0L;
    }
}
