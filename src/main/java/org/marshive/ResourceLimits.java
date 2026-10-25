package org.marshive;

final class ResourceLimits {
    private static final long HEAP = Runtime.getRuntime().maxMemory();
    static final int MAX_CONNECTIONS = positiveInt("pvz.maxConnections",
            (int) Math.max(16, Math.min(1024, HEAP / (512 * 1024))));
    static final int MAX_TRACKED_IPS = positiveInt("pvz.maxTrackedIps", 32768);
    static final int MAX_QUEUED_BYTES = positiveInt("pvz.maxQueuedBytes",
            (int) Math.max(1024 * 1024, Math.min(64 * 1024 * 1024, HEAP / 8)));
    static final int MAX_QUEUED_BUFFERS = 65536;
    static final int MAX_SETTLEMENT_BYTES = 64 * 1024;
    static final int METRICS_TIMEOUT_MS = 10000;
    static final int MAX_REPLICATION_BYTES = positiveInt("pvz.maxReplicationBytes",
            (int) Math.max(256 * 1024, Math.min(16 * 1024 * 1024, HEAP / 32)));

    private ResourceLimits() {}

    private static int positiveInt(String name, int fallback) {
        String value = System.getProperty(name);
        int result = value == null ? fallback : Integer.parseInt(value);
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }
}
