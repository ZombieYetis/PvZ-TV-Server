package org.marshive;

import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;

final class AnalyticsCollector {
    private static final int MAX_FINAL_PICKS_PER_SIDE = 8;
    private static final ConcurrentHashMap<Integer, CardStats> STATS = new ConcurrentHashMap<>();

    private AnalyticsCollector() {
    }

    static void onMatchStart(int roomId) {
        // deprecated: settlement is ingested from dedicated metrics port
    }

    static void onRoomClosed(int roomId) {
        // deprecated: settlement is ingested from dedicated metrics port
    }

    static void onRelayPayload(int roomId, byte[] raw) {
        // deprecated: settlement is ingested from dedicated metrics port
    }

    static String ingestSettlement(String line) {
        try {
            // New: SETTLE|settleId|roomId|winner|mainCounter|events|meta
            // Old compat: SETTLE|roomId|winner|mainCounter|events
            String[] parts = line.split("\\|", 7);
            if (parts.length < 5 || !"SETTLE".equals(parts[0])) return "ERR";
            final String settleId;
            final int roomId;
            final String winner;
            final int mainCounter;
            final String eventsRaw;
            final String metaRaw;
            if (parts.length >= 6) {
                settleId = parts[1].trim();
                roomId = Integer.parseInt(parts[2]);
                winner = parts[3].trim().toUpperCase(Locale.ROOT);
                mainCounter = Integer.parseInt(parts[4]);
                eventsRaw = parts[5].trim();
                metaRaw = parts.length >= 7 ? parts[6].trim() : "";
            } else {
                settleId = "legacy-" + System.currentTimeMillis() + "-" + Math.abs(line.hashCode());
                roomId = Integer.parseInt(parts[1]);
                winner = parts[2].trim().toUpperCase(Locale.ROOT);
                mainCounter = Integer.parseInt(parts[3]);
                eventsRaw = parts[4].trim();
                metaRaw = "";
            }
            boolean plantWin = "PLANT_WIN".equals(winner);
            boolean zombieWin = "ZOMBIE_WIN".equals(winner);
            if (!plantWin && !zombieWin) return "ERR";

            List<MatchEvent> events = new ArrayList<>();
            if (!eventsRaw.isEmpty()) {
                String[] items = eventsRaw.split(";");
                for (String item : items) {
                    String t = item == null ? "" : item.trim();
                    if (t.isEmpty()) continue;
                    String[] f = t.split(",", 4);
                    if (f.length < 4) continue;
                    int seq = Integer.parseInt(f[0].trim());
                    String eventType = normalizeEventType(f[2].trim());
                    int seedType = Integer.parseInt(f[3].trim());
                    String side = inferSide(seedType);
                    if (side == null) side = normalizeSide(f[1].trim());
                    if (side == null || eventType == null) continue;
                    events.add(new MatchEvent(seq, side, eventType, seedType));
                }
            }

            applyStatsFromSettlement(events, plantWin);
            Set<Integer> plantCards = collectFinalPicks(events, "PLANT");
            Set<Integer> zombieCards = collectFinalPicks(events, "ZOMBIE");
            SettlementMeta meta = parseMeta(metaRaw);
            MetricsStore.RecordResult dbResult = MetricsStore.recordMatch(settleId, roomId, plantWin, mainCounter, plantCards, zombieCards, events, meta);
            if (dbResult == MetricsStore.RecordResult.DUPLICATE) return "OK_DUP";
            if (dbResult == MetricsStore.RecordResult.FAILED) return "ERR";
            return "OK";
        } catch (Exception e) {
            System.out.println("[SETTLE] parse failed: " + e.getMessage());
            return "ERR";
        }
    }

