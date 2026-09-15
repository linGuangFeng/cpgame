package com.cpgame.glacier;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Controller v3: one process, injected 5xxxx port, Redis-only complete rounds, original static publish. */
public final class GlacierTreasureController {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final String INIT_BOARD =
        "110.210.310.410.510:610.710.810.910.A10:B10.610.710.810.910:A10.B10.610.710.810:910.A10.B10.610.710:510.410.310.210.110/BA98";

    private final Path publishRoot;
    private final BigDecimal initialBalance;
    private final RedisRoundPool pool;
    private final ProtocolProjector projector = new ProtocolProjector();
    private final CompleteRoundCodec codec = new CompleteRoundCodec();
    private final GameRuleCore core = new GameRuleCore();

    private GlacierTreasureController(Path publishRoot, Properties config) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "100000.00"));
        this.pool = new RedisRoundPool(
            config.getProperty("redis.host", "18.234.101.161"),
            Integer.parseInt(config.getProperty("redis.port", "8021")),
            Integer.parseInt(config.getProperty("redis.database", "0")),
            config.getProperty("redis.username", ""),
            config.getProperty("redis.password", ""),
            Long.parseLong(config.getProperty("redis.game-id", "8001780")));
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        Path publish = resolvePublish(options.getOrDefault("publish",
            config.getProperty("publish.directory", "")));
        GlacierTreasureController controller = new GlacierTreasureController(publish, config);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "glacier-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=1780 port=%d pid=%d rulesHash=%s publish=%s%n",
            port, ProcessHandle.current().pid(), GameRuleCore.RULES_HASH, publish);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { sendEmpty(exchange, 204); return; }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            boolean api = path.startsWith("/cp/") || path.startsWith("/api/");
            if (api && ("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))) {
                handleApi(exchange, path);
                return;
            }
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                serveStatic(exchange);
                return;
            }
            sendError(exchange, 405, "method not allowed");
        } catch (IllegalStateException ex) {
            sendError(exchange, 500, ex.getMessage()==null ? "controller failure" : ex.getMessage());
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendError(exchange, 500, "controller failure");
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        Map<String,String> form = params(exchange);
        Session session = session(exchange, form);
        switch (path) {
            case "/cp/config/initialData" -> sendJson(exchange, ok(configData(form)));
            case "/cp/account/getUserInfo" -> sendJson(exchange, ok(userData(session)));
            case "/cp/account/getBalance" -> sendJson(exchange, ok(balanceData(session)));
            case "/cp/account/session" -> sendJson(exchange, ok(sessionData(session)));
            case "/cp/activity/getActivity" -> sendJson(exchange, ok(Map.of("free",
                Map.of("act_list", List.of(), "invite_act_have", 0, "invite_end_time", 0))));
            case "/cp/activity/verifyInviteCode" -> sendJson(exchange, ok(Map.of("ok", 1)));
            case "/cp/single_game.Game/initRoom" -> sendJson(exchange, ok(initData(session)));
            case "/cp/single_game.Game/gameResult" -> sendJson(exchange, ok(spin(session, form)));
            case "/cp/goldgame/single_game_user_gold_history" -> sendJson(exchange, ok(historySummary(session)));
            case "/cp/goldgame/single_game_user_history" -> sendJson(exchange, ok(historyDetail(session, form)));
            case "/cp/goldGame/getUserHistory" -> sendJson(exchange, ok(historySummary(session)));
            case "/cp/goldGame/gameLogShowLimit" -> sendJson(exchange, ok(Map.of("time", Instant.now().getEpochSecond(), "info", List.of())));
            case "/cp/heart/heart_check" -> sendJson(exchange, ok(Map.of("ok", 1, "server_time", Instant.now().getEpochSecond())));
            case "/cp/Notice/getStopgsNotice" -> sendJson(exchange, ok(Map.of("list", List.of())));
            case "/cp/config/setGameConfig" -> sendJson(exchange, ok(Map.of()));
            case "/api/report/timing" -> sendJson(exchange, ok(Map.of("ok", 1)));
            case "/api/balance" -> sendJson(exchange, ok(balanceData(session)));
            case "/api/session" -> sendJson(exchange, ok(sessionData(session)));
            default -> sendJson(exchange, ok(Map.of()));
        }
    }

    private Map<String,Object> spin(Session state, Map<String,String> form) throws Exception {
        synchronized (state) {
            boolean buy = "3".equals(form.get("bet_type"));
            BigDecimal betSize = decimal(form.getOrDefault("bet_gold", "0.02"));
            int level = Integer.parseInt(form.getOrDefault("level", "10"));
            if (level<1 || level>10) throw new IllegalArgumentException("level");
            if (state.pending==null) {
                RedisRoundPool.Kind kind;
                if (buy) kind = RedisRoundPool.Kind.SPECIAL;
                else if (!java.util.concurrent.ThreadLocalRandom.current().nextBoolean())
                    kind = RedisRoundPool.Kind.LOSS;
                else kind = java.util.concurrent.ThreadLocalRandom.current().nextInt(5)==0
                    ? RedisRoundPool.Kind.SPECIAL : RedisRoundPool.Kind.WIN;
                RedisRoundPool.Claimed claimed;
                try { claimed = pool.claim(kind); }
                catch (Exception ex) {
                    if (kind==RedisRoundPool.Kind.SPECIAL && !buy) {
                        try { claimed = pool.claim(RedisRoundPool.Kind.WIN); }
                        catch (Exception e2) { throw new IllegalStateException("Redis round claim failed: "+ex.getMessage()); }
                    } else throw new IllegalStateException("Redis round claim failed: "+ex.getMessage());
                }
                CompleteRoundFact fact = codec.decode(claimed.member(), buy);
                List<Map<String,Object>> deliveries = projector.deliveries(fact, betSize, level, state.balance, buy);
                state.pending = new Pending(deliveries, 0, claimed.member());
            }
            Pending p = state.pending;
            Map<String,Object> delivery = p.deliveries.get(p.index);
            state.balance = (BigDecimal) delivery.get("end_gold");
            state.last = delivery;
            if (p.index==0) state.history.add(new HistoryRow(System.currentTimeMillis()/1000, new ArrayList<>(List.of(delivery))));
            else state.history.get(state.history.size()-1).deliveries.add(delivery);
            p.index++;
            if (p.index >= p.deliveries.size()) state.pending = null;
            return delivery;
        }
    }

    private Map<String,Object> initData(Session state) {
        synchronized (state) {
            if (state.last!=null) {
                var data = new LinkedHashMap<>(state.last);
                data.put("prop_odds", paytable());
                return data;
            }
            CompleteRoundFact fact = codec.decode(INIT_BOARD, false);
            GameRuleCore.Board board = fact.spins().get(0).get(0);
            var eval = core.evaluate(board, core.stakeUnit(new BigDecimal("0.02"), 10), 1);
            var data = new LinkedHashMap<String,Object>();
            data.put("bet", new BigDecimal("0.02"));
            data.put("bet_gold", core.betAmount(new BigDecimal("0.02"), 10));
            data.put("big_win", 0);
            data.put("change_gold", 0);
            data.put("end_gold", state.balance);
            data.put("frees", Map.of("bet",0,"bet_amount",0,"fw",0,"last_round_id_win",0,"level",0,
                "multiple",0,"surplus_times",0,"total_times",0,"total_win_amount",0));
            data.put("gid", 1780);
            data.put("level", 10);
            data.put("new_free", Map.of("new_times",0,"nums",0,"prop",12));
            data.put("odds", 0);
            data.put("oid", 0);
            data.put("prop_odds", paytable());
            data.put("props", List.of(core.stepJson(board, eval)));
            data.put("small_game_type", 0);
            data.put("start_gold", state.balance);
            data.put("total_win", 0);
            data.put("type", 1);
            state.last = data;
            return data;
        }
    }

    private Map<String,Object> configData(Map<String,String> form) {
        String language = form.getOrDefault("language", "en-us");
        var info = new LinkedHashMap<String,Object>();
        info.put("bet_gold", List.of(0.02, 0.1, 0.2));
        info.put("buy_free_max_bet", 10000);
        info.put("default_bet_gold", 0);
        info.put("default_level", 10);
        info.put("game_way", List.of(Map.of("max_bet_gold","0.00","min_bet_gold","0.00","way_id",178010000,"win_multi","1.00")));
        info.put("gid", 1780);
        info.put("least_gold", 0);
        info.put("name", "Glacier Treasure");
        info.put("status", "1");
        var cfg = new LinkedHashMap<String,Object>();
        cfg.put("bd_bet_count", 2);
        cfg.put("current_sys_time", Instant.now().getEpochSecond());
        cfg.put("is_debug", false);
        cfg.put("is_stopgs", 0);
        cfg.put("user_on_hook_time", 600);
        cfg.put("version", 1745909504);
        var ships = new LinkedHashMap<String,Object>();
        ships.put("770", "https://luckyairships.net/");
        ships.put("780", "https://luckyairships.net/");
        ships.put("790", "https://luckyairships.net/");
        ships.put("800", "https://luckyairships.net/");
        var gs = new LinkedHashMap<String,Object>();
        gs.put("gos_host", "");
        gs.put("gos_port", "8976");
        gs.put("gos_sport", "");
        gs.put("gs_host", "");
        gs.put("gs_host1", "");
        gs.put("gs_port", "");
        gs.put("gs_port1", "");
        gs.put("gs_push_host", "");
        gs.put("gs_push_port", "");
        gs.put("gs_push_sport", "28966");
        gs.put("gs_sport", "");
        gs.put("gs_sport1", "");
        gs.put("ngs_switch", 0);
        gs.put("ps_host", "");
        gs.put("ps_port", "");
        gs.put("snake_gs_host", "");
        gs.put("snake_gs_port", "");
        gs.put("snake_gs_sport", "");
        gs.put("snake_gs_url", "");
        var data = new LinkedHashMap<String,Object>();
        data.put("game_address", Map.of("ship_address_config", ships));
        data.put("game_info", info);
        data.put("game_server", gs);
        data.put("initial_config", cfg);
        data.put("language", language);
        data.put("r", 1);
        data.put("zone", 0);
        return data;
    }

    private Map<String,Object> userData(Session s) {
        var m=new LinkedHashMap<String,Object>();
        m.put("currency_symbol","R$"); m.put("day_first_login",0); m.put("first_gold",null);
        m.put("gid",1780); m.put("gold",s.balance); m.put("is_guide",0);
        m.put("nickname","1780-demo"); m.put("token",s.key); m.put("total_recharge","0");
        m.put("uid", 17800001); m.put("user_config", List.of());
        return m;
    }
    private Map<String,Object> balanceData(Session s) { return Map.of("gold", s.balance, "currency_symbol", "R$"); }
    private Map<String,Object> sessionData(Session s) {
        var m=new LinkedHashMap<String,Object>();
        m.put("gid",1780); m.put("active", true); m.put("gold", s.balance);
        m.put("rulesHash", GameRuleCore.RULES_HASH);
        if (s.pending!=null) { m.put("deliveryIndex", s.pending.index); m.put("deliveryCount", s.pending.deliveries.size()); }
        return m;
    }

    private Map<String,Object> historySummary(Session s) {
        synchronized (s) {
            long today = utcDay(Instant.now().getEpochSecond());
            Map<Long, BigDecimal[]> days = new LinkedHashMap<>();
            for (int i=0;i<7;i++) days.put(today-86400L*i, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            for (HistoryRow row:s.history) {
                long day = utcDay(row.createdAt);
                var acc = days.computeIfAbsent(day, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add((BigDecimal) row.deliveries.get(0).get("bet_gold"));
                for (var d:row.deliveries) acc[1] = acc[1].add((BigDecimal) d.get("change_gold"));
            }
            var list=new ArrayList<Object>();
            BigDecimal totalBet=BigDecimal.ZERO, totalChange=BigDecimal.ZERO;
            for (var e: days.entrySet()) {
                list.add(Map.of("bet_gold", e.getValue()[0], "change_gold", e.getValue()[1], "day", e.getKey()));
                totalBet=totalBet.add(e.getValue()[0]);
                totalChange=totalChange.add(e.getValue()[1]);
            }
            return Map.of("list", list, "statistics", Map.of("total_bet_gold", totalBet, "total_change_gold", totalChange));
        }
    }

    private Map<String,Object> historyDetail(Session s, Map<String,String> form) {
        int page = Integer.parseInt(form.getOrDefault("page","1"));
        int pageSize = Integer.parseInt(form.getOrDefault("page_size","30"));
        long requestedDay = 0;
        String rawDay = form.get("day");
        if (rawDay!=null && !rawDay.isBlank()) requestedDay = utcDay(Long.parseLong(rawDay));
        synchronized (s) {
            long filterDay = requestedDay;
            var ordered = new ArrayList<HistoryRow>();
            for (int i=s.history.size()-1;i>=0;i--) {
                HistoryRow row = s.history.get(i);
                if (filterDay==0 || utcDay(row.createdAt)==filterDay) ordered.add(row);
            }
            int from=Math.min(ordered.size(), Math.max(0,(page-1)*pageSize));
            int to=Math.min(ordered.size(), from+pageSize);
            var list=new ArrayList<Object>();
            for (HistoryRow row: ordered.subList(from,to)) list.add(historyRecord(row));
            return Map.of("list", list, "statistics", Map.of("total", ordered.size(), "page", page, "page_size", pageSize));
        }
    }

    private Map<String,Object> historyRecord(HistoryRow row) {
        long time = row.createdAt;
        long day = utcDay(time);
        Map<String,Object> first = row.deliveries.get(0);
        Map<String,Object> last = row.deliveries.get(row.deliveries.size()-1);
        BigDecimal change = BigDecimal.ZERO;
        var results = new ArrayList<Object>();
        for (var delivery : row.deliveries) {
            change = change.add((BigDecimal) delivery.get("change_gold"));
            results.add(historyDelivery(delivery, time, day));
        }
        Object oid = first.get("oid");
        var rec = new LinkedHashMap<String,Object>();
        rec.put("bet", first.get("bet"));
        rec.put("bet_gold", first.get("bet_gold"));
        rec.put("big_win", first.get("big_win"));
        rec.put("change_gold", change);
        rec.put("day", day);
        rec.put("end_gold", last.get("end_gold"));
        rec.put("extend", historyExtend());
        rec.put("frees", first.get("frees"));
        rec.put("gid", 1780);
        rec.put("level", first.get("level"));
        rec.put("new_free", first.get("new_free"));
        rec.put("odds", first.get("odds"));
        rec.put("oid", oid);
        rec.put("order_id", oid + "-1780");
        rec.put("result", first.get("props"));
        rec.put("results", results);
        rec.put("small_game_type", first.get("small_game_type"));
        rec.put("start_gold", first.get("start_gold"));
        rec.put("time", time);
        rec.put("total_win", first.get("total_win"));
        rec.put("type", first.get("type"));
        return rec;
    }

    private Map<String,Object> historyDelivery(Map<String,Object> spin, long time, long day) {
        var d = new LinkedHashMap<String,Object>(spin);
        d.put("bet", false);
        d.put("day", day);
        d.put("time", time);
        d.put("extend", historyExtend());
        d.put("order_id", String.valueOf(spin.get("oid")));
        Object boards = d.remove("props");
        d.put("result", boards);
        return d;
    }

    private static Map<String,Object> historyExtend() {
        var e = new LinkedHashMap<String,Object>();
        e.put("act_bet_gold", 0);
        e.put("act_id", 0);
        e.put("act_type", 0);
        return e;
    }

    private static long utcDay(long epochSecond) { return epochSecond/86400L*86400L; }

    private List<Object> paytable() {
        var table=new ArrayList<Object>();
        for (int prop=1;prop<=11;prop++) {
            var odds=new ArrayList<Object>();
            for (int n=3;n<=6;n++) odds.add(Map.of("num", n, "odds", core.odds(prop,n)));
            table.add(Map.of("prop_id", prop, "odds", odds));
        }
        return table;
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String rawPath = uri.getPath();
        if ("/".equals(rawPath)) rawPath = "/index.html";
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) { sendError(exchange, 404, "static file not found"); return; }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { sendEmpty(exchange, 200); return; }
        send(exchange, 200, body);
    }

    private Session session(HttpExchange exchange) { return session(exchange, query(exchange.getRequestURI().getRawQuery())); }
    private Session session(HttpExchange exchange, Map<String,String> values) {
        String key = firstNonBlank(values.get("token"), query(exchange.getRequestURI().getRawQuery()).get("token"),
            values.get("t"), "local-1780");
        return SESSIONS.computeIfAbsent(key, k -> new Session(k, initialBalance));
    }

    private static Map<String,String> params(HttpExchange exchange) throws IOException {
        var values = new LinkedHashMap<String,String>();
        values.putAll(query(exchange.getRequestURI().getRawQuery()));
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) values.putAll(form(exchange));
        return values;
    }
    private static Map<String,String> form(HttpExchange exchange) throws IOException {
        return query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> result=new LinkedHashMap<>();
        if (raw==null || raw.isBlank()) return result;
        for (String pair: raw.split("&")) {
            String[] parts=pair.split("=",2);
            result.put(decode(parts[0]), parts.length>1?decode(parts[1]):"");
        }
        return result;
    }
    private static String decode(String v) { return URLDecoder.decode(v, StandardCharsets.UTF_8); }
    private static String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
    private static String firstNonBlank(String... xs) {
        for (String x:xs) if (x!=null && !x.isBlank()) return x; return null;
    }
    private static BigDecimal decimal(String raw) { return new BigDecimal(raw); }

    private static Map<String,Object> ok(Object data) {
        var m=new LinkedHashMap<String,Object>();
        m.put("code", 0); m.put("data", data); m.put("msg", "success");
        m.put("time", Long.toString(Instant.now().getEpochSecond()));
        return m;
    }
    private static void sendJson(HttpExchange exchange, Object value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, 200, Json.stringify(value).getBytes(StandardCharsets.UTF_8));
    }
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        var body=new LinkedHashMap<String,Object>();
        body.put("code", status); body.put("msg", message); body.put("data", null);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status>=400?status:200, Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }
    private static void sendEmpty(HttpExchange exchange, int status) throws IOException { send(exchange, status, new byte[0]); }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        Headers h=exchange.getResponseHeaders();
        h.set("Cache-Control","no-store");
        h.set("Access-Control-Allow-Origin","*");
        h.set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers","Content-Type, Idempotency-Key, Authorization");
        exchange.sendResponseHeaders(status, body.length);
        if (body.length>0) exchange.getResponseBody().write(body);
    }
    private static String mime(Path file) {
        String n=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg")||n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ttf")||n.endsWith(".woff")) return "font/ttf";
        if (n.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private static Map<String,String> options(String[] args) {
        Map<String,String> r=new LinkedHashMap<>();
        for (int i=0;i<args.length;i+=2) {
            if (!args[i].startsWith("--") || i+1>=args.length) throw new IllegalArgumentException("options must be --name value");
            r.put(args[i].substring(2), args[i+1]);
        }
        return r;
    }
    private static int requiredPort(Map<String,String> options) {
        String raw=firstNonBlank(options.get("port"), System.getenv("PORT"), System.getenv("CPGAME_DEMO_PORT"));
        if (raw==null) throw new IllegalArgumentException("platform must inject --port in range 50000-59999");
        int port=Integer.parseInt(raw);
        if (port<50000 || port>59999) throw new IllegalArgumentException("port must be 50000-59999");
        return port;
    }
    private static Properties loadConfig(String path) throws IOException {
        Properties p=new Properties();
        if (path==null || path.isBlank()) return p;
        try (var in=Files.newBufferedReader(Path.of(path))) { p.load(in); }
        return p;
    }
    private static Path resolvePublish(String raw) {
        if (raw==null || raw.isBlank()) throw new IllegalArgumentException("--publish is required");
        Path p=Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(p) || !Files.isRegularFile(p.resolve("index.html")))
            throw new IllegalArgumentException("publish root must contain original index.html");
        return p;
    }

    private static final class Session {
        final String key; BigDecimal balance; Map<String,Object> last; Pending pending;
        final List<HistoryRow> history=new ArrayList<>();
        Session(String key, BigDecimal balance) { this.key=key; this.balance=balance; }
    }
    private static final class Pending {
        final List<Map<String,Object>> deliveries; int index; final String member;
        Pending(List<Map<String,Object>> deliveries, int index, String member) {
            this.deliveries=deliveries; this.index=index; this.member=member;
        }
    }
    private static final class HistoryRow {
        final long createdAt; final List<Map<String,Object>> deliveries;
        HistoryRow(long createdAt, List<Map<String,Object>> deliveries) { this.createdAt=createdAt; this.deliveries=deliveries; }
    }
}
