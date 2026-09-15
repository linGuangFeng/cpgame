package com.cpgame.fishinggo.server;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.ProtocolConstants;
import com.cpgame.fishinggo.core.ResultUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class FishingGoController {
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ResultUtil util = new ResultUtil();
    private final AtomicLong transfers = new AtomicLong(System.currentTimeMillis() << 8);
    private RedisRoundStore redis;
    private Path publish;

    public static void main(String[] args) throws Exception {
        new FishingGoController().start(load(args));
    }

    private void start(Properties p) throws Exception {
        if (!"3".equals(p.getProperty("controller.contract-version"))) throw new IllegalArgumentException("contract v3");
        int port = Integer.parseInt(required(p, "server.port"));
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port 50000-59999");
        publish = Path.of(required(p, "publish.directory")).toAbsolutePath().normalize();
        redis = new RedisRoundStore(p);
        HttpServer server = HttpServer.create(new InetSocketAddress(p.getProperty("server.bind", "0.0.0.0"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(12));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); redis.close(); }));
        server.start();
        System.out.printf("FishingGO contract-v3 listening=%d rulesHash=%s publish=%s%n",
                port, ProtocolConstants.RULES_HASH, publish);
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            cors(x);
            if ("OPTIONS".equals(x.getRequestMethod())) { x.sendResponseHeaders(204, -1); return; }
            String path = x.getRequestURI().getPath();
            if (path.equals("/healthz") || path.equals("/api/health")) {
                send(x, 200, Map.of("status", "UP", "gameId", 54, "rulesHash", ProtocolConstants.RULES_HASH));
                return;
            }
            if (path.equals("/api/report/timing") || path.equals("/api/v1/ping")) {
                send(x, 200, ok(Map.of("ts", Instant.now().getEpochSecond())));
                return;
            }
            if (path.startsWith("/cp/")) path = path.substring(3);
            if (path.startsWith("/api/")) { api(x, path); return; }
            staticFile(x, x.getRequestURI().getPath());
        } catch (RedisRoundStore.CacheEmptyException e) {
            send(x, 503, error(503, "PREGENERATED_CACHE_EMPTY"));
        } catch (IllegalArgumentException e) {
            send(x, 200, error(400, e.getMessage()));
        } catch (Exception e) {
            send(x, 500, error(500, e.getClass().getSimpleName() + ":" + e.getMessage()));
        } finally {
            x.close();
        }
    }

    private void api(HttpExchange x, String path) throws IOException {
        Map<String, String> q = form(x);
        String token = q.getOrDefault("t", q.getOrDefault("token", "demo"));
        SessionState s = sessions.computeIfAbsent(token, k -> new SessionState());
        switch (path) {
            case "/api/v1/auth/verify", "/api/v1/auth/session" -> send(x, 200, ok(auth(token, s)));
            case "/api/v1/go-fishing/config", "/api/v1/go-fishing/init" -> send(x, 200, ok(config(s)));
            case "/api/v1/go-fishing/spin" -> spin(x, s, q);
            case "/api/v1/go-fishing/log-list" -> send(x, 200, ok(historyList(s, q)));
            case "/api/v1/go-fishing/log-view" -> send(x, 200, ok(historyView(s, q)));
            case "/api/v1/go-fishing/balance" -> send(x, 200, ok(Map.of("cc", "BRL", "cs", "R$", "pb", money(s.balance))));
            case "/api/v1/go-fishing/session" -> send(x, 200, ok(Map.of("gid", 54, "pb", money(s.balance), "active", s.active != null)));
            default -> send(x, 404, error(404, "UNKNOWN_API"));
        }
    }

    private void spin(HttpExchange x, SessionState s, Map<String, String> q) throws IOException {
        synchronized (s) {
            if (s.active == null) {
                int bl = Integer.parseInt(q.getOrDefault("bl", "1"));
                BigDecimal bs = new BigDecimal(q.getOrDefault("bs", "0.02"));
                if (bl != 1 || bs.compareTo(ProtocolConstants.MIN_BET_SIZE) != 0)
                    throw new IllegalArgumentException("only bl=1 bs=0.02");
                RedisRoundStore.Selection sel = selection(q.getOrDefault("outcome", "any"), s);
                RedisRoundStore.Claim claim = redis.claim(sel);
                System.out.printf("FishingGO demo paid=%d cycle=%s odds=%d special=%s%n",
                        s.paidRounds, sel, claim.analysis().odds(), claim.round().special());
                s.paidRounds++;
                s.active = claim.round();
                s.deliveryIndex = 0;
                s.transferId = Long.toUnsignedString(transfers.incrementAndGet());
                s.lastCreatedAt = Instant.now().getEpochSecond();
                s.activeSteps.clear();
                s.balance = s.balance.subtract(ProtocolConstants.MIN_TOTAL_BET);
            }
            CompleteRound.Step step = s.active.steps().get(s.deliveryIndex);
            Map<String, Object> data = project(s, step);
            Map<String, Object> last = new LinkedHashMap<>(data);
            last.put("bl", 1);
            last.put("bs", ProtocolConstants.MIN_BET_SIZE);
            last.put("ca", s.lastCreatedAt);
            s.lastSpin = last;
            s.activeSteps.add(data);
            boolean terminal = step.terminal();
            s.deliveryIndex++;
            if (terminal) {
                s.balance = s.balance.add(s.active.steps().get(s.active.steps().size() - 1).rwa());
                s.history.addFirst(historyRecord(s, s.balance));
                while (s.history.size() > 30) s.history.removeLast();
                s.active = null;
                s.deliveryIndex = 0;
                s.activeSteps.clear();
            }
            send(x, 200, ok(data));
        }
    }

    private Map<String, Object> project(SessionState s, CompleteRound.Step step) {
        ResultUtil.Win win = util.evaluate(step.board(), step.rpx());
        boolean terminal = step.terminal();
        BigDecimal pb = terminal ? s.balance.add(s.active.steps().get(s.active.steps().size() - 1).rwa()) : s.balance;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apx", step.apx());
        data.put("ba", step.ba());
        data.put("fsn", step.fsn());
        data.put("gt", step.gt());
        data.put("nfsc", step.nfsc());
        data.put("pb", money(pb));
        data.put("rpx", step.rpx());
        data.put("rskl", step.board());
        data.put("rwa", step.rwa());
        data.put("small_game_type", step.smallGameType());
        data.put("ss", step.ss());
        data.put("wa", step.wa());
        data.put("wmkl", win.coords());
        data.put("wskl", win.symbols());
        return data;
    }

    private Map<String, Object> auth(String token, SessionState s) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("id", 54);
        player.put("balance", money(s.balance));
        return Map.of("player", player, "token", token, "ping", 30);
    }

    private Map<String, Object> config(SessionState s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auto", List.of(10, 30, 50, 100, 500));
        data.put("bll", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        data.put("bsl", List.of(new BigDecimal("0.02"), new BigDecimal("0.1"), new BigDecimal("0.5")));
        data.put("cc", "BRL");
        data.put("cs", "R$");
        data.put("dbl", new BigDecimal("0.5"));
        data.put("dbs", new BigDecimal("0.02"));
        data.put("last", s.lastSpin);
        data.put("ls", null);
        data.put("spl", ProtocolConstants.PAYTABLE);
        data.put("ts", Instant.now().getEpochSecond());
        return data;
    }

    private Map<String, Object> historyRecord(SessionState s, BigDecimal balanceAfter) {
        CompleteRound round = s.active;
        BigDecimal tw = round.steps().get(round.steps().size() - 1).rwa();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ba", ProtocolConstants.MIN_TOTAL_BET.stripTrailingZeros().toPlainString());
        rec.put("baf", money(balanceAfter));
        rec.put("bid", "54-" + s.transferId);
        rec.put("ca", s.lastCreatedAt);
        rec.put("fe", round.special() ? 1 : 0);
        rec.put("gm", null);
        rec.put("gt", 54);
        rec.put("tis", s.transferId);
        rec.put("wa", tw.stripTrailingZeros().toPlainString());
        rec.put("steps", new ArrayList<>(s.activeSteps));
        rec.put("transfer_id", s.transferId);
        return rec;
    }

    private Map<String, Object> historyList(SessionState s, Map<String, String> q) {
        int page = Integer.parseInt(q.getOrDefault("page_index", "1"));
        List<Map<String, Object>> all = new ArrayList<>(s.history);
        int from = Math.min(Math.max(page - 1, 0) * 10, all.size());
        int to = Math.min(from + 10, all.size());
        BigDecimal bet = BigDecimal.ZERO, win = BigDecimal.ZERO;
        for (Map<String, Object> h : all) {
            bet = bet.add(new BigDecimal(String.valueOf(h.get("ba"))));
            win = win.add(new BigDecimal(String.valueOf(h.get("wa"))));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", money(bet));
        data.put("end", to >= all.size() ? 1 : 0);
        data.put("lc", all.size());
        data.put("ll", all.subList(from, to));
        data.put("wa", money(win));
        return data;
    }

    private Map<String, Object> historyView(SessionState s, Map<String, String> q) {
        String id = q.get("transfer_id");
        Map<String, Object> item = s.history.stream().filter(h -> id.equals(h.get("tis"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("History not found"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) item.get("steps");
        String bid = String.valueOf(item.get("bid"));
        long ca = ((Number) item.get("ca")).longValue();
        List<Map<String, Object>> detail = new ArrayList<>();
        if (steps != null) for (Map<String, Object> step : steps) detail.add(historyStep(step, bid, ca));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baf", new BigDecimal(String.valueOf(item.get("baf"))));
        data.put("bid", bid);
        data.put("bsl", detail.isEmpty() ? List.of() : List.of(detail.get(0)));
        if (detail.size() > 1) data.put("fsl", detail.subList(1, detail.size()));
        return data;
    }

    private Map<String, Object> historyStep(Map<String, Object> spin, String bid, long ca) {
        Map<String, Object> data = new LinkedHashMap<>(spin);
        data.put("bid", bid);
        data.put("bl", 1);
        data.put("bs", "0.02");
        data.put("ca", ca);
        data.put("ba", Integer.valueOf(1).equals(spin.get("gt")) ? "0.40" : 0);
        @SuppressWarnings("unchecked")
        List<String> board = (List<String>) spin.get("rskl");
        int rpx = ((Number) spin.get("rpx")).intValue();
        ResultUtil.Win win = util.evaluate(board, rpx);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < win.symbols().size(); i++) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("sk", win.symbols().get(i));
            match.put("wa", util.symbolPayout(win.symbols().get(i), win.coords().get(i), rpx)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString());
            match.put("wmk", win.coords().get(i));
            matches.add(match);
        }
        data.put("wmkl", matches);
        return data;
    }

    private RedisRoundStore.Selection selection(String raw, SessionState s) {
        RedisRoundStore.Selection forced = DemoCycle.parse(raw);
        return forced != null ? forced : DemoCycle.at(s.paidRounds);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 200);
        m.put("data", data);
        m.put("info", "ok");
        return m;
    }
    private Map<String, Object> error(int code, String info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("data", Map.of());
        m.put("info", info);
        return m;
    }
    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void staticFile(HttpExchange x, String raw) throws IOException {
        String path = raw;
        if (path.startsWith("/play/54-Fishing-GO")) path = path.substring("/play/54-Fishing-GO".length());
        if (path.startsWith("/54/")) path = path.substring(3);
        if (path.equals("/54")) path = "/";
        if (path.equals("") || path.equals("/")) path = "/index.html";
        Path f = publish.resolve(path.substring(1)).normalize();
        if (!f.startsWith(publish) || !Files.isRegularFile(f)) {
            send(x, 404, error(404, "STATIC_NOT_FOUND"));
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
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".ttf")) return "font/ttf";
        if (s.endsWith(".bin")) return "application/octet-stream";
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
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key, X-Idempotency-Key");
        x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    static Properties load(String[] args) throws IOException {
        Properties overrides = new Properties();
        Path config = Path.of("dist/controller.properties");
        boolean explicit = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--config") || arg.equals("--port") || arg.equals("--server.port") || arg.equals("--publish") || arg.equals("--bind")) {
                if (++i >= args.length) throw new IllegalArgumentException("missing " + arg);
                if (arg.equals("--config")) { config = Path.of(args[i]); explicit = true; }
                else if (arg.equals("--bind")) overrides.setProperty("server.bind", args[i]);
                else overrides.setProperty(arg.equals("--publish") ? "publish.directory" : "server.port", args[i]);
            } else if (arg.startsWith("--config=")) { config = Path.of(arg.substring(9)); explicit = true; }
            else if (arg.startsWith("--publish=")) overrides.setProperty("publish.directory", arg.substring(10));
            else if (arg.startsWith("--bind=")) overrides.setProperty("server.bind", arg.substring(7));
            else if (arg.startsWith("--port=") || arg.startsWith("--server.port="))
                overrides.setProperty("server.port", arg.substring(arg.indexOf('=') + 1));
            else if (arg.startsWith("--spring.config.additional-location")) {
                if (++i < args.length) { config = Path.of(args[i].replace("file:", "")); explicit = true; }
            } else if (!arg.startsWith("--") && !explicit) { config = Path.of(arg); explicit = true; }
        }
        config = config.toAbsolutePath().normalize();
        Properties p = new Properties();
        if (Files.isRegularFile(config)) {
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(reader); }
        }
        if (!overrides.containsKey("publish.directory") && p.containsKey("publish.directory")) {
            Path directory = Path.of(p.getProperty("publish.directory"));
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
