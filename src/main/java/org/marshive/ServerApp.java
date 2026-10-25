package org.marshive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

public class ServerApp {
    private static final int DEFAULT_BASE_PORT = 8888;
    private static final int DEFAULT_SHARD_COUNT = 4;
    private static final int PROBE2_PORT_OFFSET = 1004;
    private static final long STALE_ROOM_TTL_MS = 20L * 60L * 1000L;
    private static final long ROOM_CLEAN_INTERVAL_MS = 60_000L;

    public static void main(String[] args) throws Exception {
        ServerHealth.installFailureHandler();
        int basePort = DEFAULT_BASE_PORT;
        int shardCount = DEFAULT_SHARD_COUNT;
        boolean dashboardEnabled = false;
        String replicateHost = "";
        int replicatePort = 0;
        int replicateBatch = 10;

        for (String a : args) {
            if (a == null) continue;
            a = a.trim();
            if (a.startsWith("--base=")) {
                basePort = parseIntSafe(a.substring("--base=".length()), basePort);
            } else if (a.startsWith("--shards=")) {
                shardCount = parseIntSafe(a.substring("--shards=".length()), shardCount);
            } else if (a.startsWith("--dashboard=")) {
                dashboardEnabled = parseBoolSafe(a.substring("--dashboard=".length()), false);
            } else if (a.startsWith("--replicate_to=")) {
                String target = a.substring("--replicate_to=".length()).trim();
                int p = target.lastIndexOf(':');
                if (p > 0 && p + 1 < target.length()) {
                    replicateHost = target.substring(0, p).trim();
                    replicatePort = parseIntSafe(target.substring(p + 1).trim(), 0);
                }
            } else if (a.startsWith("--replicate_batch=")) {
                replicateBatch = parseIntSafe(a.substring("--replicate_batch=".length()), replicateBatch);
            }
        }
        if (args.length >= 1 && args[0] != null && !args[0].trim().startsWith("--")) {
            basePort = parseIntSafe(args[0].trim(), basePort);
        }
        if (args.length >= 2 && args[1] != null && !args[1].trim().startsWith("--")) {
            shardCount = parseIntSafe(args[1].trim(), shardCount);
        }

        if (shardCount <= 0 || shardCount > 4) {
            throw new IllegalArgumentException("SHARD_COUNT must be 1..4 (probe port ranges), got: " + shardCount);
        }
        if (basePort <= 0 || basePort > 65535) {
            throw new IllegalArgumentException("BASE_PORT must be 1..65535, got: " + basePort);
        }
        int lastPort = basePort + shardCount - 1;
        if (lastPort > 65535) {
            throw new IllegalArgumentException("Port range overflow: " + lastPort);
        }
        int probeBasePort = basePort + 1000;
        int lastProbePort = probeBasePort + shardCount - 1;
        if (probeBasePort <= 0 || lastProbePort > 65535) {
            throw new IllegalArgumentException("Probe port range overflow: " + lastProbePort);
        }
        int metricsBasePort = basePort + 3000;
        int lastMetricsPort = metricsBasePort + shardCount - 1;
        if (metricsBasePort <= 0 || lastMetricsPort > 65535) {
            throw new IllegalArgumentException("Metrics port range overflow: " + lastMetricsPort);
        }
        int dashboardPort = basePort + 4000;
        if (dashboardPort <= 0 || dashboardPort > 65535) {
            throw new IllegalArgumentException("Dashboard port overflow: " + dashboardPort);
        }
        int probeBasePort2 = basePort + PROBE2_PORT_OFFSET;
        int lastProbePort2 = probeBasePort2 + shardCount - 1;
        if (probeBasePort2 <= 0 || lastProbePort2 > 65535) {
            throw new IllegalArgumentException("Probe2 port range overflow: " + lastProbePort2);
        }

        System.out.println(">>> NIO Game Server Started on Ports: " +
                basePort + " ~ " + lastPort +
                " (base=" + basePort + ", shards=" + shardCount + ", probeBase1=" + probeBasePort + ", probeBase2=" + probeBasePort2 + ", metricsPorts=" + metricsBasePort + "~" + lastMetricsPort + ", dashboardPort=" + dashboardPort + ")");

        Selector selector = Selector.open();
        RoomManager[] rms = new RoomManager[shardCount];
        Map<ServerSocketChannel, ShardBinding> listeners = new HashMap<>();

        for (int i = 0; i < shardCount; i++) {
            rms[i] = new RoomManager(i);
            int port = basePort + i;
            int probePort = probeBasePort + i;
            int probePort2 = probeBasePort2 + i;

            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(port));
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            listeners.put(ssc, new ShardBinding(i, probePort, probePort2));

            startProbeThread(probePort, 1);
            startProbeThread(probePort2, 2);
        }
        SettlementReplicator.configure(replicateHost, replicatePort, replicateBatch);
        for (int i = 0; i < shardCount; i++) {
            int metricsPort = metricsBasePort + i;
            startMetricsThread(metricsPort);
        }
        if (dashboardEnabled) {
            DashboardServer.start(dashboardPort);
        } else {
            System.out.println("[DASHBOARD] disabled");
        }

