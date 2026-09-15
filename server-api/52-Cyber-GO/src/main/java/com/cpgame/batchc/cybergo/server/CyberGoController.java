package com.cpgame.batchc.cybergo.server;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import com.cpgame.batchc.cybergo.CyberGoModels.CompleteRound;
import com.cpgame.batchc.cybergo.CyberGoModels.Step;
import com.cpgame.batchc.cybergo.GameRuleCore;
import com.cpgame.batchc.cybergo.ResultUtil;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** B01/B02/B05/B08 Controller；只投影GameRuleCore生成的Delivery。 */
final class CyberGoController {
    private final GameRuleCore core;
    private final SessionStore sessions;
    private final com.cpgame.batchc.cybergo.RedisRoundPool pool;

    CyberGoController(GameRuleCore core, SessionStore sessions, com.cpgame.batchc.cybergo.RedisRoundPool pool) {
        this.pool = pool;
        this.core = core;
        this.sessions = sessions;
        if (!RULES_HASH.equals(core.rulesHash())) throw new IllegalStateException("GameRuleCore rulesHash与协议基线不一致");
    }

    void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { ProtocolCodec.options(exchange); return; }
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/cp/api/")) path = path.substring(3);
        boolean internalGet = path.equals("/health") || path.equals("/__controller/status");
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !internalGet) {
            ProtocolCodec.failure(exchange, 405, 405, "协议端点仅接受POST");
            return;
        }
        try {
            Map<String, String> form = "POST".equalsIgnoreCase(exchange.getRequestMethod()) ? ProtocolCodec.form(exchange) : Map.of();
            Object result = switch (path) {
                case "/api/v1/auth/verify" -> auth(form);
                case "/api/v1/go-cyber/config" -> config(require(form));
                case "/api/v1/go-cyber/spin" -> idempotent(exchange, path, require(form), () -> spin(require(form), form));
                case "/api/v1/go-cyber/log-list" -> historyList(require(form), form);
                case "/api/v1/go-cyber/log-view" -> historyView(require(form), form.get("transfer_id"));
                case "/api/v1/ping" -> ping(require(form));
                case "/api/report/timing" -> Map.of("accepted", true);
                case "/api/v1/go-cyber/balance", "/api/v1/balance" -> balance(require(form));
                case "/api/v1/go-cyber/session", "/api/v1/session" -> session(require(form));
                case "/api/v1/go-cyber/init", "/api/v1/init" -> init(form);
                case "/health" -> controllerStatus("UP");
                case "/__controller/status" -> {
                    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress())
                        throw new ApiException(403, 403, "Controller状态仅允许共享宿主本机读取");
                    yield controllerStatus("MOUNTED");
                }
                default -> throw new ApiException(404, 404, "接口不存在");
            };
            ProtocolCodec.success(exchange, result);
        } catch (ApiException error) {
            ProtocolCodec.failure(exchange, error.httpStatus, error.code, error.getMessage());
        } catch (IllegalArgumentException error) {
            ProtocolCodec.failure(exchange, 400, 400, error.getMessage());
        } catch (Exception error) {
            error.printStackTrace(System.err);
            ProtocolCodec.failure(exchange, 500, 500, "服务端生成或状态校验失败");
        }
    }

    private Map<String, Object> controllerStatus(String status) {
        ClassLoader loader = CyberGoController.class.getClassLoader();
        return Map.ofEntries(
                Map.entry("status", status), Map.entry("gameId", GAME_ID), Map.entry("rulesHash", RULES_HASH),
                Map.entry("bindScope", "ALL_INTERFACES"), Map.entry("generation", "REDIS_PREGENERATED_COMPLETE_ROUND"),
                Map.entry("sharedJvmPid", ProcessHandle.current().pid()),
                Map.entry("managedProcessPid", ProcessHandle.current().pid()),
                Map.entry("controllerClassLoader", loader.getClass().getName()),
                Map.entry("controllerClassLoaderIdentity", Integer.toHexString(System.identityHashCode(loader))),
                Map.entry("stateStoreIdentity", Integer.toHexString(System.identityHashCode(sessions))),
                Map.entry("mountMode", "MANAGED_PROCESS_CONTROLLER"));
    }

    private Object auth(Map<String, String> form) {
        verifyGid(form);
        SessionStore.PlayerSession session = sessions.verify(form.get("t"));
        synchronized (session) {
            return authData(session);
        }
    }

    private Object init(Map<String, String> form) {
        verifyGid(form);
        SessionStore.PlayerSession session = sessions.verify(form.get("t"));
        synchronized (session) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("auth", authData(session));
            value.put("config", configData(session));
            return value;
        }
    }

    private Map<String, Object> authData(SessionStore.PlayerSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", player(session));
        data.put("token", session.token);
        data.put("ping", Map.of("enable", 0, "seconds", 0));
        data.put("rc", Map.of("on", 0, "v", 2));
        data.put("gc", Map.of("os", 1, "om", 1));
        return data;
    }

    private Object config(SessionStore.PlayerSession session) {
        synchronized (session) { return configData(session); }
    }

    private Map<String, Object> configData(SessionStore.PlayerSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auto", List.of(10, 30, 50, 100, 500));
        data.put("bll", List.of(1,2,3,4,5,6,7,8,9,10));
        data.put("bsl", List.of(new BigDecimal("0.02"), new BigDecimal("0.2")));
        data.put("cc", "BRL");
        data.put("cs", "R$");
        data.put("dbl", new BigDecimal("0.2"));
        data.put("dbs", new BigDecimal("0.02"));
        Step last = session.activeRound == null ? null : session.activeRound.lastDelivered();
        data.put("last", last == null ? null : stepMap(last, session, false));
        data.put("ls", null);
        data.put("spl", PAYTABLE);
        data.put("ts", System.currentTimeMillis() / 1000L);
        return data;
    }

    private Object spin(SessionStore.PlayerSession session, Map<String, String> form) {
        BigDecimal bs = decimal(form.get("bs"), "bs");
        int bl = integer(form.get("bl"), "bl");
        if (bl < 1 || bl > 10 || !(bs.compareTo(MINIMUM_BET_SIZE)==0 || bs.compareTo(new BigDecimal("0.2"))==0)) {
            throw new ApiException(400, 400, "下注选项不在原始config范围内");
        }
        synchronized (session) {
            if (session.activeRound == null) {
                BigDecimal bet = bs.multiply(BigDecimal.valueOf((long)bl*BASIC_BET_FACTOR));
                if (session.balance.compareTo(bet) < 0) throw new ApiException(402, 402, "余额不足R$0.60");
                CompleteRound round;
                try { round = core.atBet(pool.claim(), bl, bs); }
                catch (IOException error) { throw new ApiException(503, 503, error.getMessage()); }
                ResultUtil.validateCompleteRound(round);
                session.balance = session.balance.subtract(round.bet()).setScale(2);
                session.activeRound = new SessionStore.ActiveRound(round);
            }
            SessionStore.ActiveRound active = session.activeRound;
            if(active.round.deliveries().getFirst().bl()!=bl || active.round.deliveries().getFirst().bs().compareTo(bs)!=0)
                throw new ApiException(400,400,"活动Round不能更改下注");
            if (!active.hasNext()) throw new IllegalStateException("活动Round无下一Delivery");
            Step step = active.next();
            session.balance = session.balance.add(step.wa()).setScale(2);
            Map<String, Object> projection = stepMap(step, session, true);
            if (step.terminal()) {
                session.history.addFirst(new SessionStore.HistoryRound(active.round, session.balance));
                while (session.history.size() > 1000) session.history.removeLast();
                session.activeRound = null;
            }
            return projection;
        }
    }

    private Object historyList(SessionStore.PlayerSession session, Map<String, String> form) {
        int page = integer(form.getOrDefault("page_index", "1"), "page_index");
        long begin = timestamp(form.getOrDefault("begin_at", "0"), "begin_at");
        long end = timestamp(form.getOrDefault("end_at", Long.toString(Long.MAX_VALUE)), "end_at");
        if (page < 1 || begin > end) throw new ApiException(400, 400, "历史页码或时间范围非法");
        synchronized (session) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SessionStore.HistoryRound item : session.history) {
                CompleteRound round = item.round();
                if (round.generatedAtEpochSecond() < begin || round.generatedAtEpochSecond() > end) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ba", round.bet().toPlainString());
                row.put("baf", item.balanceAfter().toPlainString());
                row.put("bid", "52-" + round.roundKey());
                row.put("ca", round.generatedAtEpochSecond());
                row.put("fe", round.kind().name().equals("FREE_SPINS") ? round.deliveries().getFirst().fsn() : 0);
                row.put("gm", null);
                row.put("gt", GAME_ID);
                row.put("tis", round.roundKey());
                row.put("wa", round.totalWin().toPlainString());
                rows.add(row);
            }
            BigDecimal betTotal = BigDecimal.ZERO;
            BigDecimal winTotal = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                betTotal = betTotal.add(new BigDecimal(row.get("ba").toString()));
                winTotal = winTotal.add(new BigDecimal(row.get("wa").toString()));
            }
            int from = (int)Math.min(rows.size(), ((long)page - 1) * 10);
            int to = Math.min(rows.size(), from + 10);
            return Map.of("ll", List.copyOf(rows.subList(from, to)), "end", to == rows.size() ? 1 : 0,
                    "lc", rows.size(), "ba", betTotal.toPlainString(), "wa", winTotal.toPlainString());
        }
    }

    private Object historyView(SessionStore.PlayerSession session, String transferId) {
        if (transferId == null || transferId.isBlank()) throw new ApiException(400, 400, "transfer_id不能为空");
        synchronized (session) {
            for (SessionStore.HistoryRound item : session.history) {
                if (!item.round().roundKey().equals(transferId)) continue;
                List<Map<String, Object>> bsl = List.of(stepMap(item.round().deliveries().getFirst(), session, false));
                List<Map<String, Object>> fsl = item.round().deliveries().size() == 1 ? List.of() :
                        item.round().deliveries().subList(1, item.round().deliveries().size()).stream()
                                .map(step -> stepMap(step, session, false)).toList();
                return Map.of("bsl", bsl, "fsl", fsl, "baf", item.balanceAfter().toPlainString(), "bid", "52-" + item.round().roundKey());
            }
        }
        throw new ApiException(404, 404, "未找到transfer_id");
    }

    private Object balance(SessionStore.PlayerSession session) {
        synchronized (session) { return player(session); }
    }

    private Object ping(SessionStore.PlayerSession session) {
        synchronized (session) { return Map.of("ts", System.currentTimeMillis() / 1000L); }
    }

    private Object session(SessionStore.PlayerSession session) {
        synchronized (session) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("player", player(session));
            data.put("rulesHash", RULES_HASH);
            if (session.activeRound == null) data.put("activeRound", null);
            else data.put("activeRound", Map.of("roundKey", session.activeRound.round.roundKey(),
                    "deliveryIndex", session.activeRound.deliveryIndex,
                    "deliveryCount", session.activeRound.round.deliveries().size()));
            return data;
        }
    }

    private Map<String, Object> stepMap(Step step, SessionStore.PlayerSession session, boolean includePlayer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ba", step.ba()); map.put("bid", step.bid()); map.put("bl", step.bl()); map.put("bs", step.bs());
        map.put("ca", step.ca()); map.put("fsn", step.fsn()); map.put("frwa", step.frwa()); map.put("gt", step.gt());
        map.put("nfsc", step.nfsc()); map.put("rpx", step.rpx()); map.put("rskl", step.rskl()); map.put("rwa", step.rwa());
        map.put("small_game_type", step.small_game_type()); map.put("ss", step.ss()); map.put("wa", step.wa());
        map.put("wmkl", step.wmkl()); map.put("wskl", step.wskl());
        if (includePlayer) map.put("pl", player(session));
        return map;
    }

    private Map<String, Object> player(SessionStore.PlayerSession session) {
        return Map.of("balance", session.balance.setScale(2).toPlainString(), "id", session.playerId);
    }

    private SessionStore.PlayerSession require(Map<String, String> form) {
        verifyGid(form);
        return sessions.require(form.get("t"));
    }

    private Object idempotent(HttpExchange exchange, String route, SessionStore.PlayerSession session,
                              java.util.function.Supplier<Object> action) {
        return session.idempotent(route, exchange.getRequestHeaders().getFirst("Idempotency-Key"), action);
    }

    private void verifyGid(Map<String, String> form) {
        if (!Integer.toString(GAME_ID).equals(form.get("gid"))) throw new ApiException(400, 400, "gid必须为52");
    }

    private static long timestamp(String value, String name) {
        try {
            long result = Long.parseLong(value);
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw new ApiException(400, 400, name + "格式错误"); }
    }

    private static BigDecimal decimal(String value, String name) {
        try { return new BigDecimal(value); } catch (Exception error) { throw new ApiException(400, 400, name + "格式错误"); }
    }
    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); } catch (Exception error) { throw new ApiException(400, 400, name + "格式错误"); }
    }
}
