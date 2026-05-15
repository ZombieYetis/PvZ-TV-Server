package org.marshive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

final class DashboardServer {
    private static final String DB_URL = "jdbc:sqlite:data/pvz_metrics.db";
    private static final int SUNFLOWER_SEED = 1;
    private static final int GRAVESTONE_SEED = 61;
    private static final CopyOnWriteArrayList<OutputStream> STREAM_CLIENTS = new CopyOnWriteArrayList<>();

    private DashboardServer() {
    }

    static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new IndexHandler());
        server.createContext("/api/top-decks", new TopDecksHandler());
        server.createContext("/api/card-stats", new CardStatsHandler());
        server.createContext("/api/recent-matches", new RecentMatchesHandler());
        server.createContext("/api/seed-names", new SeedNamesHandler());
        server.createContext("/api/stream", new StreamHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("[DASHBOARD] http://0.0.0.0:" + port + "/");
    }

    private static final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            writeText(exchange, 200, buildHtml(), "text/html; charset=utf-8");
        }
    }

    private static final class TopDecksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json; charset=utf-8");
                return;
            }
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String side = normalizeSide(queryParam(rawQuery, "side"));
            String extraPacket = normalizeBoolDefault(queryParam(rawQuery, "extra_packet"), false);
            String balancePatch = normalizeBoolDefault(queryParam(rawQuery, "balance_patch"), true);
            String banMode = normalizeBoolDefault(queryParam(rawQuery, "ban_mode"), false);
            String modeFilter = normalizeMapFilter(queryParam(rawQuery, "mode"));
            writeText(exchange, 200, queryTopDecksJson(side, extraPacket, balancePatch, banMode, modeFilter), "application/json; charset=utf-8");
        }
    }

    private static final class CardStatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json; charset=utf-8");
                return;
            }
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String side = normalizeSide(queryParam(rawQuery, "side"));
            String extraPacket = normalizeBoolDefault(queryParam(rawQuery, "extra_packet"), false);
            String balancePatch = normalizeBoolDefault(queryParam(rawQuery, "balance_patch"), true);
            String banMode = normalizeBoolDefault(queryParam(rawQuery, "ban_mode"), false);
            String modeFilter = normalizeMapFilter(queryParam(rawQuery, "mode"));
            writeText(exchange, 200, queryCardStatsJson(side, extraPacket, balancePatch, banMode, modeFilter), "application/json; charset=utf-8");
        }
    }

    private static final class SeedNamesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json; charset=utf-8");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"items\":[");
            boolean first = true;
            for (int seed = 0; seed <= 120; seed++) {
                String en = SeedTypeNames.nameOf(seed);
                if ("UnknownSeed".equals(en)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"seed_type\":").append(seed)
                        .append(",\"en\":\"").append(jsonEscape(en)).append("\"")
                        .append(",\"zh\":\"").append(jsonEscape(SeedTypeNames.zhNameOf(seed))).append("\"}");
            }
            sb.append("]}");
            writeText(exchange, 200, sb.toString(), "application/json; charset=utf-8");
        }
    }

    private static final class RecentMatchesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json; charset=utf-8");
                return;
            }
            writeText(exchange, 200, queryRecentMatchesJson(), "application/json; charset=utf-8");
        }
    }

    private static final class StreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            STREAM_CLIENTS.add(os);
            try {
                os.write("event: hello\ndata: ok\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                STREAM_CLIENTS.remove(os);
                try { os.close(); } catch (IOException ignored) {}
            }
        }
    }

    static void notifyDataUpdated() {
        byte[] payload = "event: settle\ndata: 1\n\n".getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : STREAM_CLIENTS) {
            try {
                os.write(payload);
                os.flush();
            } catch (IOException e) {
                STREAM_CLIENTS.remove(os);
                try { os.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static String queryTopDecksJson(String side, String extraPacket, String balancePatch, String banMode, String modeFilter) {
        int slotCount = "true".equals(extraPacket) ? 6 : 5;
        final String sql =
                "WITH raw_picks AS (" +
                        "  SELECT e.match_id, e.side, e.seq, e.seed_type " +
                        "  FROM match_card_events e JOIN match_results r ON r.id=e.match_id " +
                        "  WHERE e.event_type='PICK' AND e.side=? " +
                        "    AND ((?='ZOMBIE' AND e.seed_type>=61) OR (?='PLANT' AND e.seed_type<61)) " +
                        "    AND r.extra_packet=? " +
                        "    AND r.balance_patch=? " +
                        "    AND r.ban_mode=? " +
                        "    AND r.bg=? AND r.game_mode IN ('NORMAL','UNKNOWN') " +
                        "), uniq_picks AS (" +
                        "  SELECT match_id, side, seed_type, MIN(seq) AS first_seq " +
                        "  FROM raw_picks GROUP BY match_id, side, seed_type" +
                        "), decks AS (" +
                        "  SELECT u.match_id, u.side, (" +
                        "    SELECT GROUP_CONCAT(seed_type, '|') FROM (" +
                        "      SELECT u2.seed_type FROM uniq_picks u2 " +
                        "      WHERE u2.match_id = u.match_id AND u2.side = u.side " +
                        "      ORDER BY u2.seed_type ASC, u2.first_seq ASC LIMIT ?" +
                        "    )" +
                        "  ) AS deck_ids, COUNT(*) AS unique_cnt " +
                        "  FROM uniq_picks u GROUP BY u.match_id, u.side HAVING COUNT(*) = ?" +
                        "), scored AS (" +
                        "  SELECT d.side, d.deck_ids, COUNT(*) AS plays," +
                        "         SUM(CASE WHEN (d.side='PLANT' AND r.winner='PLANT') OR (d.side='ZOMBIE' AND r.winner='ZOMBIE') THEN 1 ELSE 0 END) AS wins" +
                        "  FROM decks d JOIN match_results r ON r.id=d.match_id" +
                        "  GROUP BY d.side, d.deck_ids" +
                        "), ranked AS (" +
                        "  SELECT side, deck_ids, plays, wins, CASE WHEN plays>0 THEN 1.0*wins/plays ELSE 0 END AS win_rate," +
                        "         ROW_NUMBER() OVER (PARTITION BY side ORDER BY (1.0*wins/plays) DESC, plays DESC, wins DESC) AS rn" +
                        "  FROM scored WHERE plays>0" +
                        ")" +
                        "SELECT side, deck_ids, plays, wins, win_rate FROM ranked WHERE rn<=15 ORDER BY rn";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        boolean first = true;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, side);
            ps.setString(2, side);
            ps.setString(3, side);
            ps.setString(4, extraPacket);
            ps.setString(5, balancePatch);
            ps.setString(6, banMode);
            ps.setString(7, modeFilter);
            ps.setInt(8, slotCount);
            ps.setInt(9, slotCount);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String deckIds = rs.getString("deck_ids");
                    CoreNames core = coreNameFromUsage(conn, side, deckIds, extraPacket, balancePatch, banMode, modeFilter, slotCount);
                    String coreZh = core.zh;
                    String coreEn = core.en;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"side\":\"").append(jsonEscape(rs.getString("side"))).append("\",")
                            .append("\"deck_ids\":\"").append(jsonEscape(deckIds)).append("\",")
                            .append("\"core_zh\":\"").append(jsonEscape(coreZh)).append("\",")
                            .append("\"core_en\":\"").append(jsonEscape(coreEn)).append("\",")
                            .append("\"plays\":").append(rs.getInt("plays")).append(',')
                            .append("\"wins\":").append(rs.getInt("wins")).append(',')
                            .append("\"win_rate\":").append(formatNum(rs.getDouble("win_rate"))).append('}');
                }
            }
        } catch (Exception e) {
            return "{\"items\":[],\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }
        sb.append("]}");
        return sb.toString();
    }

    private static CoreNames coreNameFromUsage(Connection conn, String side, String deckIds, String extraPacket, String balancePatch, String banMode, String modeFilter, int slotCount) {
        int minSeed = "ZOMBIE".equals(side) ? 61 : 0;
        int maxSeed = "ZOMBIE".equals(side) ? 120 : 60;
        final String sql =
                "WITH raw_picks AS (" +
                        "  SELECT e.match_id, e.side, e.seq, e.seed_type " +
                        "  FROM match_card_events e JOIN match_results r ON r.id=e.match_id " +
                        "  WHERE e.event_type='PICK' AND e.side=? " +
                        "    AND ((?='ZOMBIE' AND e.seed_type>=61) OR (?='PLANT' AND e.seed_type<61)) " +
                        "    AND r.extra_packet=? " +
                        "    AND r.balance_patch=? " +
                        "    AND r.ban_mode=? " +
                        "    AND r.bg=? AND r.game_mode IN ('NORMAL','UNKNOWN') " +
                        "), uniq_picks AS (" +
                        "  SELECT match_id, side, seed_type, MIN(seq) AS first_seq " +
                        "  FROM raw_picks GROUP BY match_id, side, seed_type" +
                        "), decks AS (" +
                        "  SELECT u.match_id, u.side, (" +
                        "    SELECT GROUP_CONCAT(seed_type, '|') FROM (" +
                        "      SELECT u2.seed_type FROM uniq_picks u2 " +
                        "      WHERE u2.match_id = u.match_id AND u2.side = u.side " +
                        "      ORDER BY u2.seed_type ASC, u2.first_seq ASC LIMIT ?" +
                        "    )" +
                        "  ) AS deck_ids " +
                        "  FROM uniq_picks u GROUP BY u.match_id, u.side HAVING COUNT(*) = ?" +
                        "), agg AS (" +
                        "  SELECT u.seed_type, SUM(u.use_count) AS total_use " +
                        "  FROM match_card_usage u JOIN decks d ON d.match_id=u.match_id AND d.side=u.side " +
                        "  WHERE d.side=? AND d.deck_ids=? AND u.side=? AND u.use_count>0 AND u.seed_type BETWEEN ? AND ? AND u.seed_type NOT IN (?,?) " +
                        "  GROUP BY u.seed_type" +
                        ")" +
                        "SELECT seed_type FROM agg ORDER BY total_use DESC, seed_type ASC LIMIT 2";
        List<Integer> picked = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, side);
            ps.setString(2, side);
            ps.setString(3, side);
            ps.setString(4, extraPacket);
            ps.setString(5, balancePatch);
            ps.setString(6, banMode);
            ps.setString(7, modeFilter);
            ps.setInt(8, slotCount);
            ps.setInt(9, slotCount);
            ps.setString(10, side);
            ps.setString(11, deckIds);
            ps.setString(12, side);
            ps.setInt(13, minSeed);
            ps.setInt(14, maxSeed);
            ps.setInt(15, SUNFLOWER_SEED);
            ps.setInt(16, GRAVESTONE_SEED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) picked.add(rs.getInt("seed_type"));
            }
        } catch (SQLException ignored) {
        }
        if (picked.isEmpty()) return new CoreNames(coreNameForSide(deckIds, true, side), coreNameForSide(deckIds, false, side));
        String aZh = SeedTypeNames.zhNameOf(picked.get(0));
        String aEn = SeedTypeNames.nameOf(picked.get(0));
        if (picked.size() == 1) return new CoreNames(aZh, aEn);
        String bZh = SeedTypeNames.zhNameOf(picked.get(1));
        String bEn = SeedTypeNames.nameOf(picked.get(1));
        return new CoreNames(aZh + bZh, aEn + bEn);
    }

    private static String queryCardStatsJson(String side, String extraPacket, String balancePatch, String banMode, String modeFilter) {
        final String sql =
                "WITH scoped AS (" +
                        "  SELECT e.seed_type, e.side, e.event_type, r.winner " +
                        "  FROM match_card_events e JOIN match_results r ON r.id=e.match_id " +
                        "  WHERE e.side=? " +
                        "    AND ((?='ZOMBIE' AND e.seed_type>=61) OR (?='PLANT' AND e.seed_type<61)) " +
                        "    AND r.extra_packet=? " +
                        "    AND r.balance_patch=? " +
                        "    AND r.ban_mode=? " +
                        "    AND r.bg=? AND r.game_mode IN ('NORMAL','UNKNOWN') " +
                        "), totals AS (" +
                        "  SELECT " +
                        "    SUM(CASE WHEN event_type='PICK' THEN 1 ELSE 0 END) AS total_picked, " +
                        "    SUM(CASE WHEN event_type='BAN' THEN 1 ELSE 0 END) AS total_banned " +
                        "  FROM scoped" +
                        "), agg AS (" +
                        "  SELECT seed_type, " +
                        "    SUM(CASE WHEN event_type='PICK' THEN 1 ELSE 0 END) AS picked, " +
                        "    SUM(CASE WHEN event_type='BAN' THEN 1 ELSE 0 END) AS banned, " +
                        "    SUM(CASE WHEN event_type='PICK' AND ((side='PLANT' AND winner='PLANT') OR (side='ZOMBIE' AND winner='ZOMBIE')) THEN 1 ELSE 0 END) AS won " +
                        "  FROM scoped GROUP BY seed_type" +
                        "), calc AS (" +
                        "  SELECT a.seed_type, a.picked, a.banned, a.won, " +
                        "         CASE WHEN a.picked>0 THEN 1.0*a.won/a.picked ELSE 0 END AS win_rate, " +
                        "         CASE WHEN t.total_picked>0 THEN 1.0*a.picked/t.total_picked ELSE 0 END AS pick_rate, " +
                        "         CASE WHEN t.total_banned>0 THEN 1.0*a.banned/t.total_banned ELSE 0 END AS ban_rate, " +
                        "         CASE WHEN a.picked>0 THEN 1.0*a.banned/a.picked ELSE 0 END AS target_rate " +
                        "  FROM agg a CROSS JOIN totals t " +
                        "  WHERE (a.picked+a.banned+a.won)>0" +
                        ")" +
                        "SELECT seed_type, picked, banned, won, win_rate, pick_rate, ban_rate, target_rate " +
                        "FROM calc ORDER BY win_rate DESC, pick_rate DESC, ban_rate DESC, target_rate DESC, seed_type";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        boolean first = true;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, side);
            ps.setString(2, side);
            ps.setString(3, side);
            ps.setString(4, extraPacket);
            ps.setString(5, balancePatch);
            ps.setString(6, banMode);
            ps.setString(7, modeFilter);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    int seed = rs.getInt("seed_type");
                    sb.append("{\"seed_type\":").append(seed).append(',')
                            .append("\"picked\":").append(rs.getInt("picked")).append(',')
                            .append("\"banned\":").append(rs.getInt("banned")).append(',')
                            .append("\"won\":").append(rs.getInt("won")).append(',')
                            .append("\"win_rate\":").append(formatNum(rs.getDouble("win_rate"))).append(',')
                            .append("\"pick_rate\":").append(formatNum(rs.getDouble("pick_rate"))).append(',')
                            .append("\"ban_rate\":").append(formatNum(rs.getDouble("ban_rate"))).append(',')
                            .append("\"target_rate\":").append(formatNum(rs.getDouble("target_rate"))).append(',')
                            .append("\"seed_en\":\"").append(jsonEscape(SeedTypeNames.nameOf(seed))).append("\",")
                            .append("\"seed_zh\":\"").append(jsonEscape(SeedTypeNames.zhNameOf(seed))).append("\"}");
                }
            }
        } catch (Exception e) {
            return "{\"items\":[],\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String queryRecentMatchesJson() {
        final String sql = "SELECT finished_at, winner, plant_name, zombie_name, duration_text FROM match_results ORDER BY id DESC LIMIT 20";
        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        boolean first = true;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"finished_at\":\"").append(jsonEscape(rs.getString("finished_at"))).append("\",")
                        .append("\"winner\":\"").append(jsonEscape(rs.getString("winner"))).append("\",")
                        .append("\"plant_name\":\"").append(jsonEscape(rs.getString("plant_name"))).append("\",")
                        .append("\"zombie_name\":\"").append(jsonEscape(rs.getString("zombie_name"))).append("\",")
                        .append("\"duration\":\"").append(jsonEscape(rs.getString("duration_text"))).append("\"}");
            }
        } catch (Exception e) {
            return "{\"items\":[],\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String coreNameForSide(String deckIds, boolean zh, String side) {
        List<Integer> ids = parseDeckIds(deckIds);
        List<Integer> nonProducer = new ArrayList<>();
        boolean zombie = "ZOMBIE".equals(side);
        for (int id : ids) {
            if (zombie && id < 61) continue;
            if (!zombie && id >= 61) continue;
            if (id == SUNFLOWER_SEED || id == GRAVESTONE_SEED) continue;
            nonProducer.add(id);
        }
        if (nonProducer.isEmpty()) return zh ? "未知核心" : "UnknownCore";
        String a = zh ? SeedTypeNames.zhNameOf(nonProducer.get(0)) : SeedTypeNames.nameOf(nonProducer.get(0));
        if (nonProducer.size() == 1) return a;
        String b = zh ? SeedTypeNames.zhNameOf(nonProducer.get(1)) : SeedTypeNames.nameOf(nonProducer.get(1));
        return a + b;
    }

    private record CoreNames(String zh, String en) {
    }

    private static List<Integer> parseDeckIds(String deckIds) {
        List<Integer> ids = new ArrayList<>();
        if (deckIds == null || deckIds.isEmpty()) return ids;
        String[] parts = deckIds.split("\\|");
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(p));
            } catch (NumberFormatException ignore) {
            }
        }
        return ids;
    }

    private static String buildHtml() {
        return "<!doctype html><html><head><meta charset='utf-8'/>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'/>" +
                "<title>PvZ_TV 对战大数据</title>" +
                "<style>" +
                ":root{--bg:#e7e1d2;--card:#d9d2c2;--line:#b7ad98;--text:#1b2430;--muted:#5b6678;--accent:#2f80ed;--top:#000000;--topText:#ffffff;--panel:#eee6d5;--panelLine:#c4baa6;--panelHover:#e5dccb;--tableHead:#e6decd}" +
                "body.dark{--bg:#0f141b;--card:#151d28;--line:#253246;--text:#e6edf6;--muted:#9aa9bf;--accent:#5aa3ff;--top:#000000;--topText:#ffffff;--panel:#1b2636;--panelLine:#2b3b52;--panelHover:#243247;--tableHead:#202d40}" +
                "*{box-sizing:border-box}" +
                "body{margin:0;padding:0 18px 18px 18px;background:var(--bg);color:var(--text);font-family:'Microsoft YaHei','Segoe UI',Arial,sans-serif}" +
                ".top{display:flex;justify-content:space-between;align-items:flex-end;gap:10px;flex-wrap:wrap;background:var(--top);color:var(--topText);padding:14px 16px;border-radius:0 0 12px 12px}" +
                "h1{margin:0;font-size:28px}.muted{color:var(--muted);font-size:12px}" +
                ".top .muted{color:rgba(255,255,255,0.78)}" +
                ".controls{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;column-gap:12px;flex:1;width:100%;transform:translateY(-6px)}" +
                ".filters{grid-column:2;display:flex;gap:8px;align-items:center;justify-content:center;flex-wrap:wrap;transform:translateX(-148px)}" +
                ".actions{grid-column:3;justify-self:end;display:flex;gap:0;align-items:center;white-space:nowrap;transform:translateX(-32px)}" +
                ".btn{padding:7px 12px;border:1px solid rgba(255,255,255,0.7);border-radius:10px;background:transparent;color:var(--topText);cursor:pointer}" +
                ".actions .btn{padding:0;border:none;border-radius:0;background:transparent;color:rgba(255,255,255,0.62);font-size:13px;font-weight:400}" +
                ".actions .sep{padding:0 7px;color:rgba(255,255,255,0.5)}" +
                ".flt{padding:8px 12px;border:1px solid rgba(255,255,255,0.7);border-radius:10px;background:transparent;color:var(--topText);font-size:15px;font-weight:700}" +
                ".flt option{color:#111}" +
                ".seg{display:flex;gap:8px}" +
                ".seg .btn{padding:10px 18px;font-size:15px;font-weight:700;min-width:88px}" +
                ".seg .btn.active{background:#2f80ed;color:#fff;border-color:#2f80ed}" +
                ".layout{display:grid;grid-template-columns:minmax(560px,1.15fr) minmax(460px,0.85fr);gap:14px;margin-top:10px;align-items:start}" +
                ".card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px}" +
                ".card-head{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap}" +
                ".deck-wrap{display:grid;grid-template-columns:minmax(280px,430px) 1fr;gap:16px;align-items:start}" +
                ".pie-box{display:flex;justify-content:center;align-items:center}" +
                "#pie{width:100%;max-width:420px;aspect-ratio:1/1;border-radius:12px;background:#f8fbff;border:1px solid #e4ecf8}" +
                "body.dark #pie{background:#070b12;border-color:#1c2738}" +
                ".deck-list{height:640px;overflow:auto;border:1px solid var(--panelLine);border-radius:10px;padding:8px;background:var(--panel)}" +
                ".deck-item{padding:8px;border-radius:8px;border:1px solid transparent;margin-bottom:6px;cursor:pointer}" +
                ".deck-item:hover,.deck-item.active{border-color:var(--panelLine);background:var(--panelHover)}" +
                ".deck-core{font-weight:700}" +
                ".deck-meta{font-size:12px;color:var(--muted)}" +
                ".deck-cards{font-size:12px;word-break:break-word;margin-top:2px}" +
                "table{width:100%;border-collapse:collapse;font-size:13px}" +
                "th,td{padding:6px 8px;border-bottom:1px solid #edf1f7;text-align:left}" +
                "th{background:var(--tableHead);position:sticky;top:0}" +
                "#cardsWrap{height:640px;overflow:auto;border:1px solid var(--panelLine);border-radius:10px;background:var(--panel)}" +
                "#recentWrap{max-height:320px;overflow:auto;border:1px solid var(--panelLine);border-radius:10px;background:var(--panel)}" +
                "@media (max-width:1180px){.layout{grid-template-columns:1fr}.deck-wrap{grid-template-columns:1fr}.deck-list{height:460px}#cardsWrap{height:500px}.controls{display:flex;flex-wrap:wrap;justify-content:center}.actions{justify-self:auto}}" +
                "</style></head><body>" +
                "<div class='top'><div><h1 id='topTitle'>PvZ_TV 对战大数据</h1><div class='muted' id='subTitle'>每5秒自动刷新</div></div>" +
                "<div class='controls'><div class='filters'><select id='extraSel' class='flt'><option value='false'>额外卡槽: 关</option><option value='true'>额外卡槽: 开</option></select><select id='bpSel' class='flt'><option value='true'>平衡调整: 开</option><option value='false'>平衡调整: 关</option></select><select id='modeSel' class='flt'><option value='DAY'>白天</option><option value='NIGHT'>黑夜</option><option value='POOL'>泳池白天</option><option value='FOG'>泳池黑夜</option><option value='ROOF'>屋顶</option></select></div><div class='actions'><button id='langBtn' class='btn'>中文 / EN</button><span class='sep'>|</span><button id='themeBtn' class='btn'>日间 / 夜间</button></div></div></div>" +
                "<div class='layout'>" +
                "<div class='card'><div class='card-head'><h3 id='deckTitle'>Top 15 卡组</h3><div class='seg'><button id='sidePlant' class='btn active'>植物</button><button id='sideZombie' class='btn'>僵尸</button></div></div><div class='deck-wrap'><div class='pie-box'><canvas id='pie' width='420' height='420'></canvas></div><div id='deckList' class='deck-list'></div></div></div>" +
                "<div class='card'><h3 id='cardTitle'>单卡统计</h3><div id='cardsWrap'><table id='cards'><thead><tr><th id='thCard'>卡牌</th><th id='thPick'>选取</th><th id='thBan'>禁用</th><th id='thWin'>胜场</th><th id='thWr'>胜率</th><th id='thPr'>选取率</th><th id='thBr'>禁用率</th><th id='thTr'>针对率</th></tr></thead><tbody></tbody></table></div></div>" +
                "</div>" +
                "<div class='card' style='margin-top:14px'><h3>Recent Matches</h3><div id='recentWrap'><table id='recent'><thead><tr><th>Time</th><th>Plant</th><th>Zombie</th><th>Winner</th><th>Duration</th></tr></thead><tbody></tbody></table></div></div>" +
                "<script>" +
                "let lang='zh';let side='PLANT';let names={};let decks=[];let hoverDeck='';let extraPacket='false';let balancePatch='true';let banMode='false';let mode='DAY';let theme=(localStorage.getItem('pvz_theme')||'light');" +
                "const i18n={zh:{title:'PvZ_TV 对战大数据',deckTitle:'Top 15 卡组',cardTitle:'单卡统计',sub:'每5秒自动刷新',card:'卡牌',pick:'选取',ban:'禁用',win:'胜场',wr:'胜率',pr:'选取率',br:'禁用率',tr:'针对率',trTip:'被禁次数 / 被选次数',plant:'植物',zombie:'僵尸',unknown:'未知卡牌',sideSuffix:'方',rate:'胜率',plays:'对局',extraOff:'额外卡槽: 关',extraOn:'额外卡槽: 开',bpOn:'平衡调整: 开',bpOff:'平衡调整: 关',modeDay:'白天',modeNight:'黑夜',modePool:'泳池白天',modeFog:'泳池黑夜',modeRoof:'屋顶',themeLight:'☀日间',themeDark:'🌙夜间'},en:{title:'PvZ_TV Battle Data',deckTitle:'Top 15 Decks',cardTitle:'Card Stats',sub:'Auto refresh every 5s',card:'Card',pick:'Pick',ban:'Ban',win:'Wins',wr:'Win Rate',pr:'Pick Rate',br:'Ban Rate',tr:'Target Rate',trTip:'Banned Count / Picked Count',plant:'Plant',zombie:'Zombie',unknown:'UnknownSeed',sideSuffix:'',rate:'Win Rate',plays:'Plays',extraOff:'Extra Slot: Off',extraOn:'Extra Slot: On',bpOn:'Balance Patch: On',bpOff:'Balance Patch: Off',modeDay:'Day',modeNight:'Night',modePool:'Pool Day',modeFog:'Pool Night',modeRoof:'Roof',themeLight:'☀Light',themeDark:'🌙Dark'}};" +
                "function pct(x){return (x*100).toFixed(2)+'%'}" +
                "function sideText(s){return s==='PLANT'?i18n[lang].plant:i18n[lang].zombie}" +
                "function cardName(id,en,zh){const n=names[id];if(n){return lang==='zh'?(n.zh||n.en):n.en;}return lang==='zh'?(zh||en||i18n[lang].unknown):(en||i18n[lang].unknown)}" +
                "function labels(){const t=i18n[lang];document.getElementById('topTitle').textContent=t.title;document.getElementById('deckTitle').textContent=sideText(side)+t.sideSuffix+' '+t.deckTitle;document.getElementById('cardTitle').textContent=t.cardTitle;document.getElementById('subTitle').textContent=t.sub;document.getElementById('thCard').textContent=t.card;document.getElementById('thPick').textContent=t.pick;document.getElementById('thBan').textContent=t.ban;document.getElementById('thWin').textContent=t.win;document.getElementById('thWr').textContent=t.wr;document.getElementById('thPr').textContent=t.pr;document.getElementById('thBr').textContent=t.br;document.getElementById('thTr').textContent=t.tr;document.getElementById('thTr').title=t.trTip;const e=document.getElementById('extraSel');e.options[0].text=t.extraOff;e.options[1].text=t.extraOn;const b=document.getElementById('bpSel');b.options[0].text=t.bpOn;b.options[1].text=t.bpOff;const m=document.getElementById('modeSel');m.options[0].text=t.modeDay;m.options[1].text=t.modeNight;m.options[2].text=t.modePool;m.options[3].text=t.modeFog;m.options[4].text=t.modeRoof;document.getElementById('sidePlant').textContent=t.plant;document.getElementById('sideZombie').textContent=t.zombie;document.getElementById('langBtn').textContent=(lang==='zh'?'中文 / EN':'EN / 中文');document.getElementById('themeBtn').textContent=(theme==='dark'?t.themeDark:t.themeLight);applyBanColumns();}" +
                "function applyBanColumns(){const on=(banMode==='true');['thBan','thBr','thTr'].forEach(id=>{const el=document.getElementById(id);if(el)el.style.display=on?'':'none';});}" +
                "async function loadNames(){const r=await fetch('/api/seed-names');const j=await r.json();names={};(j.items||[]).forEach(it=>names[it.seed_type]={en:it.en,zh:it.zh});}" +
                "function color(i,a){const c=[[33,150,243],[0,200,83],[255,171,64],[255,82,82],[171,71,188],[0,229,255],[124,255,178],[41,182,246],[92,107,192],[255,138,101],[255,112,67],[64,196,255],[105,240,174],[255,214,0],[176,190,197]];const v=c[i%c.length];return 'rgba('+v[0]+','+v[1]+','+v[2]+','+a+')'}" +
                "function drawPie(){const cv=document.getElementById('pie');const ctx=cv.getContext('2d');ctx.clearRect(0,0,cv.width,cv.height);const total=decks.reduce((s,d)=>s+d.plays,0);if(total<=0){ctx.fillStyle='#6c7a90';ctx.font='16px Microsoft YaHei';ctx.fillText('No Data',170,210);return;}let start=-Math.PI/2;const cx=210,cy=210,r=170;" +
                "decks.forEach((d,i)=>{const ang=(d.plays/total)*Math.PI*2;const active=(hoverDeck===''||hoverDeck===d.deck_ids);ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,start+ang);ctx.closePath();ctx.fillStyle=color(i,active?0.9:0.22);ctx.fill();start+=ang;});" +
                "ctx.beginPath();ctx.arc(cx,cy,84,0,Math.PI*2);ctx.fillStyle=document.body.classList.contains('dark')?'#070b12':'#fff';ctx.fill();ctx.fillStyle=document.body.classList.contains('dark')?'#ffffff':'#2d3a52';ctx.font='bold 18px Microsoft YaHei';ctx.textAlign='center';ctx.fillText(String(total),cx,204);ctx.font='12px Microsoft YaHei';ctx.fillStyle='#74839b';ctx.fillText(lang==='zh'?'对局总数':'Total',cx,224);}" +
                "function renderDecks(){const box=document.getElementById('deckList');box.innerHTML='';decks.forEach((d,idx)=>{const div=document.createElement('div');div.className='deck-item'+(hoverDeck===d.deck_ids?' active':'');const ids=(d.deck_ids||'').split('|').filter(Boolean).map(x=>parseInt(x,10));const deckText=ids.map(id=>cardName(id,'','')).join(' | ');const core=lang==='zh'?(d.core_zh||'未知核心'):(d.core_en||'UnknownCore');div.innerHTML='<div class=\"deck-core\">#'+(idx+1)+' '+core+'</div><div class=\"deck-meta\">'+i18n[lang].plays+': '+d.plays+' | '+i18n[lang].rate+': '+pct(d.win_rate)+'</div><div class=\"deck-cards\">'+deckText+'</div>';div.addEventListener('mouseenter',()=>{hoverDeck=d.deck_ids;renderDecks();drawPie();});div.addEventListener('mouseleave',()=>{hoverDeck='';renderDecks();drawPie();});box.appendChild(div);});}" +
                "function qs(){return 'side='+encodeURIComponent(side)+'&extra_packet='+encodeURIComponent(extraPacket)+'&balance_patch='+encodeURIComponent(balancePatch)+'&ban_mode='+encodeURIComponent(banMode)+'&mode='+encodeURIComponent(mode)}" +
                "async function loadDecks(){const r=await fetch('/api/top-decks?'+qs());const j=await r.json();decks=j.items||[];if(hoverDeck && !decks.some(d=>d.deck_ids===hoverDeck))hoverDeck='';renderDecks();drawPie();}" +
                "async function loadCards(){const r=await fetch('/api/card-stats?'+qs());const j=await r.json();const tb=document.querySelector('#cards tbody');tb.innerHTML='';const on=(banMode==='true');(j.items||[]).forEach(it=>{const tr=document.createElement('tr');tr.innerHTML=on?('<td>'+cardName(it.seed_type,it.seed_en,it.seed_zh)+'</td><td>'+it.picked+'</td><td>'+it.banned+'</td><td>'+it.won+'</td><td>'+pct(it.win_rate)+'</td><td>'+pct(it.pick_rate)+'</td><td>'+pct(it.ban_rate)+'</td><td>'+pct(it.target_rate)+'</td>'):('<td>'+cardName(it.seed_type,it.seed_en,it.seed_zh)+'</td><td>'+it.picked+'</td><td>'+it.won+'</td><td>'+pct(it.win_rate)+'</td><td>'+pct(it.pick_rate)+'</td>');tb.appendChild(tr);});}" +
                "async function loadRecent(){const r=await fetch('/api/recent-matches');const j=await r.json();const tb=document.querySelector('#recent tbody');tb.innerHTML='';(j.items||[]).forEach(it=>{const tr=document.createElement('tr');const p=it.plant_name&&it.plant_name.length?it.plant_name:'-';const z=it.zombie_name&&it.zombie_name.length?it.zombie_name:'-';tr.innerHTML='<td>'+it.finished_at+'</td><td>'+p+'</td><td>'+z+'</td><td>'+it.winner+'</td><td>'+it.duration+'</td>';tb.appendChild(tr);});}" +
                "async function tick(){await Promise.all([loadDecks(),loadCards(),loadRecent()]);}" +
                "function renderSideBtns(){const p=document.getElementById('sidePlant');const z=document.getElementById('sideZombie');if(side==='PLANT'){p.classList.add('active');z.classList.remove('active');}else{z.classList.add('active');p.classList.remove('active');}}" +
                "document.getElementById('langBtn').addEventListener('click',async()=>{lang=(lang==='zh'?'en':'zh');labels();const bn=document.getElementById('banSel');if(bn){bn.options[0].text=(lang==='zh'?'禁选模式: 关':'Ban Mode: Off');bn.options[1].text=(lang==='zh'?'禁选模式: 开':'Ban Mode: On');}renderSideBtns();renderDecks();drawPie();await loadCards();});" +
                "function applyTheme(){document.body.classList.toggle('dark',theme==='dark');const t=i18n[lang];document.getElementById('themeBtn').textContent=(theme==='dark'?t.themeDark:t.themeLight);}" +
                "document.getElementById('themeBtn').addEventListener('click',()=>{theme=(theme==='dark'?'light':'dark');localStorage.setItem('pvz_theme',theme);applyTheme();drawPie();});" +
                "document.getElementById('sidePlant').addEventListener('click',async()=>{if(side==='PLANT')return;side='PLANT';hoverDeck='';renderSideBtns();labels();await tick();});" +
                "document.getElementById('sideZombie').addEventListener('click',async()=>{if(side==='ZOMBIE')return;side='ZOMBIE';hoverDeck='';renderSideBtns();labels();await tick();});" +
                "document.getElementById('extraSel').addEventListener('change',async(e)=>{extraPacket=e.target.value;hoverDeck='';await tick();});" +
                "document.getElementById('bpSel').addEventListener('change',async(e)=>{balancePatch=e.target.value;hoverDeck='';await tick();});" +
                "document.getElementById('modeSel').addEventListener('change',async(e)=>{mode=e.target.value;hoverDeck='';await tick();});" +
                "(async()=>{const bp=document.getElementById('bpSel');if(bp){const bn=document.createElement('select');bn.id='banSel';bn.className='flt';bn.innerHTML='<option value=\"false\">Ban Mode: Off</option><option value=\"true\">Ban Mode: On</option>';bp.insertAdjacentElement('afterend',bn);bn.addEventListener('change',async(e)=>{banMode=e.target.value;hoverDeck='';applyBanColumns();await tick();});}applyTheme();labels();const bn2=document.getElementById('banSel');if(bn2){bn2.options[0].text=(lang==='zh'?'禁选模式: 关':'Ban Mode: Off');bn2.options[1].text=(lang==='zh'?'禁选模式: 开':'Ban Mode: On');}applyBanColumns();renderSideBtns();await loadNames();await tick();if(window.EventSource){const es=new EventSource('/api/stream');es.addEventListener('settle',()=>{tick();});es.onerror=()=>{};}})();" +
                "</script></body></html>";
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        String[] items = rawQuery.split("&");
        for (String item : items) {
            int p = item.indexOf('=');
            if (p <= 0) continue;
            String k = item.substring(0, p);
            if (!key.equalsIgnoreCase(k)) continue;
            return item.substring(p + 1);
        }
        return null;
    }

    private static String normalizeSide(String raw) {
        if (raw == null) return "PLANT";
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return "ZOMBIE".equals(v) ? "ZOMBIE" : "PLANT";
    }

    private static String normalizeBoolDefault(String raw, boolean defaultValue) {
        if (raw == null) return defaultValue ? "true" : "false";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "on".equals(v)) return "true";
        if ("false".equals(v) || "0".equals(v) || "off".equals(v)) return "false";
        return defaultValue ? "true" : "false";
    }

    private static String normalizeMapFilter(String raw) {
        if (raw == null) return "DAY";
        String v = raw.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "DAY":
            case "NIGHT":
            case "POOL":
            case "FOG":
            case "ROOF":
                return v;
            default:
                return "DAY";
        }
    }

    private static void writeText(HttpExchange ex, int code, String body, String contentType) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String formatNum(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