        long lastRoomCleanAt = System.currentTimeMillis();
        ServerHealth.startWatchdog();
        System.out.println("[LIMITS] connections=" + ResourceLimits.MAX_CONNECTIONS
                + " trackedIps=" + ResourceLimits.MAX_TRACKED_IPS
                + " writeBytes=" + ResourceLimits.MAX_QUEUED_BYTES
                + " settlementBytes=" + ResourceLimits.MAX_SETTLEMENT_BYTES
                + " replicationBytes=" + ResourceLimits.MAX_REPLICATION_BYTES);
        while (true) {
            selector.select(500);
            long now = System.currentTimeMillis();

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;
                try {
                    if (key.isAcceptable()) {
                        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                        ShardBinding binding = listeners.get(ssc);
                        if (binding == null) continue;
                        SocketChannel ch = ssc.accept();
                        if (ch == null) continue;
                        String remoteIp = remoteIpOf(ch);
                        if (!AbuseGuard.tryOpenConnection(remoteIp)) {
                            ch.close();
                            continue;
                        }
                        ClientHandler h = null;
                        try {
                            h = new ClientHandler(ch, rms[binding.shardId], binding.shardId, binding.probePort, binding.probePort2, remoteIp);
                            SelectionKey clientKey = ch.register(selector, SelectionKey.OP_READ);
                            h.key = clientKey;
                            clientKey.attach(h);
                        } catch (IOException e) {
                            if (h != null) h.close();
                            else {
                                AbuseGuard.closeConnection(remoteIp);
                                ch.close();
                            }
                            continue;
                        }
                    } else {
                        ClientHandler h = (ClientHandler) key.attachment();
                        if (h == null) continue;
                        if (key.isReadable()) h.onReadable();
                        if (key.isValid() && key.isWritable()) h.onWritable();
                    }
                } catch (IOException e) {
                    Object att = key.attachment();
                    if (att instanceof ClientHandler) {
                        ((ClientHandler) att).close();
                    } else {
                        key.cancel();
                    }
                }
            }

            for (SelectionKey key : selector.keys()) {
                Object att = key.attachment();
                if (!(att instanceof ClientHandler h)) continue;
                if (h.isClosed()) continue;
                try {
                    h.onTick(now);
                } catch (IOException e) {
                    h.close();
                }
            }
            SettlementReplicator.onTick(now);

            if (now - lastRoomCleanAt >= ROOM_CLEAN_INTERVAL_MS) {
                int removedTotal = 0;
                for (RoomManager rm : rms) {
                    removedTotal += rm.removeStaleNotGamingRooms(now, STALE_ROOM_TTL_MS);
                }
                if (removedTotal > 0) {
                    System.out.println("[ROOM_CLEAN] removed stale rooms: " + removedTotal);
                }
                ServerHealth.logMemory(AbuseGuard.cleanup(now));
                lastRoomCleanAt = now;
            }
            ServerHealth.loopCompleted();
        }
    }

    private static void startProbeThread(int probePort, int probeIndex) {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(probePort)) {
                while (true) {
                    Socket s = ss.accept();
                    try (Socket probeSocket = s) {
                        s.setTcpNoDelay(true);
                        s.setSoTimeout(1000);
                        InputStream in = probeSocket.getInputStream();
                        int token = Proto.readIntBE(in);
                        ClientHandler.acceptP2PProbe(token, probeSocket, probeIndex);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Probe listener failed on " + probePort, e);
            }
        }, (probeIndex == 1 ? "probe-" : "probe2-") + probePort);
        t.setDaemon(false);
        t.start();
    }

    private static String remoteIpOf(SocketChannel ch) {
        try {
            if (ch.getRemoteAddress() instanceof InetSocketAddress a && a.getAddress() != null) {
                return a.getAddress().getHostAddress();
            }
        } catch (IOException ignored) {
        }
        return "unknown";
    }

    private static void startMetricsThread(int metricsPort) {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(metricsPort)) {
                while (true) {
                    Socket s = ss.accept();
                    ScheduledFuture<?> deadline = ServerHealth.closeAfter(s, ResourceLimits.METRICS_TIMEOUT_MS);
                    try (Socket metricsSocket = s) {
                        s.setTcpNoDelay(true);
                        OutputStream out = metricsSocket.getOutputStream();
                        String req = BoundedLineReader.read(metricsSocket, ResourceLimits.MAX_SETTLEMENT_BYTES,
                                ResourceLimits.METRICS_TIMEOUT_MS);
                        if (req == null) continue;
                        String resp;
                        if (req != null && req.startsWith("SETTLE|")) {
                            String ret = AnalyticsCollector.ingestSettlement(req);
                            resp = ret + "\n";
                            if (SettlementReplicator.isEnabled() && ("OK".equals(ret) || "OK_DUP".equals(ret))) {
                                SettlementReplicator.onAcceptedSettlementLine(req);
                            }
                        } else {
                            resp = AnalyticsCollector.snapshotSummary() + "\n";
                        }
                        byte[] payload = resp.getBytes(StandardCharsets.UTF_8);
                        out.write(payload);
                        out.flush();
                    } catch (IOException ignored) {
                    } finally {
                        deadline.cancel(false);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Metrics listener failed on " + metricsPort, e);
            }
        }, "metrics-" + metricsPort);
        t.setDaemon(false);
        t.start();
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolSafe(String s, boolean fallback) {
        if (s == null) return fallback;
        String v = s.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) return true;
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) return false;
        return fallback;
    }

    private record ShardBinding(int shardId, int probePort, int probePort2) {
    }
}
