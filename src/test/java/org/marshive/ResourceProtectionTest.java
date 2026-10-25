package org.marshive;

import org.junit.After;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ResourceProtectionTest {
    @After public void cleanup() {
        assertEquals(0, AbuseGuard.connectionCount());
        AbuseGuard.cleanup(Long.MAX_VALUE);
    }

    @Test public void globalAndPerIpAdmissionReleaseTheirSlots() {
        List<String> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                assertTrue(AbuseGuard.tryOpenConnection("same"));
                accepted.add("same");
            }
            assertFalse(AbuseGuard.tryOpenConnection("same"));
            for (int i = 24; i < ResourceLimits.MAX_CONNECTIONS; i++) {
                String ip = "ip-" + i;
                assertTrue(AbuseGuard.tryOpenConnection(ip));
                accepted.add(ip);
            }
            assertFalse(AbuseGuard.tryOpenConnection("over-global-limit"));
            assertEquals(ResourceLimits.MAX_CONNECTIONS, AbuseGuard.connectionCount());
            AbuseGuard.closeConnection(accepted.remove(accepted.size() - 1));
            assertTrue(AbuseGuard.tryOpenConnection("replacement"));
            accepted.add("replacement");
        } finally {
            accepted.forEach(AbuseGuard::closeConnection);
        }
    }

    @Test public void cleanupPreservesLiveConnectionsAndRateLimits() {
        long now = System.currentTimeMillis();
        assertTrue(AbuseGuard.tryOpenConnection("live"));
        try {
            assertTrue(AbuseGuard.allowCreate("idle", now));
            assertTrue(AbuseGuard.allowCreate("idle", now));
            assertTrue(AbuseGuard.allowCreate("idle", now));
            assertFalse(AbuseGuard.allowCreate("idle", now));
            AbuseGuard.cleanup(now + 1000);
            assertFalse(AbuseGuard.allowCreate("idle", now + 1000));
            assertEquals(1, AbuseGuard.cleanup(now + AbuseGuard.IP_IDLE_TTL_MS + 2000));
            assertEquals(1, AbuseGuard.trackedIpCount());
            assertEquals(1, AbuseGuard.connectionCount());
        } finally {
            AbuseGuard.closeConnection("live");
        }
    }

    @Test public void historicalIpTableIsBoundedAndCanRecover() {
        for (int i = 0; i < ResourceLimits.MAX_TRACKED_IPS; i++) {
            String ip = "history-" + i;
            assertTrue(AbuseGuard.tryOpenConnection(ip));
            AbuseGuard.closeConnection(ip);
        }
        assertFalse(AbuseGuard.tryOpenConnection("over-table-limit"));
        assertEquals(ResourceLimits.MAX_TRACKED_IPS, AbuseGuard.cleanup(Long.MAX_VALUE));
        assertTrue(AbuseGuard.tryOpenConnection("after-cleanup"));
        AbuseGuard.closeConnection("after-cleanup");
    }

    @Test public void concurrentCleanupDoesNotLoseConnectionAccounting() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                String ip = "concurrent-" + t;
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 2000; i++) {
                        assertTrue(AbuseGuard.tryOpenConnection(ip));
                        AbuseGuard.closeConnection(ip);
                    }
                }));
            }
            tasks.add(workers.submit(() -> {
                for (int i = 0; i < 2000; i++) AbuseGuard.cleanup(Long.MAX_VALUE);
            }));
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void bufferBudgetBoundsBothBytesAndObjectCount() {
        BufferBudget budget = new BufferBudget(10, 2);
        assertTrue(budget.reserve(6));
        assertFalse(budget.reserve(5));
        assertTrue(budget.reserve(1));
        assertFalse(budget.reserve(1));
        budget.release(6);
        assertTrue(budget.reserve(9));
        budget.release(1);
        budget.release(9);
        assertEquals(0, budget.bytes());
        assertEquals(0, budget.buffers());
    }

    @Test public void replicationQueueBoundsBytesCountAndSingleLine() throws Exception {
        SettlementQueue queue = new SettlementQueue(150, 2);
        assertTrue(queue.offer("中文"));
        assertTrue(queue.offer("test"));
        assertFalse(queue.offer(""));
        assertEquals(140, queue.bytes());
        assertEquals("中文", queue.poll(0));
        assertFalse(queue.offer("a".repeat(20)));
        assertEquals("test", queue.poll(0));
        assertTrue(queue.offer("a".repeat(20)));
        queue.clear();
        assertEquals(0, queue.bytes());
        assertFalse(queue.offer("a".repeat(ResourceLimits.MAX_SETTLEMENT_BYTES + 1)));
    }

    @Test public void oversizedSettlementAndEventListAreRejectedBeforeDatabaseWork() {
        assertEquals("ERR", AnalyticsCollector.ingestSettlement("x".repeat(ResourceLimits.MAX_SETTLEMENT_BYTES + 1)));
        assertEquals("ERR", AnalyticsCollector.ingestSettlement("SETTLE|test|1|PLANT_WIN|1|"
                + "1,P,K,0;".repeat(513) + "|"));
    }
}
