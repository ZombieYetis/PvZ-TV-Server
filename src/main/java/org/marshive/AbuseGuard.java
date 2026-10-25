package org.marshive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

final class AbuseGuard {
    private static final int MAX_CONNECTIONS_PER_IP = 24;
    private static final int MAX_CREATES_PER_10S = 3;
    private static final int MAX_CREATES_PER_MINUTE = 8;
    private static final long CREATE_SHORT_WINDOW_MS = 10000L;
    private static final long CREATE_LONG_WINDOW_MS = 60000L;
    private static final long CREATE_BLOCK_MS = 60000L;
    static final long IP_IDLE_TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, IpState> IP_STATES = new HashMap<>();
    private static int connections;

    private AbuseGuard() {}

    // Admission, close and eviction share a lock so eviction cannot lose live counts.
    static synchronized boolean tryOpenConnection(String ip) {
        if (connections >= ResourceLimits.MAX_CONNECTIONS) return false;
        IpState state = stateFor(ip, System.currentTimeMillis());
        if (state == null || state.connections >= MAX_CONNECTIONS_PER_IP) return false;
        state.connections++;
        connections++;
        return true;
    }

    static synchronized void closeConnection(String ip) {
        IpState state = IP_STATES.get(normalizeIp(ip));
        if (state == null || state.connections == 0) return;
        state.connections--;
        connections--;
        state.lastSeenMillis = System.currentTimeMillis();
    }

    static synchronized boolean allowCreate(String ip, long nowMillis) {
        IpState state = stateFor(ip, nowMillis);
        if (state == null || state.createBlockedUntilMillis > nowMillis) return false;
        pruneOlderThan(state.creates, nowMillis - CREATE_LONG_WINDOW_MS);
        int shortCount = 0;
        for (Long t : state.creates) if (t >= nowMillis - CREATE_SHORT_WINDOW_MS) shortCount++;
        if (shortCount >= MAX_CREATES_PER_10S || state.creates.size() >= MAX_CREATES_PER_MINUTE) {
            state.createBlockedUntilMillis = nowMillis + CREATE_BLOCK_MS;
            return false;
        }
        state.creates.addLast(nowMillis);
        return true;
    }

    static synchronized int cleanup(long nowMillis) {
        int before = IP_STATES.size();
        IP_STATES.values().removeIf(state -> {
            pruneOlderThan(state.creates, nowMillis - CREATE_LONG_WINDOW_MS);
            return state.connections == 0 && state.creates.isEmpty()
                    && state.createBlockedUntilMillis <= nowMillis
                    && nowMillis - state.lastSeenMillis >= IP_IDLE_TTL_MS;
        });
        return before - IP_STATES.size();
    }

    static synchronized int connectionCount() { return connections; }
    static synchronized int trackedIpCount() { return IP_STATES.size(); }

    private static IpState stateFor(String ip, long nowMillis) {
        String key = normalizeIp(ip);
        IpState state = IP_STATES.get(key);
        if (state == null) {
            if (IP_STATES.size() >= ResourceLimits.MAX_TRACKED_IPS) return null;
            state = new IpState();
            IP_STATES.put(key, state);
        }
        state.lastSeenMillis = nowMillis;
        return state;
    }

    private static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private static void pruneOlderThan(Deque<Long> deque, long cutoffMillis) {
        while (!deque.isEmpty() && deque.peekFirst() < cutoffMillis) deque.removeFirst();
    }

    private static final class IpState {
        int connections;
        final Deque<Long> creates = new ArrayDeque<>();
        long createBlockedUntilMillis;
        long lastSeenMillis;
    }
}
