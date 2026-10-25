package org.marshive;

/** Counts retained allocations, including partially written buffers. */
final class BufferBudget {
    private final long maxBytes;
    private final int maxBuffers;
    private long bytes;
    private int buffers;

    BufferBudget(long maxBytes, int maxBuffers) {
        this.maxBytes = maxBytes;
        this.maxBuffers = maxBuffers;
    }

    synchronized boolean reserve(int size) {
        if (size < 0 || size > maxBytes - bytes || buffers >= maxBuffers) return false;
        bytes += size;
        buffers++;
        return true;
    }

    synchronized void release(int size) {
        if (size < 0 || size > bytes || buffers == 0) {
            throw new IllegalStateException("Unbalanced buffer reservation");
        }
        bytes -= size;
        buffers--;
    }

    synchronized long bytes() { return bytes; }
    synchronized int buffers() { return buffers; }
}
