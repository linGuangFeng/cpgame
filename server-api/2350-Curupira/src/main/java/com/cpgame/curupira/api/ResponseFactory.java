package com.cpgame.curupira.api;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.RulesContract;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.RoundResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ResponseFactory {
    private ResponseFactory() {
    }

    static Map<String, Object> success(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", 0);
        envelope.put("data", data);
        envelope.put("msg", "success");
        envelope.put("time", Long.toString(Instant.now().getEpochSecond()));
        return envelope;
    }

    static Map<String, Object> error(int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("data", Map.of());
        envelope.put("msg", message);
        envelope.put("time", Long.toString(Instant.now().getEpochSecond()));
        return envelope;
    }

    static Map<String, Object> initialData(String requestedLanguage) {
        String actualLanguage = SupportedLanguages.resolve(requestedLanguage);
        Map<String, Object> gameInfo = new LinkedHashMap<>();
        gameInfo.put("bet_gold", List.of(0.02, 0.04, 0.2, 0.4));
        gameInfo.put("buy_free_max_bet", 0);
        gameInfo.put("default_bet_gold", 0);
        gameInfo.put("default_level", 10);
        gameInfo.put("game_way", List.of(Map.of("max_bet_gold", "0.00", "min_bet_gold", "0.00",
                "way_id", 235010000, "win_multi", "1.00")));
        gameInfo.put("gid", RulesContract.GAME_ID);
        gameInfo.put("least_gold", 0);
        gameInfo.put("name", RulesContract.GAME_NAME);
        gameInfo.put("status", "1");

        long now = Instant.now().getEpochSecond();
        Map<String, Object> initialConfig = new LinkedHashMap<>();
        initialConfig.put("bd_bet_count", 2);
        initialConfig.put("current_sys_time", now);
        initialConfig.put("is_debug", false);
        initialConfig.put("is_stopgs", 0);
        initialConfig.put("user_on_hook_time", 600);
        initialConfig.put("version", 1745909504);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("game_address", Map.of("ship_address_config", Map.of(
                "770", "https://luckyairships.net/",
                "780", "https://luckyairships.net/",
                "790", "https://luckyairships.net/",
                "800", "https://luckyairships.net/")));
        data.put("game_info", gameInfo);
        data.put("game_server", emptyGameServer());
        data.put("initial_config", initialConfig);
        data.put("language", actualLanguage);
        data.put("r", 1);
        data.put("zone", 0);
        return data;
    }

    static Map<String, Object> userInfo(com.cpgame.curupira.session.SessionState session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currency_symbol", "R$");
        data.put("day_first_login", 0);
        data.put("first_gold", null);
        data.put("gid", RulesContract.GAME_ID);
        data.put("gold", session.balance());
        data.put("is_guide", 0);
        data.put("nickname", Long.toString(session.userId()));
        data.put("token", session.token());
        data.put("total_recharge", "0");
        data.put("uid", session.userId());
        data.put("user_config", List.of());
        return data;
    }

    static Map<String, Object> round(RoundResult round) {
        Map<String, Object> data = roundBase(round);
        data.put("bet", round.lineBet());
        data.put("bet_gold", round.totalBet());
        data.put("change_gold", round.paidRound() ? round.change() : BigDecimal.ZERO.setScale(2));
        data.put("end_gold", round.endBalance());
        data.put("level", round.level());
        data.put("start_gold", round.startBalance());
        return data;
    }

    static Map<String, Object> history(List<com.cpgame.curupira.session.SessionState.HistoryRound> rounds,
                                       long start, long end, int page, int pageSize) {
        List<com.cpgame.curupira.session.SessionState.HistoryRound> filtered = rounds.stream()
                .filter(round -> round.createdAt >= start && round.createdAt <= end)
                .toList();
        int from = Math.min(Math.max(0, (page - 1) * pageSize), filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.cpgame.curupira.session.SessionState.HistoryRound round : filtered.subList(from, to)) {
            rows.add(historyRow(round));
        }
        BigDecimal bets = filtered.stream().map(round -> round.bet).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal changes = filtered.stream().map(round -> round.change).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("bet_golds", bets);
        totals.put("change_golds", changes);
        totals.put("total", filtered.size());
        return Map.of("list", rows, "totals", totals);
    }

    private static Map<String, Object> historyRow(com.cpgame.curupira.session.SessionState.HistoryRound round) {
        Map<String, Object> first = new LinkedHashMap<>(round.steps.get(0));
        long day = LocalDate.ofInstant(Instant.ofEpochSecond(round.createdAt), ZoneOffset.UTC)
                .atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        first.put("d", day);
        first.put("day", day);
        first.put("ext", Map.of("act_id", 0, "kind", 1));
        first.put("extend", Map.of("act_id", 0, "kind", 1));
        first.put("order_id", first.get("rid") + "-" + RulesContract.GAME_ID);
        first.put("results", round.steps);
        first.put("time", round.createdAt);
        first.put("total_win", round.tw);
        first.put("type", 1);
        first.put("bg", round.bet);
        first.put("cg", round.change);
        return first;
    }

    private static Map<String, Object> roundBase(RoundResult round) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("b", round.lineBet());
        data.put("bg", round.totalBet());
        data.put("cg", round.change());
        data.put("cl", 0);
        data.put("eg", round.endBalance());
        data.put("f", List.of());
        data.put("gt", 1);
        data.put("l", round.level());
        data.put("o", round.multiplierSum());
        data.put("oid", round.roundKey());
        data.put("res", result(round));
        data.put("rid", round.roundKey());
        data.put("sg", round.startBalance());
        data.put("small_game_type", 0);
        data.put("t", round.opaqueToken());
        data.put("tw", round.totalWin());
        data.put("u", round.userId());
        return data;
    }

    private static Map<String, Object> result(RoundResult round) {
        List<Map<String, Object>> awards = round.board().awards().stream().map(ResponseFactory::award).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gt", 1);
        result.put("ps", round.board().ps());
        result.put("sc", round.board().scatterCount());
        result.put("tws", round.totalWin());
        result.put("wa", awards);
        return result;
    }

    private static Map<String, Object> award(Award award) {
        return Map.of("c", award.c(), "ln", award.ln(), "o", award.o(), "s", award.s());
    }

    private static Map<String, Object> emptyGameServer() {
        Map<String, Object> server = new LinkedHashMap<>();
        for (String key : List.of("gos_host","gos_port","gos_sport","gs_host","gs_host1","gs_port","gs_port1",
                "gs_push_host","gs_push_port","gs_push_sport","gs_sport","gs_sport1","ps_host","ps_port",
                "snake_gs_host","snake_gs_port","snake_gs_sport","snake_gs_url")) {
            server.put(key, "");
        }
        server.put("gos_port", "8976");
        server.put("gs_push_sport", "28966");
        server.put("ngs_switch", 0);
        return server;
    }

    private static final class SupportedLanguages {
        private static final List<String> CODES = List.of("hi-in","fr-fr","ko-ko","es-es","vi-vn","th-th",
                "id-id","bn-bd","zh-cn","in-marathi","in-telugu","zh-hk","en-us","tr-tr","pt-pt");

        static String resolve(String requested) {
            return requested != null && CODES.contains(requested.toLowerCase()) ? requested.toLowerCase() : "en-us";
        }
    }
}
