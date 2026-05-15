package org.marshive;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

final class MetricsStore {
    private static final String DB_DIR = "data";
    private static final String DB_PATH = DB_DIR + File.separator + "pvz_metrics.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    private static Connection conn;
    private static boolean initialized = false;

    private MetricsStore() {
    }

    enum RecordResult {
        OK,
        DUPLICATE,
        FAILED
    }

    static RecordResult recordMatch(String settleId, int roomId, boolean plantWin, int mainCounter, Set<Integer> plantCards, Set<Integer> zombieCards, List<AnalyticsCollector.MatchEvent> events, AnalyticsCollector.SettlementMeta meta) {
        synchronized (MetricsStore.class) {
            if (!ensureInit()) return RecordResult.FAILED;
            try {
                conn.setAutoCommit(false);
                long matchId = insertMatchResult(settleId, roomId, plantWin ? "PLANT" : "ZOMBIE", mainCounter, meta);
                insertMatchEvents(matchId, events);
                insertCardUsage(matchId, meta == null ? null : meta.usages);
                upsertCardStatsFromEvents(events, plantWin);
                conn.commit();
                return RecordResult.OK;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                if (isDuplicateSettleId(e)) {
                    return RecordResult.DUPLICATE;
                }
                System.out.println("[METRICS_DB] recordMatch failed: " + e.getMessage());
                return RecordResult.FAILED;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private static long insertMatchResult(String settleId, int roomId, String winner, int mainCounter, AnalyticsCollector.SettlementMeta meta) throws SQLException {
        int frames = Math.max(mainCounter, 0);
        String sql = "INSERT INTO match_results(settle_id, room_id, winner, duration_text, bg, battle_type, game_mode, extra_packet, extended_seeds, ban_mode, balance_patch, mower_loss, target_loss, sunflower_loss, grave_loss, plant_name, zombie_name, finished_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, settleId);
            ps.setInt(2, roomId);
            ps.setString(3, winner);
            ps.setString(4, formatDurationMMSS(frames));
            ps.setString(5, mapBg(meta == null ? -1 : meta.background));
            ps.setString(6, mapBattleType(meta == null ? -1 : meta.battleType));
            ps.setString(7, (meta != null && meta.shuffleMode) ? "SHUFFLE" : "NORMAL");
            ps.setString(8, boolText(meta != null && meta.addonExtraPackets));
            ps.setString(9, boolText(meta != null && meta.addonExtraSeeds));
            ps.setString(10, boolText(meta != null && meta.addonBanMode));
            ps.setString(11, boolText(meta != null && meta.addonBalancePatch));
            ps.setInt(12, meta == null ? 0 : meta.mowerLoss);
            ps.setInt(13, meta == null ? 0 : meta.targetLoss);
            ps.setInt(14, meta == null ? 0 : meta.sunflowerLoss);
            ps.setInt(15, meta == null ? 0 : meta.graveLoss);
            ps.setString(16, clipName(meta == null ? "" : meta.plantName));
            ps.setString(17, clipName(meta == null ? "" : meta.zombieName));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("No generated key for match_results");
    }

    private static void insertCardUsage(long matchId, List<AnalyticsCollector.CardUsage> usages) throws SQLException {
        if (usages == null || usages.isEmpty()) return;
        String sql = "INSERT INTO match_card_usage(match_id, side, seed_type, seed_name, use_count, created_at) VALUES(?, ?, ?, ?, ?, datetime('now'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (AnalyticsCollector.CardUsage u : usages) {
                ps.setLong(1, matchId);
                ps.setString(2, u.side());
                ps.setInt(3, u.seedType());
                ps.setString(4, SeedTypeNames.nameOf(u.seedType()));
                ps.setInt(5, u.useCount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertMatchEvents(long matchId, List<AnalyticsCollector.MatchEvent> events) throws SQLException {
        if (events == null || events.isEmpty()) return;
        String sql = "INSERT INTO match_card_events(match_id, seq, side, event_type, seed_type, seed_name, created_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, datetime('now'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (AnalyticsCollector.MatchEvent e : events) {
                ps.setLong(1, matchId);
                ps.setInt(2, e.seq());
                ps.setString(3, e.side());
                ps.setString(4, e.eventType());
                ps.setInt(5, e.seedType());
                ps.setString(6, SeedTypeNames.nameOf(e.seedType()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void upsertCardStatsFromEvents(List<AnalyticsCollector.MatchEvent> events, boolean plantWin) throws SQLException {
        if (events == null || events.isEmpty()) return;
        Set<Integer> plantPicked = new java.util.LinkedHashSet<>();
        Set<Integer> zombiePicked = new java.util.LinkedHashSet<>();
        for (AnalyticsCollector.MatchEvent e : events) {
            if ("BAN".equals(e.eventType())) {
                upsertBanOnly(e.seedType());
            } else if ("PICK".equals(e.eventType())) {
                if ("ZOMBIE".equals(e.side()) && zombiePicked.size() < 8) zombiePicked.add(e.seedType());
                if ("PLANT".equals(e.side()) && plantPicked.size() < 8) plantPicked.add(e.seedType());
            }
        }
        upsertPickedSide(plantPicked, plantWin);
        upsertPickedSide(zombiePicked, !plantWin);
    }

    private static void upsertBanOnly(int seedType) throws SQLException {
        String sql = "INSERT INTO card_stats(seed_type, picked, banned, won, updated_at) " +
                "VALUES(?,0,1,0,datetime('now')) " +
                "ON CONFLICT(seed_type) DO UPDATE SET " +
                "banned = banned + 1, updated_at = datetime('now')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, seedType);
            ps.executeUpdate();
        }
    }

    private static void upsertPickedSide(Set<Integer> seeds, boolean won) throws SQLException {
        String sql = "INSERT INTO card_stats(seed_type, picked, banned, won, updated_at) " +
                "VALUES(?,1,0,?,datetime('now')) " +
                "ON CONFLICT(seed_type) DO UPDATE SET " +
                "picked = picked + 1, won = won + ?, updated_at = datetime('now')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int winDelta = won ? 1 : 0;
            for (int seed : seeds) {
                ps.setInt(1, seed);
                ps.setInt(2, winDelta);
                ps.setInt(3, winDelta);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static boolean ensureInit() {
        if (initialized && conn != null) return true;
        try {
            File dir = new File(DB_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[METRICS_DB] cannot create directory: " + dir.getAbsolutePath());
                return false;
            }
            conn = DriverManager.getConnection(DB_URL);
            createSchema(conn);
            initialized = true;
            return true;
        } catch (SQLException e) {
            System.out.println("[METRICS_DB] init failed: " + e.getMessage());
            return false;
        }
    }

    private static void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS match_results (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "settle_id TEXT NOT NULL UNIQUE," +
                    "room_id INTEGER NOT NULL," +
                    "winner TEXT NOT NULL," +
                    "duration_text TEXT NOT NULL DEFAULT '00:00'," +
                    "bg TEXT NOT NULL DEFAULT 'UNKNOWN'," +
                    "battle_type TEXT NOT NULL DEFAULT 'UNKNOWN'," +
                    "game_mode TEXT NOT NULL DEFAULT 'NORMAL'," +
                    "extra_packet TEXT NOT NULL DEFAULT 'false'," +
                    "extended_seeds TEXT NOT NULL DEFAULT 'false'," +
                    "ban_mode TEXT NOT NULL DEFAULT 'false'," +
                    "balance_patch TEXT NOT NULL DEFAULT 'false'," +
                    "mower_loss INTEGER NOT NULL DEFAULT 0," +
                    "target_loss INTEGER NOT NULL DEFAULT 0," +
                    "sunflower_loss INTEGER NOT NULL DEFAULT 0," +
                    "grave_loss INTEGER NOT NULL DEFAULT 0," +
                    "plant_name TEXT NOT NULL DEFAULT ''," +
                    "zombie_name TEXT NOT NULL DEFAULT ''," +
                    "finished_at TEXT NOT NULL" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS card_stats (" +
                    "seed_type INTEGER PRIMARY KEY," +
                    "picked INTEGER NOT NULL DEFAULT 0," +
                    "banned INTEGER NOT NULL DEFAULT 0," +
                    "won INTEGER NOT NULL DEFAULT 0," +
                    "updated_at TEXT NOT NULL" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS match_card_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "match_id INTEGER NOT NULL," +
                    "seq INTEGER NOT NULL," +
                    "side TEXT NOT NULL," +
                    "event_type TEXT NOT NULL," +
                    "seed_type INTEGER NOT NULL," +
                    "seed_name TEXT NOT NULL DEFAULT 'UnknownSeed'," +
                    "created_at TEXT NOT NULL," +
                    "FOREIGN KEY(match_id) REFERENCES match_results(id)" +
                    ")");
            st.execute("CREATE TABLE IF NOT EXISTS match_card_usage (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "match_id INTEGER NOT NULL," +
                    "side TEXT NOT NULL," +
                    "seed_type INTEGER NOT NULL," +
                    "seed_name TEXT NOT NULL DEFAULT 'UnknownSeed'," +
                    "use_count INTEGER NOT NULL," +
                    "created_at TEXT NOT NULL," +
                    "FOREIGN KEY(match_id) REFERENCES match_results(id)" +
                    ")");
        }
    }

    private static String boolText(boolean v) {
        return v ? "true" : "false";
    }

    private static String mapBattleType(int battleType) {
        switch (battleType) {
            case 9:
                return "QUICK";
            case 10:
                return "CUSTOM";
            case 11:
                return "RANDOM";
            default:
                return "UNKNOWN";
        }
    }

    private static String mapBg(int bg) {
        switch (bg) {
            case 0:
                return "DAY";
            case 1:
                return "NIGHT";
            case 2:
                return "POOL";
            case 3:
                return "FOG";
            case 4:
                return "ROOF";
            default:
                return "UNKNOWN";
        }
    }

    private static boolean isDuplicateSettleId(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("unique") && m.contains("match_results.settle_id");
    }

    private static String formatDurationMMSS(int frames) {
        int totalSeconds = Math.max(frames, 0) / 100;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static String clipName(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.length() <= 64) return v;
        return v.substring(0, 64);
    }
}
