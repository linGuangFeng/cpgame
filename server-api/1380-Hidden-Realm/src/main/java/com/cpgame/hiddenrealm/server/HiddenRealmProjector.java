package com.cpgame.hiddenrealm.server;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.ResultUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HiddenRealmProjector {
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil oracle = new ResultUtil(rules);

    Map<String, Object> project(SessionState session, CompleteRound round, int index) {
        CompleteRound.Delivery delivery = round.deliveries().get(index);
        BigDecimal betSize = session.betSize;
        int level = session.betLevel;
        BigDecimal charged = rules.chargedBet(betSize, level);
        int deliveryOdds = 0;
        for (CompleteRound.Page page : delivery.pages())
            deliveryOdds += oracle.pageOdds(page.board(), delivery.typeSkill());
        BigDecimal spinWin = oracle.money(deliveryOdds, betSize, level);
        BigDecimal before = session.balance;
        boolean paid = index == 0;
        if (paid) session.balance = money(session.balance.subtract(charged));
        session.balance = money(session.balance.add(spinWin));
        int priorOdds = 0;
        for (int i = 0; i < index; i++)
            for (CompleteRound.Page page : round.deliveries().get(i).pages())
                priorOdds += oracle.pageOdds(page.board(), round.deliveries().get(i).typeSkill());
        BigDecimal cumulative = oracle.money(priorOdds + deliveryOdds, betSize, level);
        int runningCollection = 0;
        if (index > 0) runningCollection = round.deliveries().get(index - 1).collection();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bet", betSize);
        data.put("bet_gold", charged);
        data.put("big_win", cumulative.compareTo(charged.multiply(BigDecimal.valueOf(20))) >= 0);
        data.put("change_gold", money(paid ? spinWin.subtract(charged) : spinWin));
        data.put("end_gold", session.balance);
        data.put("frees", frees(delivery, betSize, charged, level, cumulative));
        data.put("level", level);
        data.put("odds", charged.signum() == 0 ? BigDecimal.ZERO : spinWin.divide(charged, 6, RoundingMode.HALF_UP));
        data.put("oid", String.valueOf(session.roundId + index));
        data.put("props", props(delivery, betSize, level, runningCollection));
        data.put("small_game_type", delivery.smallGameType());
        data.put("start_gold", before);
        data.put("total_num", 0);
        data.put("total_win", spinWin);
        data.put("type", delivery.type());
        data.put("type_skill", delivery.typeSkill());
        return data;
    }

    private List<Map<String, Object>> props(CompleteRound.Delivery delivery, BigDecimal betSize, int level, int collection) {
        List<Map<String, Object>> out = new ArrayList<>();
        int running = collection;
        for (CompleteRound.Page page : delivery.pages()) {
            int[][] board = page.board();
            GameRuleCore.Evaluation ev = rules.evaluatePage(board, delivery.typeSkill());
            running += ev.exploded();
            List<List<Map<String, Object>>> columns = new ArrayList<>();
            for (int c = 0; c < 5; c++) {
                List<Map<String, Object>> cells = new ArrayList<>();
                for (int r = 0; r < 5; r++) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("is_win", ev.win()[c][r] ? 1 : 0);
                    cell.put("prop", board[c][r]);
                    cells.add(cell);
                }
                columns.add(cells);
            }
            List<Map<String, Object>> winArray = new ArrayList<>();
            BigDecimal amount = BigDecimal.ZERO;
            for (GameRuleCore.Cluster cluster : ev.clusters()) {
                BigDecimal win = rules.clusterAmount(cluster.odds(), betSize, level);
                amount = amount.add(win);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("num", cluster.cells().size());
                row.put("odd", cluster.odds());
                row.put("prop", cluster.symbol());
                row.put("win_amout", win);
                winArray.add(row);
                GameRuleCore.Cell mark = cluster.cells().stream()
                        .filter(cell -> board[cell.col()][cell.row()] != GameRuleCore.WILD)
                        .findFirst().orElse(cluster.cells().get(0));
                Map<String, Object> marked = columns.get(mark.col()).get(mark.row());
                marked.put("win_amount", win);
            }
            Map<String, Object> pageJson = new LinkedHashMap<>();
            pageJson.put("props_value", columns);
            pageJson.put("total_amount", money(amount));
            pageJson.put("total_num", running);
            pageJson.put("win_array", winArray);
            pageJson.put("win_num", ev.exploded());
            out.add(pageJson);
        }
        return out;
    }

    private Map<String, Object> frees(CompleteRound.Delivery delivery, BigDecimal bet, BigDecimal charged, int level, BigDecimal cumulative) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bet", bet);
        f.put("bet_amount", charged);
        f.put("level", level);
        f.put("skill_type", delivery.typeSkill());
        f.put("total_num", delivery.collection());
        f.put("total_skill_type", delivery.maxPhase());
        f.put("total_win_amount", cumulative);
        return f;
    }

    static BigDecimal money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    List<Map<String, Object>> paytable() {
        int[][] odds = {
                {1, 2, 3, 4, 6, 8, 10, 15, 15, 15, 20, 20, 40, 40, 40, 100, 100, 100, 200, 200, 200, 200, 300},
                {2, 3, 5, 6, 10, 15, 20, 30, 30, 30, 40, 40, 60, 60, 60, 200, 200, 200, 300, 300, 300, 300, 400},
                {3, 4, 6, 9, 15, 20, 30, 40, 40, 40, 50, 50, 80, 80, 80, 300, 300, 300, 400, 400, 400, 400, 500},
                {4, 5, 10, 15, 20, 30, 40, 50, 50, 50, 60, 60, 100, 100, 100, 500, 500, 500, 600, 600, 600, 600, 600},
                {5, 10, 15, 30, 60, 70, 80, 100, 100, 100, 300, 300, 400, 400, 400, 600, 600, 600, 800, 800, 800, 800, 800},
                {6, 15, 20, 40, 70, 80, 100, 200, 200, 200, 400, 400, 500, 500, 500, 800, 800, 800, 1000, 1000, 1000, 1000, 1000},
                {7, 20, 30, 50, 80, 100, 200, 300, 300, 300, 600, 600, 800, 800, 800, 1000, 1000, 1000, 2000, 2000, 2000, 2000, 5000},
                {8, 30, 40, 70, 100, 200, 300, 500, 500, 500, 1000, 1000, 2000, 2000, 2000, 5000, 5000, 5000, 10000, 10000, 10000, 10000, 20000}
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (int[] row : odds) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (int n = 4; n <= 25; n++) entries.add(Map.of("num", n, "odds", row[n - 3]));
            out.add(Map.of("prop_id", row[0], "odds", entries));
        }
        out.add(Map.of("prop_id", 9, "odds", List.of(Map.of("num", 4, "odds", 0))));
        return out;
    }
}
