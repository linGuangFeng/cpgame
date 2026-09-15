package com.cpgame.hiddenrealm.server;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

public final class HiddenRealmController {
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final HiddenRealmProjector projector = new HiddenRealmProjector();
    private RedisRoundStore redis;
    private Path publish;

    public static void main(String[] args) throws Exception {
        new HiddenRealmController().start(load(args));
    }

    private void start(Properties p) throws Exception {
        if (!"3".equals(p.getProperty("controller.contract-version"))) throw new IllegalArgumentException("contract v3 required");
        int port = Integer.parseInt(required(p, "server.port"));
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        publish = Path.of(required(p, "publish.directory")).toAbsolutePath().normalize();
        redis = new RedisRoundStore(p);
        HttpServer server = HttpServer.create(new InetSocketAddress(p.getProperty("server.bind", "0.0.0.0"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(12));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); redis.close(); }));
        server.start();
        System.out.printf("HiddenRealm contract-v3 listening=%d rulesHash=%s publish=%s%n", port, GameRuleCore.RULES_HASH, publish);
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            cors(x);
            if ("OPTIONS".equals(x.getRequestMethod())) { x.sendResponseHeaders(204, -1); return; }
            String path = x.getRequestURI().getPath();
            if (path.equals("/healthz")) { send(x, 200, Map.of("status", "UP", "gameId", 1380, "rulesHash", GameRuleCore.RULES_HASH)); return; }
            if (path.equals("/api/report/timing") || path.equals("/api/report/timing/")) { send(x, 200, ok(Map.of())); return; }
            if (path.startsWith("/cp/")) { api(x, path.substring(3)); return; }
            staticFile(x, path);
        } catch (RedisRoundStore.CacheEmptyException e) {
            send(x, 503, Map.of("code", 50301, "msg", "PREGENERATED_CACHE_EMPTY", "detail", e.getMessage()));
        } catch (Exception e) {
            send(x, 500, Map.of("code", 50000, "msg", e.getClass().getSimpleName() + ":" + e.getMessage()));
        } finally {
            x.close();
        }
    }

    private void api(HttpExchange x, String path) throws IOException {
        Map<String, String> q = form(x);
        String token = q.getOrDefault("token", "demo");
        String language = normalizeLanguage(q.getOrDefault("language", "en"));
        SessionState s = sessions.computeIfAbsent(token, k -> new SessionState());
        switch (path) {
            case "/config/initialData" -> send(x, 200, ok(config(language)));
            case "/account/getUserInfo" -> send(x, 200, ok(user(token, s)));
            case "/activity/getActivity" -> send(x, 200, ok(Map.of("free", Map.of("act_list", List.of(), "invite_act_have", 0, "invite_end_time", 0))));
            case "/config/setGameConfig" -> send(x, 200, ok(Map.of()));
            case "/single_game.Game/initRoom" -> send(x, 200, ok(initRoom(s)));
            case "/single_game.Game/gameResult" -> spin(x, s, q);
            case "/goldgame/single_game_user_gold_history" -> send(x, 200, ok(historySummary(s)));
            case "/goldgame/single_game_user_history" -> send(x, 200, ok(historyDetail(s)));
            default -> send(x, 404, Map.of("code", 404, "msg", "UNKNOWN_API", "path", path));
        }
    }

    private void spin(HttpExchange x, SessionState s, Map<String, String> q) throws IOException {
        synchronized (s) {
            if (s.activeRound == null) {
                BigDecimal bet = new BigDecimal(q.getOrDefault("bet_gold", "0.05"));
                int level = Integer.parseInt(q.getOrDefault("level", "10"));
                if (bet.signum() <= 0 || level <= 0) throw new IllegalArgumentException("invalid bet");
                RedisRoundStore.Selection select = selection(q.getOrDefault("outcome", "any"));
                RedisRoundStore.Claim claim = redis.claim(select);
                s.activeRound = claim.round();
                s.activeMember = claim.member();
                s.deliveryIndex = 0;
                s.roundId = System.currentTimeMillis() * 100;
                s.betSize = bet;
                s.betLevel = level;
                s.activeResults.clear();
            }
            CompleteRound active = s.activeRound;
            Map<String, Object> data = projector.project(s, active, s.deliveryIndex);
            s.lastResult = deepCopy(data);
            s.activeResults.add(deepCopy(data));
            boolean terminal = s.deliveryIndex == active.deliveries().size() - 1;
            if (terminal) {
                s.history.addFirst(historyRecord(s, data));
                while (s.history.size() > 30) s.history.removeLast();
                s.activeRound = null;
                s.activeMember = null;
                s.deliveryIndex = 0;
                s.activeResults.clear();
            } else s.deliveryIndex++;
            send(x, 200, ok(data));
        }
    }

    private Map<String, Object> historyRecord(SessionState s, Map<String, Object> last) {
        Map<String, Object> first = s.activeResults.get(0);
        Map<String, Object> h = new LinkedHashMap<>(first);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> state : s.activeResults) {
            Map<String, Object> item = new LinkedHashMap<>(state);
            item.put("result", item.remove("props"));
            item.put("time", Instant.now().getEpochSecond());
            results.add(item);
        }
        h.put("day", LocalDate.now(ZoneOffset.UTC).atStartOfDay().toEpochSecond(ZoneOffset.UTC));
        h.put("order_id", first.get("oid") + "-1380");
        h.put("result", first.get("props"));
        h.put("results", results);
        h.put("time", Instant.now().getEpochSecond());
        h.put("change_gold", new BigDecimal(String.valueOf(last.get("end_gold"))).subtract(new BigDecimal(String.valueOf(first.get("start_gold")))));
        return h;
    }

    private Map<String, Object> historySummary(SessionState s) {
        BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
        for (Map<String, Object> h : s.history) {
            bet = bet.add(new BigDecimal(String.valueOf(h.get("bet_gold"))));
            change = change.add(new BigDecimal(String.valueOf(h.get("change_gold"))));
        }
        long day = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        return Map.of("list", List.of(Map.of("bet_gold", bet, "change_gold", change, "day", day)),
                "statistics", Map.of("total_bet_gold", bet, "total_change_gold", change));
    }

    private Map<String, Object> historyDetail(SessionState s) {
        BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
        for (Map<String, Object> h : s.history) {
            bet = bet.add(new BigDecimal(String.valueOf(h.get("bet_gold"))));
            change = change.add(new BigDecimal(String.valueOf(h.get("change_gold"))));
        }
        return Map.of("list", new ArrayList<>(s.history), "page", 1, "page_size", 30,
                "statistics", Map.of("total_bet_gold", bet, "total_change_gold", change));
    }

    private Map<String, Object> user(String token, SessionState s) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("currency_symbol", "R$");
        u.put("day_first_login", 0);
        u.put("gid", 1380);
        u.put("gold", s.balance);
        u.put("is_guide", 0);
        u.put("nickname", "demo1380");
        u.put("token", token);
        u.put("total_recharge", "0");
        u.put("uid", 13800001);
        u.put("user_config", List.of());
        return u;
    }

    private Map<String, Object> config(String language) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("bet_gold", List.of(0.05, 0.5, 4));
        info.put("buy_free_max_bet", -1);
        info.put("default_bet_gold", 0);
        info.put("default_level", 10);
        info.put("game_way", List.of(Map.of("max_bet_gold", "0.00", "min_bet_gold", "0.00", "way_id", 138010000, "win_multi", "1.00")));
        info.put("gid", 1380);
        info.put("least_gold", 0);
        info.put("name", "Hidden Realm");
        info.put("status", "1");
        return Map.of("game_address", Map.of("ship_address_config", Map.of()),
                "game_info", info, "game_server", Map.of(),
                "initial_config", Map.of("bd_bet_count", 2, "current_sys_time", Instant.now().getEpochSecond(), "is_debug", false, "is_stopgs", 0, "user_on_hook_time", 600, "version", 1745909504),
                "language", language, "r", 1, "zone", 0);
    }

    private Map<String, Object> initRoom(SessionState s) {
        if (s.lastResult != null) {
            Map<String, Object> restored = deepCopy(s.lastResult);
            restored.put("prop_odds", projector.paytable());
            return restored;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bet", s.betSize);
        data.put("bet_gold", 0);
        data.put("big_win", false);
        data.put("change_gold", 0);
        data.put("end_gold", s.balance);
        data.put("frees", Map.of("bet", s.betSize, "bet_amount", 0, "level", s.betLevel, "skill_type", 0, "total_num", 0, "total_skill_type", 0, "total_win_amount", 0));
        data.put("level", s.betLevel);
        data.put("odds", 0);
        data.put("oid", "0");
        data.put("prop_odds", projector.paytable());
        data.put("props", List.of(Map.of("props_value", idleBoard(), "total_amount", 0, "total_num", 0, "win_array", List.of(), "win_num", 0)));
        data.put("small_game_type", 0);
        data.put("start_gold", s.balance);
        data.put("total_num", 0);
        data.put("total_win", 0);
        data.put("type", 1);
        data.put("type_skill", 0);
        return data;
    }

    private List<List<Map<String, Object>>> idleBoard() {
        int[][] b = {{8, 2, 6, 8, 1}, {4, 5, 6, 1, 7}, {3, 8, 7, 7, 6}, {6, 7, 4, 4, 6}, {3, 5, 8, 3, 8}};
        List<List<Map<String, Object>>> cols = new ArrayList<>();
        for (int c = 0; c < 5; c++) {
            List<Map<String, Object>> col = new ArrayList<>();
            for (int r = 0; r < 5; r++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("is_win", 0);
                cell.put("prop", b[c][r]);
                col.add(cell);
            }
            cols.add(col);
        }
        return cols;
    }

    private RedisRoundStore.Selection selection(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "loss" -> RedisRoundStore.Selection.LOSS;
            case "win" -> RedisRoundStore.Selection.WIN;
            case "special", "free" -> RedisRoundStore.Selection.SPECIAL;
            default -> RedisRoundStore.Selection.ANY;
        };
    }

    private String normalizeLanguage(String s) {
        s = s.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "en-us" -> "en";
            case "pt-pt" -> "pt-br";
            default -> s;
        };
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 0);
        m.put("data", data);
        m.put("msg", "success");
        m.put("time", String.valueOf(Instant.now().getEpochSecond()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> m) {
        return json.convertValue(m, Map.class);
    }

    private void staticFile(HttpExchange x, String raw) throws IOException {
        String path = raw;
        if (path.startsWith("/play/1380-Hidden-Realm/")) path = path.substring("/play/1380-Hidden-Realm".length());
        else if (path.equals("/play/1380-Hidden-Realm")) path = "/";
        if (path.equals("/") || path.isEmpty()) {
            Path index = publish.resolve("index.html");
            if (Files.isRegularFile(index)) {
                byte[] body = Files.readAllBytes(index);
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, body.length);
                x.getResponseBody().write(body);
                return;
            }
        }
        if (path.startsWith("/v2/1380/")) path = path.substring("/v2/1380".length());
        else if (path.equals("/v2/reportv2.js") || path.equals("/reportv2.js")) {
            send(x, 200, Map.of("ok", true));
            return;
        } else if (path.startsWith("/v2/")) path = path.substring(3);
        if (path.equals("") || path.equals("/")) path = "/index.html";
        Path f = publish.resolve(path.substring(1)).normalize();
        if (!f.startsWith(publish) || !Files.isRegularFile(f)) {
            send(x, 404, Map.of("code", 404, "msg", "STATIC_NOT_FOUND", "path", raw));
            return;
        }
        byte[] body = Files.readAllBytes(f);
        x.getResponseHeaders().set("Content-Type", content(f));
        x.sendResponseHeaders(200, body.length);
        x.getResponseBody().write(body);
    }

    private String content(Path p) {
        String s = p.toString().toLowerCase(Locale.ROOT);
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".json")) return "application/json";
        if (s.endsWith(".css")) return "text/css";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".ttf")) return "font/ttf";
        if (s.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private Map<String, String> form(HttpExchange x) throws IOException {
        String raw = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank() && x.getRequestURI().getQuery() != null) raw = x.getRequestURI().getQuery();
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    private void send(HttpExchange x, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
    }

    private void cors(HttpExchange x) {
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    static Properties load(String[] args) throws IOException {
        Properties overrides = new Properties();
        Path config = Path.of("dist/controller.properties");
        boolean explicit = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--config") || arg.equals("--port") || arg.equals("--server.port") || arg.equals("--publish") || arg.equals("--bind")) {
                if (++i >= args.length) throw new IllegalArgumentException("missing value for " + arg);
                if (arg.equals("--config")) { config = Path.of(args[i]); explicit = true; }
                else if (arg.equals("--bind")) overrides.setProperty("server.bind", args[i]);
                else overrides.setProperty(arg.equals("--publish") ? "publish.directory" : "server.port", args[i]);
            } else if (arg.startsWith("--config=")) { config = Path.of(arg.substring(9)); explicit = true; }
            else if (arg.startsWith("--publish=")) overrides.setProperty("publish.directory", arg.substring(10));
            else if (arg.startsWith("--bind=")) overrides.setProperty("server.bind", arg.substring(7));
            else if (arg.startsWith("--port=") || arg.startsWith("--server.port="))
                overrides.setProperty("server.port", arg.substring(arg.indexOf('=') + 1));
            else if (!arg.startsWith("--") && !explicit) { config = Path.of(arg); explicit = true; }
            else throw new IllegalArgumentException("unsupported argument " + arg);
        }
        config = config.toAbsolutePath().normalize();
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(reader); }
        if (!overrides.containsKey("publish.directory")) {
            Path directory = Path.of(required(p, "publish.directory"));
            if (!directory.isAbsolute()) directory = config.getParent().resolve(directory);
            p.setProperty("publish.directory", directory.toAbsolutePath().normalize().toString());
        }
        String env = System.getenv("PORT");
        if (env != null && !env.isBlank()) p.setProperty("server.port", env);
        String injected = System.getenv("CPGAME_DEMO_PORT");
        if (injected != null && !injected.isBlank()) p.setProperty("server.port", injected);
        p.putAll(overrides);
        if (p.containsKey("publish.directory"))
            p.setProperty("publish.directory", Path.of(p.getProperty("publish.directory")).toAbsolutePath().normalize().toString());
        required(p, "server.port");
        return p;
    }

    private static String required(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + k);
        return v;
    }
}
