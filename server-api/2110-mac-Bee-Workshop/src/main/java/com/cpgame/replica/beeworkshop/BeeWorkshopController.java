package com.cpgame.replica.beeworkshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

/** Controller v3: static original page + API on one injected 5xxxx port. Results come only from Redis. */
public final class BeeWorkshopController {
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ProtocolProjection projection = new ProtocolProjection();
    private RedisRoundRepository redis;
    private Path publish;

    public static void main(String[] args) throws Exception {
        Properties p = load(args);
        new BeeWorkshopController().start(p);
    }

    private void start(Properties p) throws Exception {
        if (!"3".equals(p.getProperty("controller.contract-version"))) throw new IllegalArgumentException("contract v3 required");
        int port = Integer.parseInt(p.getProperty("server.port"));
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be dynamic 5xxxx");
        Path configured = Path.of(p.getProperty("publish.directory"));
        publish = (configured.isAbsolute() ? configured : Path.of(p.getProperty("config.base-directory")).resolve(configured)).toAbsolutePath().normalize();
        redis = new RedisRoundRepository(p);
        HttpServer server = HttpServer.create(new InetSocketAddress(p.getProperty("server.bind", "0.0.0.0"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(12));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (Exception ignored) {}
        }));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=2110 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s%n",
                port, ProcessHandle.current().pid(), BeeWorkshopRulesMetadata.VERSION, BeeWorkshopRulesMetadata.HASH, publish);
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            cors(x);
            if ("OPTIONS".equals(x.getRequestMethod())) { x.sendResponseHeaders(204, -1); return; }
            String path = x.getRequestURI().getPath();
            if (path.equals("/health")) {
                send(x, 200, Map.of("status", "UP", "gameId", 2110, "contractVersion", 3, "redisDatabase", 15, "rulesHash", GameRuleCore.RULES_HASH));
                return;
            }
            if (path.equals("/api/balance") || path.equals("/api/session")) {
                Map<String, String> q = query(x.getRequestURI().getRawQuery());
                SessionState s = sessions.computeIfAbsent(q.getOrDefault("token", "demo"), k -> new SessionState());
                send(x, 200, path.endsWith("balance") ? Map.of("balance", s.balance) : Map.of("balance", s.balance, "activeRound", s.active != null, "nextStep", s.step));
                return;
            }
            if (path.startsWith("/cp/")) { api(x, path.substring(3)); return; }
            staticFile(x, path);
        } catch (RedisRoundRepository.CacheEmptyException e) {
            send(x, 503, Map.of("code", 50301, "msg", "PREGENERATED_CACHE_EMPTY", "detail", e.getMessage()));
        } catch (Exception e) {
            send(x, 500, Map.of("code", 50000, "msg", e.getClass().getSimpleName() + ":" + e.getMessage()));
        } finally {
            x.close();
        }
    }

    private void api(HttpExchange x, String path) throws Exception {
        Map<String, String> q = form(x);
        String token = q.getOrDefault("token", "demo");
        SessionState s = sessions.computeIfAbsent(token, k -> new SessionState());
        switch (path) {
            case "/config/initialData" -> send(x, 200, ok(config()));
            case "/account/getUserInfo" -> send(x, 200, ok(user(token, s)));
            case "/activity/getActivity" -> send(x, 200, ok(Map.of("free", Map.of("act_list", List.of(), "invite_act_have", 0, "invite_end_time", 0))));
            case "/config/setGameConfig" -> send(x, 200, ok(Map.of()));
            case "/single_game.Game/initRoom" -> send(x, 200, ok(initRoom(s)));
            case "/single_game.Game/gameResult" -> spin(x, s, q);
            case "/goldgame/single_game_user_gold_history" -> send(x, 200, ok(dayHistory(s)));
            case "/goldgame/single_game_user_history" -> {
                try { send(x, 200, ok(history(s, q))); }
                catch (IllegalArgumentException invalid) { send(x, 400, Map.of("code", 40002, "msg", "INVALID_HISTORY_QUERY")); }
            }
            default -> send(x, 404, Map.of("code", 404, "msg", "UNKNOWN_API", "path", path));
        }
    }

    private void spin(HttpExchange x, SessionState s, Map<String, String> q) throws Exception {
        synchronized (s) {
            if (s.active == null) {
                String requested = q.get("playthrough_kind");
                if (requested != null && !q.getOrDefault("token", "").startsWith("playthrough-")) {
                    send(x, 403, Map.of("code", 40301, "msg", "PLAYTHROUGH_TOKEN_REQUIRED"));
                    return;
                }
                double bet;
                int level;
                try {
                    bet = Double.parseDouble(q.getOrDefault("bet_gold", "0.02"));
                    level = Integer.parseInt(q.getOrDefault("level", "10"));
                } catch (NumberFormatException invalid) {
                    send(x, 400, Map.of("code", 40001, "msg", "INVALID_BET"));
                    return;
                }
                double wager = new ResultUtil(new GameRuleCore()).totalBet(bet, level);
                if (!Double.isFinite(bet) || bet <= 0 || level <= 0 || !Double.isFinite(wager) || wager <= 0) {
                    send(x, 400, Map.of("code", 40001, "msg", "INVALID_BET"));
                    return;
                }
                if (wager > s.balance) {
                    send(x, 409, Map.of("code", 40901, "msg", "INSUFFICIENT_BALANCE"));
                    return;
                }
                s.active = redis.claim(requested);
                s.roundBet = bet;
                s.roundLevel = level;
                s.step = 0;
                s.roundId = System.currentTimeMillis() * 1000 + new Random().nextInt(1000);
                s.cumulativeFreeWin = 0;
                s.currentResults.clear();
            }
            Map<String, Object> data = projection.project(s, s.step, s.roundBet, s.roundLevel);
            s.lastResponse = new LinkedHashMap<>(data);
            s.currentResults.add(historyStep(data, s.roundId));
            boolean terminal = s.step == s.active.round().steps().size() - 1;
            if (terminal) {
                Map<String, Object> first = s.currentResults.get(0);
                double change = s.currentResults.stream().mapToDouble(v -> ((Number) v.get("change_gold")).doubleValue()).sum();
                Map<String, Object> entry = new LinkedHashMap<>(data);
                entry.put("bet_gold", first.get("bet_gold"));
                entry.put("change_gold", Math.round(change * 100.0) / 100.0);
                entry.put("start_gold", first.get("start_gold"));
                entry.put("order_id", s.roundId + "-2110");
                entry.put("time", Instant.now().getEpochSecond());
                entry.put("result", data.get("props"));
                entry.put("results", List.copyOf(s.currentResults));
                entry.put("extend", historyExtend());
                entry.remove("props");
                s.history.addFirst(entry);
                s.active = null;
                s.step = 0;
                s.currentResults.clear();
            } else s.step++;
            send(x, 200, ok(data));
        }
    }

    private Map<String, Object> historyStep(Map<String, Object> data, long order) {
        Map<String, Object> step = new LinkedHashMap<>(data);
        if (((Number) data.get("type")).intValue() == 1) step.put("bet", false);
        step.put("order_id", String.valueOf(order));
        step.put("result", step.remove("props"));
        step.put("extend", historyExtend());
        return step;
    }

    /** Origin history rows always include this object; Game2110GameDetailView crashes without it. act_id must be the string "0". */
    private static Map<String, Object> historyExtend() {
        Map<String, Object> extend = new LinkedHashMap<>();
        extend.put("act_bet_gold", 0);
        extend.put("act_id", "0");
        extend.put("act_type", 0);
        return extend;
    }

    private Map<String, Object> initRoom(SessionState s) {
        if (s.active != null && s.lastResponse != null) return new LinkedHashMap<>(s.lastResponse);
        int[] board = {5, 6, 6, 1, 1, 5, 8, 8, 7, 7, 3, 3, 7, 2, 4};
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("bet", .02);
        d.put("bet_gold", 0);
        d.put("bg", 0);
        d.put("cg", 0);
        d.put("change_gold", 0);
        d.put("end_gold", s.balance);
        d.put("frees", Map.of("ba", 0, "bet", 0, "fw", 0, "l", 0, "st", 0, "tt", 0, "twa", 0));
        d.put("level", 10);
        d.put("odds", 0);
        d.put("oid", String.valueOf(System.currentTimeMillis() * 1000));
        d.put("props", Map.of("frees_prop", 0, "prop", Arrays.stream(board).boxed().toList(), "tw", 0, "win_arr", List.of()));
        d.put("small_game_type", 0);
        d.put("spe_pos", List.of());
        d.put("start_gold", s.balance);
        d.put("total_win", 0);
        d.put("type", 1);
        return d;
    }

    private Map<String, Object> config() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("bet_gold", List.of(.02, .2, 2));
        game.put("buy_free_max_bet", -1);
        game.put("default_bet_gold", 0);
        game.put("default_level", 10);
        game.put("game_way", List.of(Map.of("max_bet_gold", "0.00", "min_bet_gold", "0.00", "way_id", 211010000, "win_multi", "1.00")));
        game.put("gid", 2110);
        game.put("least_gold", 0);
        game.put("name", "Magic Scroll");
        game.put("status", "1");
        Map<String, Object> gs = new LinkedHashMap<>();
        for (String key : List.of("gos_host", "gos_port", "gos_sport", "gs_host", "gs_host1", "gs_port", "gs_port1", "gs_push_host", "gs_push_port", "gs_push_sport", "gs_sport", "gs_sport1", "ps_host", "ps_port", "snake_gs_host", "snake_gs_port", "snake_gs_sport", "snake_gs_url"))
            gs.put(key, "");
        gs.put("ngs_switch", 0);
        return Map.of("game_address", Map.of("ship_address_config", Map.of()), "game_info", game, "game_server", gs,
                "initial_config", Map.of("bd_bet_count", 2, "current_sys_time", now, "is_debug", false, "is_stopgs", 0, "user_on_hook_time", 600, "version", 1745909504),
                "language", "en-us", "r", 1, "zone", 0);
    }

    private Map<String, Object> user(String token, SessionState s) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("currency_symbol", "R$");
        u.put("day_first_login", 0);
        u.put("first_gold", null);
        u.put("gid", 2110);
        u.put("gold", s.balance);
        u.put("is_guide", 0);
        u.put("nickname", "BeeTester");
        u.put("token", token);
        u.put("total_recharge", "0");
        u.put("uid", 21100001);
        u.put("user_config", List.of());
        return u;
    }

    private Map<String, Object> history(SessionState s, Map<String, String> q) {
        long day = q.getOrDefault("day", "").isBlank() ? utcDay(Instant.now().getEpochSecond()) : utcDay(Long.parseLong(q.get("day")));
        int page = Integer.parseInt(q.getOrDefault("page", "1")), size = Integer.parseInt(q.getOrDefault("page_size", "30"));
        if (page < 1 || size < 1) throw new IllegalArgumentException("history page and page_size must be positive");
        synchronized (s) {
            List<Map<String, Object>> all = s.history.stream().filter(e -> utcDay(((Number) e.get("time")).longValue()) == day).toList();
            long offset = (long) (page - 1) * size;
            int from = (int) Math.min(offset, all.size()), to = (int) Math.min(offset + size, all.size());
            return Map.of("list", List.copyOf(all.subList(from, to)), "statistics", historyStatistics(all));
        }
    }

    private Map<String, Object> dayHistory(SessionState s) {
        long today = utcDay(Instant.now().getEpochSecond());
        List<Map<String, Object>> days = new ArrayList<>();
        double totalBet = 0, totalChange = 0;
        synchronized (s) {
            for (int offset = 0; offset < 7; offset++) {
                long day = today - offset * 86400L;
                List<Map<String, Object>> rows = s.history.stream().filter(e -> utcDay(((Number) e.get("time")).longValue()) == day).toList();
                Map<String, Object> stats = historyStatistics(rows);
                double bet = ((Number) stats.get("total_bet_gold")).doubleValue(), change = ((Number) stats.get("total_change_gold")).doubleValue();
                days.add(Map.of("bet_gold", bet, "change_gold", change, "day", day));
                totalBet += bet;
                totalChange += change;
            }
        }
        return Map.of("list", days, "statistics", Map.of("total_bet_gold", money(totalBet), "total_change_gold", money(totalChange)));
    }

    private Map<String, Object> historyStatistics(List<Map<String, Object>> rows) {
        double bet = 0, change = 0;
        for (var row : rows) {
            bet += ((Number) row.getOrDefault("bet_gold", 0)).doubleValue();
            change += ((Number) row.getOrDefault("change_gold", 0)).doubleValue();
        }
        return Map.of("total_bet_gold", money(bet), "total_change_gold", money(change));
    }

    private static long utcDay(long epochSeconds) { return Math.floorDiv(epochSeconds, 86400) * 86400; }
    private static double money(double amount) { return Math.round(amount * 100.0) / 100.0; }
    private Map<String, Object> ok(Object data) { return Map.of("code", 0, "data", data, "msg", "success", "time", String.valueOf(Instant.now().getEpochSecond())); }

    private void staticFile(HttpExchange x, String raw) throws IOException {
        if (raw.equals("/")) {
            String host = x.getRequestHeaders().getFirst("Host");
            x.getResponseHeaders().set("Location", "/fixed/?ai=luck_single_10229&gid=2110&l=pt&language=pt-br&token=demo&sip=" + host);
            x.sendResponseHeaders(302, -1);
            return;
        }
        if (raw.equals("/favicon.ico")) { x.sendResponseHeaders(204, -1); return; }
        String path = raw.equals("/fixed/") ? "/index.html" : raw.startsWith("/fixed/") ? raw.substring(6) : raw;
        if (path.contains("?")) path = path.substring(0, path.indexOf('?'));
        Path file = publish.resolve(path.substring(1)).normalize();
        if (!file.startsWith(publish) || !Files.isRegularFile(file)) {
            send(x, 404, Map.of("code", 404, "msg", "STATIC_NOT_FOUND", "path", raw));
            return;
        }
        byte[] body = Files.readAllBytes(file);
        x.getResponseHeaders().set("Content-Type", content(file));
        x.sendResponseHeaders(200, body.length);
        x.getResponseBody().write(body);
    }

    private String content(Path p) {
        String s = p.toString().toLowerCase(Locale.ROOT);
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".json")) return "application/json";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".ttf")) return "font/ttf";
        if (s.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private Map<String, String> form(HttpExchange x) throws IOException {
        String raw = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    private Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    private void send(HttpExchange x, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
    }

    private void cors(HttpExchange x) {
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        x.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
    }

    static Properties load(String[] args) throws IOException {
        Properties overrides = new Properties();
        String file = "controller.properties";
        boolean explicit = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--config") || a.equals("--port") || a.equals("--publish")) {
                if (++i >= args.length) throw new IllegalArgumentException("missing value for " + a);
                if (a.equals("--config")) { file = args[i]; explicit = true; }
                else if (a.equals("--port")) overrides.setProperty("server.port", args[i]);
                else overrides.setProperty("publish.directory", Path.of(args[i]).toAbsolutePath().normalize().toString());
            } else if (a.startsWith("--config=")) { file = a.substring(9); explicit = true; }
            else if (a.startsWith("--port=")) overrides.setProperty("server.port", a.substring(7));
            else if (a.startsWith("--publish=")) overrides.setProperty("publish.directory", Path.of(a.substring(10)).toAbsolutePath().normalize().toString());
            else if (a.matches("\\d{5}")) overrides.setProperty("server.port", a);
            else if (a.startsWith("--") && a.contains("=")) {
                String[] kv = a.substring(2).split("=", 2);
                overrides.setProperty(kv[0], kv[1]);
            } else if (!a.startsWith("--") && !explicit) { file = a; explicit = true; }
            else throw new IllegalArgumentException("unsupported argument " + a);
        }
        Path config = Path.of(file);
        if (!explicit) {
            try {
                Path code = Path.of(BeeWorkshopController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path base = Files.isDirectory(code) ? code : code.getParent();
                config = base.resolve(file);
            } catch (Exception failure) {
                throw new IOException("cannot locate controller config", failure);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(r); }
        p.setProperty("config.base-directory", config.toAbsolutePath().normalize().getParent().toString());
        String port = System.getenv("PORT");
        if (port != null && !port.isBlank()) p.setProperty("server.port", port);
        p.putAll(overrides);
        if (p.getProperty("server.port", "").isBlank()) throw new IllegalArgumentException("platform must supply --port or PORT (50000-59999)");
        int selected = Integer.parseInt(p.getProperty("server.port"));
        if (selected < 50000 || selected > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        return p;
    }
}
