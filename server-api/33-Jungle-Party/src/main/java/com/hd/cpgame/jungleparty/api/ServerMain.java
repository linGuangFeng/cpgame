package com.hd.cpgame.jungleparty.api;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.hd.cpgame.jungleparty.GameRuleCore;
import com.hd.cpgame.jungleparty.IndependentVerifier;
import com.hd.cpgame.jungleparty.MemberCodec;
import com.hd.cpgame.jungleparty.RedisRoundWriter;
import com.hd.cpgame.jungleparty.RedisKeyContract;
import com.hd.cpgame.jungleparty.ResultUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Evidence-derived raw gid 33 controller. Static assets and API share one managed Java process. */
public final class ServerMain {
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000L);
    private static ControllerState state;
    private static Path publishRoot;
    private static Path stateFile;
    private static Properties config;

    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Map<String,String> options = options(args);
        int port = requiredPort(options);
        config = load(Path.of(options.getOrDefault("config", "controller.properties")));
        if (!"33".equals(config.getProperty("game.rawId"))) throw new IllegalArgumentException("game.rawId must be raw 33");
        publishRoot = Path.of(options.getOrDefault("publish", config.getProperty("publish.directory", "../../../publish/33-Jungle-Party"))).toAbsolutePath().normalize();
        if (!Files.isRegularFile(publishRoot.resolve("index.html"))) throw new IllegalArgumentException("publish/index.html not found: " + publishRoot);
        stateFile = Path.of(options.getOrDefault("state", config.getProperty("state.file", "controller-state.bin"))).toAbsolutePath().normalize();
        state = loadState();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", e -> json(e, 200, "{\"status\":\"UP\",\"rawGameId\":33,\"rulesHash\":" + quote(GameRuleCore.RULES_HASH) + ",\"processMode\":\"managed-process\"}"));
        server.createContext("/api/balance", ServerMain::balance);
        server.createContext("/api/session", ServerMain::sessionInfo);
        server.createContext("/api/report/timing", ServerMain::timingReport);
        server.createContext("/cp/api/v1/auth/verify", ServerMain::verify);
        server.createContext("/cp/api/v1/auth/session", ServerMain::verify);
        server.createContext("/cp/api/v1/ping", e -> json(e, 200, ok("{\"enable\":0,\"seconds\":0}")));
        server.createContext("/cp/api/v1/jungle-party/config", ServerMain::gameConfig);
        server.createContext("/cp/api/v1/jungle-party/spin", ServerMain::spin);
        server.createContext("/cp/api/v1/jungle-party/log-list", ServerMain::historyList);
        server.createContext("/cp/api/v1/jungle-party/log-view", ServerMain::historyView);
        server.createContext("/", ServerMain::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "jungle-party-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY rawGameId=33 port=%d pid=%d rulesHash=%s publish=%s%n", port, ProcessHandle.current().pid(), GameRuleCore.RULES_HASH, publishRoot);
    }

    private static void verify(HttpExchange e) throws IOException {
        if (!post(e)) return; Map<String,String> form = form(e);
        synchronized (LOCK) {
            String token = sessionKey(e, form); SessionState s = session(token);
            String fcg = s.active == null ? "null" : "{\"fbl\":" + s.active.round.betLevel() + ",\"fbs\":" + s.active.round.betSize() + "}";
            String player = "{\"balance\":" + quote(money(s.balance)) + ",\"id\":33,\"fbt\":" + remaining(s) + ",\"fcg\":" + fcg + "}";
            json(e, 200, ok("{\"player\":" + player + ",\"token\":" + quote(token) + ",\"ping\":{\"enable\":0,\"seconds\":0},\"rc\":{\"on\":0,\"v\":2},\"gc\":{\"os\":1,\"om\":1}}"));
        }
    }

    private static void gameConfig(HttpExchange e) throws IOException {
        if (!post(e)) return; Map<String,String> form = form(e);
        synchronized (LOCK) {
            SessionState s = session(sessionKey(e, form)); String last = s.lastDelivery == null ? "null" : s.lastDelivery;
            json(e, 200, ok("{\"auto\":[10,30,50,100,500],\"bll\":[1,2,3,4,5,6,7,8,9,10],\"bsl\":[0.02,0.12,0.8],\"cc\":\"BRL\",\"cs\":\"R$\",\"dbl\":0.8,\"dbs\":0.02,\"fbt\":" + remaining(s) + ",\"fbl\":1,\"fbs\":0.02,\"last\":" + last + ",\"spl\":" + paytableJson() + ",\"ts\":" + Instant.now().getEpochSecond() + "}"));
        }
    }

    private static void spin(HttpExchange e) throws IOException {
        if (!post(e)) return; Map<String,String> form = form(e);
        if (!"33".equals(form.getOrDefault("gid", "33"))) { json(e, 400, error("raw gid must be 33")); return; }
        try { synchronized (LOCK) {
            SessionState s = session(sessionKey(e, form));
            String idem = firstNonBlank(e.getRequestHeaders().getFirst("Idempotency-Key"), form.get("idempotency_key"), form.get("request_id"));
            if (idem != null && s.idempotentResponses.containsKey(idem)) { json(e, 200, s.idempotentResponses.get(idem)); return; }
            if (s.active == null) {
                int level = parseLevel(form.getOrDefault("bl", "1")); BigDecimal size = parseBetSize(form.getOrDefault("bs", "0.02"));
                GameRuleCore.Round round = claimRound(scenario(form.get("scenario")), level, size);
                IndependentVerifier.Verification check = IndependentVerifier.verify(round);
                if (!check.pass()) throw new IllegalStateException("runtime Round failed independent verification: " + check.errors());
                if (s.balance.compareTo(round.paidBet()) < 0) { json(e, 409, error("insufficient local balance")); return; }
                s.balance = s.balance.subtract(round.paidBet());
                s.active = new ActiveRound(Long.toUnsignedString(IDS.incrementAndGet()), round, 0, s.balance, new ArrayList<>());
            }
            ActiveRound a = s.active; GameRuleCore.Delivery d = a.round.deliveries().get(a.nextIndex);
            BigDecimal shownBalance = d.terminal() ? s.balance.add(a.round.totalAward()) : s.balance;
            String detail = historyStep(a.transferId, a.round, d, shownBalance); a.steps.add(detail);
            String response = ok(deliveryJson(a.transferId, a.round, d, shownBalance)); s.lastDelivery = detail;
            if (d.terminal()) {
                s.balance = shownBalance; s.history.addFirst(new History(a.transferId, Instant.now().getEpochSecond(), a.round, a.balanceAfterBet, s.balance, List.copyOf(a.steps)));
                while (s.history.size() > 200) s.history.removeLast(); s.active = null;
            } else a.nextIndex++;
            if (idem != null) { s.idempotentResponses.put(idem, response); while (s.idempotentResponses.size() > 200) s.idempotentResponses.remove(s.idempotentResponses.keySet().iterator().next()); }
            saveState(); json(e, 200, response);
        }} catch (IllegalArgumentException ex) { json(e, 400, error(ex.getMessage())); }
        catch (Exception ex) { json(e, 503, error(ex.getMessage())); }
    }

    private static GameRuleCore.Round claimRound(GameRuleCore.Scenario requested, int level, BigDecimal size) throws Exception {
        if (!"redis".equalsIgnoreCase(config.getProperty("result.source", "redis"))) throw new IllegalStateException("runtime generation is disabled; result.source must be redis");
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8000033"));
        if (gameId <= 0) throw new IllegalStateException("redis.game-id must be positive");
        try (RedisRoundWriter redis = new RedisRoundWriter(requiredConfig("redis.host"), Integer.parseInt(requiredConfig("redis.port")),
            Integer.parseInt(requiredConfig("redis.connect-timeout-ms")), Integer.parseInt(requiredConfig("redis.socket-timeout-ms")),
            Boolean.parseBoolean(requiredConfig("redis.ssl")))) {
            redis.auth(config.getProperty("redis.username", ""), config.getProperty("redis.password", ""));
            redis.select(Integer.parseInt(requiredConfig("redis.database")));
            boolean explicit = requested == GameRuleCore.Scenario.ORDINARY_LOSS
                    || requested == GameRuleCore.Scenario.ORDINARY_WIN
                    || requested == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS;
            boolean firstWin = requested != GameRuleCore.Scenario.ORDINARY_LOSS
                    && (explicit || RANDOM.nextBoolean());
            for (int side = 0; side < (explicit ? 1 : 2); side++) {
                boolean wantWin = side == 0 ? firstWin : !firstWin;
                boolean firstSpecial = wantWin && (requested == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS
                        || (!explicit && RANDOM.nextBoolean()));
                boolean[] pools = explicit || !wantWin ? new boolean[]{firstSpecial}
                        : new boolean[]{firstSpecial, !firstSpecial};
                for (boolean special : pools) {
                    var buckets = RedisFloorLookup.open(redis::command,
                            special ? RedisKeyContract.specialIndex(gameId) : RedisKeyContract.normalIndex(gameId),
                            m -> special ? RedisKeyContract.specialList(gameId, m) : RedisKeyContract.normalList(gameId, m),
                            RANDOM, wantWin ? 1 : 0, wantWin ? Integer.MAX_VALUE : 0);
                    Integer multiplier;
                    while ((multiplier = buckets.next()) != null) {
                        Bucket bucket = new Bucket(special, multiplier);
                String key = bucket.special ? RedisKeyContract.specialList(gameId, bucket.multiplier) : RedisKeyContract.normalList(gameId, bucket.multiplier);
                // Randomly read a cached complete round without consuming it.
                long len = redis.llen(key);
                if (len <= 0) continue;
                Object claimed = redis.command("LINDEX", key, Integer.toString(RANDOM.nextInt((int) Math.min(len, Integer.MAX_VALUE))));
                String member = claimed == null ? null : claimed.toString();
                if (member == null) continue;
                GameRuleCore.Round cached = MemberCodec.decode(member);
                if (ResultUtil.multiplier(cached) != bucket.multiplier || ResultUtil.special(cached) != bucket.special)
                    throw new IllegalStateException("Redis member classification does not match selected bucket");
                return GameRuleCore.reprice(cached, level, size);
            }
                }
            }
            throw new IllegalStateException("selected Redis gid33 WIN/LOSS side became empty");
        }
    }

    

    private static void historyList(HttpExchange e) throws IOException {
        if (!getOrPost(e)) return; Map<String,String> values = "POST".equalsIgnoreCase(e.getRequestMethod()) ? form(e) : query(e);
        synchronized (LOCK) { SessionState s = session(sessionKey(e, values)); List<String> rows = new ArrayList<>(); BigDecimal totalBet = BigDecimal.ZERO, totalAward = BigDecimal.ZERO;
            for (History h : s.history) rows.add("{\"ba\":" + quote(money(h.round.paidBet())) + ",\"baf\":" + quote(money(h.balanceAfterBet)) + ",\"bid\":" + quote("33-" + h.transferId) + ",\"ca\":" + h.createdAt + ",\"fe\":0,\"gm\":null,\"gt\":33,\"tis\":" + quote(h.transferId) + ",\"wa\":" + quote(money(h.round.totalAward())) + "}");
            for (History h : s.history) { totalBet = totalBet.add(h.round.paidBet()); totalAward = totalAward.add(h.round.totalAward()); }
            json(e, 200, ok("{\"end\":1,\"lc\":" + rows.size() + ",\"ba\":" + quote(money(totalBet)) + ",\"wa\":" + quote(money(totalAward)) + ",\"ll\":[" + String.join(",", rows) + "]}")); }
    }

    private static void historyView(HttpExchange e) throws IOException {
        if (!post(e)) return; Map<String,String> form = form(e); String transfer = firstNonBlank(form.get("transfer_id"), form.get("tis"), form.get("id"));
        synchronized (LOCK) { SessionState s = session(sessionKey(e, form)); for (History h : s.history) if (h.transferId.equals(transfer)) {
            List<String> base = h.steps.subList(0, 1), free = h.steps.subList(1, h.steps.size());
            json(e, 200, ok("{\"baf\":" + quote(money(h.balanceAfter)) + ",\"bid\":" + quote("33-" + transfer) + ",\"bsl\":[" + String.join(",", base) + "]" + (free.isEmpty() ? "" : ",\"fsl\":[" + String.join(",", free) + "]") + "}")); return;
        }} json(e, 404, error("history transfer_id not found"));
    }

    private static void balance(HttpExchange e) throws IOException {
        if (!getOrPost(e)) return; Map<String,String> values = "POST".equalsIgnoreCase(e.getRequestMethod()) ? form(e) : query(e);
        synchronized (LOCK) { json(e, 200, ok("{\"balance\":" + quote(money(session(sessionKey(e, values)).balance)) + "}")); }
    }

    private static void timingReport(HttpExchange e) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(e.getRequestMethod())) { empty(e, 204); return; }
        if (!"POST".equalsIgnoreCase(e.getRequestMethod())) { json(e, 405, error("POST required")); return; }
        e.getRequestBody().readAllBytes();
        empty(e, 204);
    }

    private static void sessionInfo(HttpExchange e) throws IOException {
        if (!getOrPost(e)) return; Map<String,String> values = "POST".equalsIgnoreCase(e.getRequestMethod()) ? form(e) : query(e);
        synchronized (LOCK) { SessionState s = session(sessionKey(e, values)); String active = s.active == null ? "null" : "{\"transferId\":" + quote(s.active.transferId) + ",\"nextIndex\":" + s.active.nextIndex + ",\"deliveries\":" + s.active.round.deliveries().size() + "}";
            json(e, 200, ok("{\"rawGameId\":33,\"balance\":" + quote(money(s.balance)) + ",\"activeRound\":" + active + ",\"historyCount\":" + s.history.size() + "}")); }
    }

    private static String deliveryJson(String transfer, GameRuleCore.Round r, GameRuleCore.Delivery d, BigDecimal balance) {
        return "{\"ba\":" + money(d.paidBet()) + ",\"bl\":" + r.betLevel() + ",\"bs\":" + r.betSize() + ",\"fbt\":" + Math.max(0, d.fsn() - d.nfsc()) + ",\"frwa\":0,\"fsn\":" + d.fsn() + ",\"gt\":" + d.gameType() + ",\"nfsc\":" + d.nfsc() + ",\"pb\":" + quote(money(balance)) + ",\"pl\":{\"balance\":" + quote(money(balance)) + ",\"id\":33},\"rpx\":" + d.rpx() + ",\"rskl\":" + strings(d.board().externalCells()) + ",\"rwa\":" + money(d.cumulativeAward()) + ",\"small_game_type\":" + d.smallGameType() + ",\"ss\":" + (d.terminal() ? 1 : 0) + ",\"wa\":" + money(d.award()) + ",\"wmkl\":" + wins(d.wins(), false) + ",\"_transferId\":" + quote(transfer) + ",\"_roundTerminal\":" + d.terminal() + "}";
    }
    private static String historyStep(String transfer, GameRuleCore.Round r, GameRuleCore.Delivery d, BigDecimal balance) {
        return "{\"ba\":" + money(d.paidBet()) + ",\"balance_after\":" + quote(money(balance)) + ",\"bet_level\":" + r.betLevel() + ",\"bet_size\":" + r.betSize() + ",\"bid\":" + quote("33-" + transfer) + ",\"bl\":" + r.betLevel() + ",\"bs\":" + r.betSize() + ",\"cc\":\"BRL\",\"created_at\":" + Instant.now().getEpochSecond() + ",\"cs\":\"R$\",\"frwa\":0,\"fsn\":" + d.fsn() + ",\"gt\":" + d.gameType() + ",\"nfsc\":" + d.nfsc() + ",\"pb\":" + quote(money(balance)) + ",\"pl\":{\"balance\":" + quote(money(balance)) + ",\"id\":33},\"rpx\":" + d.rpx() + ",\"rskl\":" + strings(d.board().externalCells()) + ",\"rwa\":" + money(d.cumulativeAward()) + ",\"small_game_type\":" + d.smallGameType() + ",\"ss\":" + (d.terminal() ? 1 : 0) + ",\"wa\":" + money(d.award()) + ",\"wmkl\":" + wins(d.wins(), true) + "}";
    }

    private static SessionState session(String token) { return state.sessions.computeIfAbsent(token, ignored -> new SessionState(new BigDecimal(config.getProperty("session.initialBalance", "10000.00")))); }
    private static String sessionKey(HttpExchange e, Map<String,String> v) { return firstNonBlank(e.getRequestHeaders().getFirst("web-token"), e.getRequestHeaders().getFirst("X-Session-Token"), v.get("t"), v.get("token"), "local-session"); }
    private static int remaining(SessionState s) { return s.active == null ? 0 : Math.max(0, s.active.round.deliveries().size() - s.active.nextIndex); }
    private static ControllerState loadState() throws IOException { if (!Files.isRegularFile(stateFile)) return new ControllerState(GameRuleCore.RULES_HASH); try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(stateFile))) { Object o = in.readObject(); if (!(o instanceof ControllerState restored) || !GameRuleCore.RULES_HASH.equals(restored.rulesHash)) throw new IOException("controller state rulesHash mismatch"); return restored; } catch (ClassNotFoundException ex) { throw new IOException(ex); } }
    private static void saveState() throws IOException { Path parent = stateFile.getParent(); if (parent != null) Files.createDirectories(parent); Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp"); try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) { out.writeObject(state); } try { Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING); } }

    private static void staticFile(HttpExchange e) throws IOException { if ("OPTIONS".equalsIgnoreCase(e.getRequestMethod())) { empty(e, 204); return; } if (!"GET".equalsIgnoreCase(e.getRequestMethod()) && !"HEAD".equalsIgnoreCase(e.getRequestMethod())) { json(e, 405, error("GET required")); return; } String p = e.getRequestURI().getPath(); if (p.equals("/")) p = "/index.html"; Path f = publishRoot.resolve(p.substring(1)).normalize(); if (!f.startsWith(publishRoot) || !Files.isRegularFile(f)) { json(e, 404, error("not found")); return; } byte[] body = Files.readAllBytes(f); if (p.equals("/report.js")) { String report = new String(body, StandardCharsets.UTF_8).replace("return 'https://' + sip + '/api/report/timing';", "return window.location.protocol + '//' + sip + '/api/report/timing';"); body = report.getBytes(StandardCharsets.UTF_8); } e.getResponseHeaders().set("Content-Type", mime(p)); cors(e); e.getResponseHeaders().set("Cache-Control", "no-store"); boolean head = "HEAD".equalsIgnoreCase(e.getRequestMethod()); e.sendResponseHeaders(200, head ? -1 : body.length); if (!head) e.getResponseBody().write(body); e.close(); }
    private static String mime(String p) { String v = p.toLowerCase(Locale.ROOT); if (v.endsWith(".js")) return "application/javascript; charset=utf-8"; if (v.endsWith(".css")) return "text/css; charset=utf-8"; if (v.endsWith(".json")) return "application/json; charset=utf-8"; if (v.endsWith(".png")) return "image/png"; if (v.endsWith(".jpg") || v.endsWith(".jpeg")) return "image/jpeg"; if (v.endsWith(".mp3")) return "audio/mpeg"; if (v.endsWith(".wav")) return "audio/wav"; if (v.endsWith(".ttf")) return "font/ttf"; if (v.endsWith(".plist")) return "application/xml"; if (v.endsWith(".bin")) return "application/octet-stream"; return "text/html; charset=utf-8"; }
    private static boolean post(HttpExchange e) throws IOException { if ("OPTIONS".equalsIgnoreCase(e.getRequestMethod())) { empty(e, 204); return false; } if (!"POST".equalsIgnoreCase(e.getRequestMethod())) { json(e, 405, error("POST required")); return false; } return true; }
    private static boolean getOrPost(HttpExchange e) throws IOException { if ("OPTIONS".equalsIgnoreCase(e.getRequestMethod())) { empty(e, 204); return false; } if (!"GET".equalsIgnoreCase(e.getRequestMethod()) && !"POST".equalsIgnoreCase(e.getRequestMethod())) { json(e, 405, error("GET or POST required")); return false; } return true; }
    private static Map<String,String> form(HttpExchange e) throws IOException { return pairs(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); }
    private static Map<String,String> query(HttpExchange e) { return pairs(e.getRequestURI().getRawQuery()); }
    private static Map<String,String> pairs(String text) { Map<String,String> v = new LinkedHashMap<>(); if (text == null || text.isBlank()) return v; for (String pair : text.split("&")) { if (pair.isBlank()) continue; String[] p = pair.split("=", 2); v.put(decode(p[0]), decode(p.length > 1 ? p[1] : "")); } return v; }
    private static String decode(String v) { return URLDecoder.decode(v, StandardCharsets.UTF_8); }
    private static void empty(HttpExchange e, int status) throws IOException { cors(e); e.sendResponseHeaders(status, -1); e.close(); }
    private static void json(HttpExchange e, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); cors(e); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
    private static void cors(HttpExchange e) { e.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); e.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, game-id, web-token, Idempotency-Key, X-Session-Token"); e.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS"); }
    private static String ok(String data) { return "{\"code\":200,\"data\":" + data + ",\"info\":\"ok\"}"; }
    private static String error(String message) { return "{\"code\":500,\"data\":null,\"info\":" + quote(message == null ? "error" : message) + "}"; }
    private static String quote(String v) { return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String money(BigDecimal v) { return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }
    private static String strings(List<String> v) { return "[" + String.join(",", v.stream().map(ServerMain::quote).toList()) + "]"; }
    private static String wins(List<GameRuleCore.Win> wins, boolean history) { List<String> values = new ArrayList<>(); for (GameRuleCore.Win w : wins) values.add(history ? "{\"psn\":" + w.count() + ",\"sk\":" + quote(GameRuleCore.externalName(w.symbol())) + ",\"wa\":" + quote(money(w.award())) + ",\"wpk\":" + w.line() + "}" : quote(Integer.toString(w.line())) + ":{\"" + GameRuleCore.externalName(w.symbol()) + "\":" + w.count() + "}"); return history ? "[" + String.join(",", values) + "]" : "{" + String.join(",", values) + "}"; }
    private static String paytableJson() { return "{\"9\":{\"3\":5,\"4\":25,\"5\":100},\"A\":{\"3\":10,\"4\":50,\"5\":150},\"H1\":{\"2\":10,\"3\":50,\"4\":250,\"5\":750},\"H2\":{\"2\":5,\"3\":40,\"4\":200,\"5\":500},\"H3\":{\"3\":30,\"4\":150,\"5\":400},\"H4\":{\"3\":25,\"4\":100,\"5\":250},\"H5\":{\"3\":25,\"4\":100,\"5\":250},\"J\":{\"3\":5,\"4\":25,\"5\":100},\"K\":{\"3\":10,\"4\":50,\"5\":150},\"Q\":{\"3\":5,\"4\":25,\"5\":100},\"Scat\":{\"2\":0,\"3\":0,\"4\":0,\"5\":0},\"T\":{\"3\":5,\"4\":25,\"5\":100},\"Wild\":{\"2\":25,\"3\":150,\"4\":1000,\"5\":2500}}"; }
    private static Properties load(Path p) throws IOException { Properties v = new Properties(); try (InputStream in = Files.newInputStream(p)) { v.load(in); } return v; }
    private static String requiredConfig(String key) { String value = config.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key); return value.trim(); }
    private static Map<String,String> options(String[] args) { Map<String,String> v = new LinkedHashMap<>(); for (int i = 0; i < args.length; i++) { if (!args[i].startsWith("--")) continue; String key = args[i].substring(2); if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException("missing value for --" + key); v.put(key, args[++i]); } return v; }
    private static int requiredPort(Map<String,String> options) { String text = firstNonBlank(options.get("port"), System.getenv("PORT")); if (text == null) throw new IllegalArgumentException("managed controller requires --port or PORT"); int port = Integer.parseInt(text); if (port < 50000 || port > 59999) throw new IllegalArgumentException("managed port must be 50000..59999"); return port; }
    private static int parseLevel(String v) { int n = Integer.parseInt(v); if (n < 1 || n > 10) throw new IllegalArgumentException("bl must be 1..10"); return n; }
    private static BigDecimal parseBetSize(String v) { BigDecimal n = new BigDecimal(v); if (n.compareTo(new BigDecimal("0.02")) != 0 && n.compareTo(new BigDecimal("0.12")) != 0 && n.compareTo(new BigDecimal("0.8")) != 0) throw new IllegalArgumentException("bs must be 0.02, 0.12 or 0.8"); return n; }
    private static GameRuleCore.Scenario scenario(String v) { return v == null || v.isBlank() ? GameRuleCore.Scenario.RANDOM : GameRuleCore.Scenario.valueOf(v.toUpperCase(Locale.ROOT)); }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v; return null; }

    private static final class ControllerState implements Serializable { @Serial private static final long serialVersionUID = 1L; final String rulesHash; final Map<String,SessionState> sessions = new LinkedHashMap<>(); ControllerState(String rulesHash) { this.rulesHash = rulesHash; } }
    private static final class SessionState implements Serializable { @Serial private static final long serialVersionUID = 1L; BigDecimal balance; ActiveRound active; final Deque<History> history = new LinkedList<>(); final LinkedHashMap<String,String> idempotentResponses = new LinkedHashMap<>(); String lastDelivery; SessionState(BigDecimal balance) { this.balance = balance; } }
    private static final class ActiveRound implements Serializable { @Serial private static final long serialVersionUID = 1L; final String transferId; final GameRuleCore.Round round; int nextIndex; final BigDecimal balanceAfterBet; final List<String> steps; ActiveRound(String id, GameRuleCore.Round round, int next, BigDecimal balance, List<String> steps) { this.transferId=id; this.round=round; this.nextIndex=next; this.balanceAfterBet=balance; this.steps=steps; } }
    private static final class History implements Serializable { @Serial private static final long serialVersionUID = 1L; final String transferId; final long createdAt; final GameRuleCore.Round round; final BigDecimal balanceAfterBet; final BigDecimal balanceAfter; final List<String> steps; History(String id,long at,GameRuleCore.Round round,BigDecimal bet,BigDecimal after,List<String> steps){this.transferId=id;this.createdAt=at;this.round=round;this.balanceAfterBet=bet;this.balanceAfter=after;this.steps=steps;} }
    private record Bucket(boolean special, int multiplier) {}
}
