package com.hd.cpgame.magicscroll2.server;

import com.hd.cpgame.magicscroll2.api.StaticFileHandler;
import com.hd.cpgame.magicscroll2.api.http.FormFields;
import com.hd.cpgame.magicscroll2.api.http.HttpResponses;
import com.hd.cpgame.magicscroll2.core.GameConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class MagicScroll2Server implements AutoCloseable {
    private final AppConfig config;
    private final SessionService sessions;
    private HttpServer server;

    public MagicScroll2Server(AppConfig config) {
        this.config = config;
        this.sessions = new SessionService(config.initialBalance, new RedisRoundStore(config));
    }

    MagicScroll2Server(AppConfig config, RedisRoundStore store) {
        this.config = config;
        this.sessions = new SessionService(config.initialBalance, store);
    }

    public void start() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress(config.address, config.port), 128);
            server.createContext("/web-api/auth/session/v3/verifySession", this::verify);
            server.createContext("/game-api/cp-magic-scroll2/v2/GameInfo/Get", this::config);
            server.createContext("/game-api/cp-magic-scroll2/v2/Spin", this::spin);
            server.createContext("/game-api/cp-magic-scroll2/v2/History", this::history);
            server.createContext("/game-api/cp-magic-scroll2/v2/HistoryDate", this::historyDate);
            server.createContext("/game-api/cp-magic-scroll2/v2/Top", this::top);
            server.createContext("/api/init", this::verify);
            server.createContext("/api/config", this::config);
            server.createContext("/api/spin", this::spin);
            server.createContext("/api/history", this::history);
            server.createContext("/api/balance", this::balance);
            server.createContext("/api/session", this::session);
            server.createContext("/health", this::health);
            server.createContext("/", new StaticFileHandler(config.publishDirectory.toFile()));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private void verify(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            PlayerSession session = sessions.verify(form.get("gi"), form.get("otk"), form.get("l"));
            HttpResponses.envelope(exchange, sessionPayload(session));
        } catch (Exception e) { fail(exchange, e); }
    }

    private void config(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            PlayerSession session = sessions.require(form.get("atk"));
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("bl", session.balance());
            dt.put("cc", "BRL");
            dt.put("betList", Arrays.asList(20, 120, 800));
            dt.put("multList", Arrays.asList(10000, 20000, 30000, 40000, 50000, 60000, 70000, 80000, 90000, 100000));
            dt.put("autoList", Arrays.asList(10, 20, 30, 50, 100, 200, 300, 500, 1000, -1));
            dt.put("totalList", Arrays.asList(400,800,1200,1600,2000,2400,2800,3200,3600,4000,4800,7200,9600,12000,14400,16000,16800,19200,21600,24000,32000,48000,64000,80000,96000,112000,128000,144000,160000));
            dt.put("aniList", Arrays.asList(250000, 375000, 500000, 1000000));
            dt.put("version", 1);
            dt.put("formation", GameConstants.IDLE_FORMATION);
            dt.put("round", 1);
            HttpResponses.envelope(exchange, dt);
        } catch (Exception e) { fail(exchange, e); }
    }

    private void spin(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            PlayerSession session = sessions.require(form.get("atk"));
            BigDecimal bet = decimal(form.get("bet"), BigDecimal.ZERO);
            BigDecimal mult = decimal(form.get("mult"), BigDecimal.ZERO);
            int gameType = integer(form.get("gameType"), 1);
            boolean reconnect = bet.compareTo(BigDecimal.ZERO) < 0;
            String key = first(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("X-Request-Id"), form.get("requestId"));
            HttpResponses.envelope(exchange, session.spin(bet, mult, gameType, reconnect, key));
        } catch (Exception e) { fail(exchange, e); }
    }

    private void history(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            PlayerSession session = sessions.require(form.get("atk"));
            int page = integer(form.get("page"), 1);
            int size = integer(form.get("size"), 20);
            if (page < 1 || size < 1 || size > 20) throw new ApiException("42200", "unsupported History pagination boundary");
            Long timeBegin = optionalLong(form.get("time_begin"));
            Long timeEnd = optionalLong(form.get("time_end"));
            List<PlayerSession.HistoryRecord> filtered = new ArrayList<>();
            for (PlayerSession.HistoryRecord record : session.history()) {
                if (timeBegin != null && record.createdAt < timeBegin) continue;
                if (timeEnd != null && record.createdAt >= timeEnd) continue;
                filtered.add(record);
            }
            Collections.sort(filtered, Comparator.comparingLong((PlayerSession.HistoryRecord r) -> r.createdAt).reversed()
                    .thenComparing((a, b) -> b.roundKey.compareTo(a.roundKey)));
            BigDecimal allBet = BigDecimal.ZERO.setScale(2);
            BigDecimal allWin = BigDecimal.ZERO.setScale(2);
            for (PlayerSession.HistoryRecord record : filtered) {
                allBet = allBet.add(record.bet).setScale(2);
                allWin = allWin.add(record.transfer).setScale(2);
            }
            int from = Math.min(filtered.size(), (page - 1) * size);
            int to = Math.min(filtered.size(), from + size);
            List<Map<String, Object>> items = new ArrayList<>();
            for (PlayerSession.HistoryRecord record : filtered.subList(from, to)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("psid", record.roundKey);
                item.put("created_at", record.createdAt);
                item.put("bet", record.bet);
                item.put("mult", record.mult);
                item.put("transfer", record.transfer);
                item.put("balance", record.balance);
                item.put("gameType", 1);
                item.put("formation", record.formations);
                item.put("free", false);
                items.add(item);
            }
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("all_num", filtered.size());
            dt.put("all_bet", allBet);
            dt.put("all_win", allWin);
            dt.put("historyItem", items.isEmpty() ? null : items);
            HttpResponses.envelope(exchange, dt);
        } catch (Exception e) { fail(exchange, e); }
    }

    private void historyDate(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            HttpResponses.error(exchange, 404, "ORIGINAL_PATH_NOT_FOUND",
                    "fresh original-site verification returned 404 for v2/HistoryDate");
        } catch (Exception e) { fail(exchange, e); }
    }

    private void top(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            throw new ApiException("CAPABILITY_GATED", "B-TOP is UNKNOWN and not implemented");
        } catch (Exception e) { fail(exchange, e); }
    }

    private void balance(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            PlayerSession session = sessions.require(form.get("atk"));
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("bl", session.balance());
            dt.put("cc", "BRL");
            HttpResponses.envelope(exchange, dt);
        } catch (Exception e) { fail(exchange, e); }
    }

    private void session(HttpExchange exchange) throws IOException {
        try {
            if (preflight(exchange)) return;
            Map<String, String> form = FormFields.parse(exchange);
            HttpResponses.envelope(exchange, sessionPayload(sessions.require(form.get("atk"))));
        } catch (Exception e) { fail(exchange, e); }
    }

    private void health(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("gameId", GameConstants.GAME_ID);
            health.put("rulesHash", GameConstants.RULES_HASH);
            HttpResponses.json(exchange, 200, health);
        } catch (Exception e) { fail(exchange, e); }
    }

    private Map<String, Object> sessionPayload(PlayerSession session) {
        Map<String, Object> dt = new LinkedHashMap<>();
        dt.put("cc", "BRL");
        dt.put("cs", "R$");
        dt.put("atk", session.atk());
        dt.put("gdr", "/game-api/cp-magic-scroll2/");
        dt.put("player", session.playerName());
        dt.put("user_id", session.userId());
        return dt;
    }

    private boolean preflight(HttpExchange exchange) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpResponses.options(exchange);
            return true;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                && !exchange.getRequestURI().getPath().equals("/health")) {
            HttpResponses.error(exchange, 405, "METHOD_NOT_ALLOWED", "POST is required");
            return true;
        }
        return false;
    }

    private void fail(HttpExchange exchange, Exception e) {
        try {
            if (e instanceof ApiException api) {
                HttpResponses.error(exchange, 422, api.getCode(), api.getMessage());
            } else if (e instanceof RedisRoundStore.PoolUnavailableException pool) {
                HttpResponses.error(exchange, 503, pool.code(), pool.getMessage());
            } else {
                e.printStackTrace(System.err);
                HttpResponses.error(exchange, 500, "INTERNAL_ERROR", e.getMessage());
            }
        } catch (Exception ignored) {
            exchange.close();
        }
    }

    private static BigDecimal decimal(String value, BigDecimal fallback) {
        return value == null || value.trim().isEmpty() ? fallback : new BigDecimal(value.trim());
    }
    private static int integer(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
    }
    private static Long optionalLong(String value) {
        return value == null || value.trim().isEmpty() ? null : Long.valueOf(value.trim());
    }
    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }

    @Override public void close() {
        if (server != null) server.stop(0);
    }
}