    private static SettlementMeta parseMeta(String raw) {
        SettlementMeta m = new SettlementMeta();
        if (raw == null || raw.isEmpty()) return m;
        String[] items = raw.split("&");
        for (String kv : items) {
            if (kv == null || kv.isEmpty()) continue;
            int p = kv.indexOf('=');
            if (p <= 0) continue;
            String k = kv.substring(0, p).trim();
            String v = kv.substring(p + 1).trim();
            try {
                switch (k) {
                    case "bg":
                        m.background = Integer.parseInt(v);
                        break;
                    case "battle":
                        m.battleType = Integer.parseInt(v);
                        break;
                    case "shuffle":
                        m.shuffleMode = "1".equals(v) || "true".equalsIgnoreCase(v);
                        break;
                    case "addon_ep":
                        m.addonExtraPackets = "1".equals(v);
                        break;
                    case "addon_es":
                        m.addonExtraSeeds = "1".equals(v);
                        break;
                    case "addon_ban":
                        m.addonBanMode = "1".equals(v);
                        break;
                    case "addon_bp":
                        m.addonBalancePatch = "1".equals(v);
                        break;
                    case "mower_loss":
                        m.mowerLoss = Integer.parseInt(v);
                        break;
                    case "target_loss":
                        m.targetLoss = Integer.parseInt(v);
                        break;
                    case "grave_loss":
                        m.graveLoss = Integer.parseInt(v);
                        break;
                    case "sunflower_loss":
                        m.sunflowerLoss = Integer.parseInt(v);
                        break;
                    case "plant_use":
                        parseUsage(v, "PLANT", m.usages);
                        break;
                    case "zombie_use":
                        parseUsage(v, "ZOMBIE", m.usages);
                        break;
                    case "plant_name":
                        m.plantName = urlDecode(v);
                        break;
                    case "zombie_name":
                        m.zombieName = urlDecode(v);
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void parseUsage(String raw, String side, List<CardUsage> out) {
        if (raw == null || raw.isEmpty()) return;
        String[] pairs = raw.split(",");
        for (String pair : pairs) {
            int p = pair.indexOf(':');
            if (p <= 0) continue;
            int seed = Integer.parseInt(pair.substring(0, p).trim());
            int count = Integer.parseInt(pair.substring(p + 1).trim());
            if (count > 0) out.add(new CardUsage(side, seed, count));
        }
    }

    private static String urlDecode(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        byte[] buf = new byte[raw.length()];
        int w = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf[w++] = (byte) ((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            buf[w++] = (byte) c;
        }
        return new String(buf, 0, w, StandardCharsets.UTF_8);
    }

    private static String normalizeSide(String raw) {
        String v = raw.toUpperCase(Locale.ROOT);
        if ("Z".equals(v) || "ZOMBIE".equals(v)) return "ZOMBIE";
        if ("P".equals(v) || "PLANT".equals(v)) return "PLANT";
        return null;
    }

    private static String inferSide(int seedType) {
        if (seedType >= 61) return "ZOMBIE";
        if (seedType >= 0) return "PLANT";
        return null;
    }

    private static String normalizeEventType(String raw) {
        String v = raw.toUpperCase(Locale.ROOT);
        if ("B".equals(v) || "BAN".equals(v)) return "BAN";
        if ("K".equals(v) || "PICK".equals(v)) return "PICK";
        return null;
    }

    private static Set<Integer> collectFinalPicks(List<MatchEvent> events, String side) {
        Set<Integer> out = new LinkedHashSet<>();
        for (MatchEvent e : events) {
            if (!side.equals(e.side)) continue;
            if (!"PICK".equals(e.eventType)) continue;
            if (out.size() < MAX_FINAL_PICKS_PER_SIDE) out.add(e.seedType);
        }
        return out;
    }

    private static void applyStatsFromSettlement(List<MatchEvent> events, boolean plantWin) {
        Set<Integer> plantPicks = new LinkedHashSet<>();
        Set<Integer> zombiePicks = new LinkedHashSet<>();
        for (MatchEvent e : events) {
            if ("BAN".equals(e.eventType)) {
                stats(e.seedType).disabled.incrementAndGet();
            } else if ("PICK".equals(e.eventType)) {
                if ("PLANT".equals(e.side) && plantPicks.size() < MAX_FINAL_PICKS_PER_SIDE) plantPicks.add(e.seedType);
                if ("ZOMBIE".equals(e.side) && zombiePicks.size() < MAX_FINAL_PICKS_PER_SIDE)
                    zombiePicks.add(e.seedType);
            }
        }
        for (int seed : plantPicks) {
            CardStats s = stats(seed);
            s.picked.incrementAndGet();
            if (plantWin) s.won.incrementAndGet();
        }
        for (int seed : zombiePicks) {
            CardStats s = stats(seed);
            s.picked.incrementAndGet();
            if (!plantWin) s.won.incrementAndGet();
        }
    }

    private static CardStats stats(int seedType) {
        return STATS.computeIfAbsent(seedType, k -> new CardStats());
    }

    static String snapshotSummary() {
        List<CardRow> rows = new ArrayList<>();
        long totalPick = 0;
        long totalBan = 0;
        long totalWin = 0;

        for (Map.Entry<Integer, CardStats> e : STATS.entrySet()) {
            int seed = e.getKey();
            CardStats s = e.getValue();
            long picked = s.picked.get();
            long banned = s.disabled.get();
            long won = s.won.get();
            if (picked == 0 && banned == 0 && won == 0) continue;
            rows.add(new CardRow(seed, picked, banned, won));
            totalPick += picked;
            totalBan += banned;
            totalWin += won;
        }

        rows.sort(Comparator
                .comparingLong(CardRow::picked).reversed()
                .thenComparingInt(CardRow::seed));

        StringBuilder sb = new StringBuilder();
        sb.append("[CARD_METRICS] cards=").append(rows.size())
                .append(" total_pick=").append(totalPick)
                .append(" total_ban=").append(totalBan)
                .append(" total_win=").append(totalWin);

        for (CardRow r : rows) {
            double pickRate = totalPick <= 0 ? 0.0 : (double) r.picked() / (double) totalPick;
            double banRate = totalBan <= 0 ? 0.0 : (double) r.banned() / (double) totalBan;
            double targetRate = r.picked() <= 0 ? 0.0 : (double) r.banned() / (double) r.picked();
            double winRate = r.picked() <= 0 ? 0.0 : (double) r.won() / (double) r.picked();
            sb.append('\n')
                    .append("[CARD] seed=").append(r.seed())
                    .append("(").append(SeedTypeNames.nameOf(r.seed())).append(")")
                    .append(" pick=").append(r.picked())
                    .append(" ban=").append(r.banned())
                    .append(" win=").append(r.won())
                    .append(" pick_rate=").append(formatRate(pickRate))
                    .append(" ban_rate=").append(formatRate(banRate))
                    .append(" target_rate=").append(formatRate(targetRate))
                    .append(" win_rate=").append(formatRate(winRate));
        }
        return sb.toString();
    }

    static String snapshotSummaryAligned(String resultTag) {
        List<CardRow> rows = new ArrayList<>();
        long totalPick = 0;
        long totalBan = 0;
        long totalWin = 0;

        for (Map.Entry<Integer, CardStats> e : STATS.entrySet()) {
            int seed = e.getKey();
            CardStats s = e.getValue();
            long picked = s.picked.get();
            long banned = s.disabled.get();
            long won = s.won.get();
            if (picked == 0 && banned == 0 && won == 0) continue;
            rows.add(new CardRow(seed, picked, banned, won));
            totalPick += picked;
            totalBan += banned;
            totalWin += won;
        }

        rows.sort(Comparator
                .comparingLong(CardRow::picked).reversed()
                .thenComparingInt(CardRow::seed));

        StringBuilder sb = new StringBuilder();
        sb.append("[CARD_METRICS][").append(resultTag).append("] cards=").append(rows.size())
                .append(" total_pick=").append(totalPick)
                .append(" total_ban=").append(totalBan)
                .append(" total_win=").append(totalWin)
                .append('\n');
        sb.append(String.format(Locale.US, "%-5s %-18s %7s %7s %7s %10s %10s %12s %10s%n",
                "Seed", "Name", "Pick", "Ban", "Win", "PickRate", "BanRate", "TargetRate", "WinRate"));
        sb.append("-----------------------------------------------------------------------------------------------");

        for (CardRow r : rows) {
            double pickRate = totalPick <= 0 ? 0.0 : (double) r.picked() / (double) totalPick;
            double banRate = totalBan <= 0 ? 0.0 : (double) r.banned() / (double) totalBan;
            double targetRate = r.picked() <= 0 ? 0.0 : (double) r.banned() / (double) r.picked();
            double winRate = r.picked() <= 0 ? 0.0 : (double) r.won() / (double) r.picked();
            sb.append('\n').append(String.format(Locale.US, "%-5d %-18s %7d %7d %7d %9.2f%% %9.2f%% %11.2f%% %9.2f%%",
                    r.seed(),
                    clipName(SeedTypeNames.nameOf(r.seed()), 18),
                    r.picked(),
                    r.banned(),
                    r.won(),
                    pickRate * 100.0,
                    banRate * 100.0,
                    targetRate * 100.0,
                    winRate * 100.0));
        }
        return sb.toString();
    }

    private static String clipName(String name, int maxLen) {
        if (name == null) return "";
        if (name.length() <= maxLen) return name;
        if (maxLen <= 1) return name.substring(0, maxLen);
        return name.substring(0, maxLen - 1) + "~";
    }

    private static String formatRate(double v) {
        return String.format(Locale.US, "%.2f%%", v * 100.0);
    }

    record MatchEvent(int seq, String side, String eventType, int seedType) {
    }

    record CardUsage(String side, int seedType, int useCount) {
    }

    static final class SettlementMeta {
        int background = -1;
        int battleType = -1;
        boolean shuffleMode;
        boolean addonExtraPackets;
        boolean addonExtraSeeds;
        boolean addonBanMode;
        boolean addonBalancePatch;
        int mowerLoss;
        int targetLoss;
        int graveLoss;
        int sunflowerLoss;
        String plantName = "";
        String zombieName = "";
        final List<CardUsage> usages = new ArrayList<>();
    }

    private static final class CardStats {
        private final AtomicLong picked = new AtomicLong();
        private final AtomicLong disabled = new AtomicLong();
        private final AtomicLong won = new AtomicLong();
    }

    private record CardRow(int seed, long picked, long banned, long won) {
    }
}
