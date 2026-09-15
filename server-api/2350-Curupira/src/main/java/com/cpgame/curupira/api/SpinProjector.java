package com.cpgame.curupira.api;

import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.FeatureStep.Role;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpinProjector {
    private SpinProjector() { }

    public static BigDecimal totalBet(BigDecimal lineBet, int level) {
        return GameRuleCore.money(lineBet.multiply(BigDecimal.valueOf((long) level * GameRules.PAYLINE_COUNT)));
    }

    public static BigDecimal stepWin(FeatureStep step, BigDecimal lineBet, int level) {
        if (step.role() == Role.HOLD) {
            return GameRuleCore.money(totalBet(lineBet, level).multiply(BigDecimal.valueOf(step.fcnw())));
        }
        return GameRuleCore.money(lineBet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(step.redisUnits())));
    }

    public static Map<String, Object> data(FeatureStep step, long rid, BigDecimal lineBet, int level,
                                    BigDecimal bg, BigDecimal start, BigDecimal tw, BigDecimal change,
                                    BigDecimal end, long user, String token, int wireType,
                                    BigDecimal twa, boolean idle) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("b", lineBet);
        data.put("bg", idle ? BigDecimal.ZERO.setScale(2) : bg);
        data.put("cg", change);
        data.put("cl", 0);
        data.put("eg", end);
        data.put("f", feature(step, lineBet, level, twa));
        data.put("gt", idle ? 1 : step.gt());
        data.put("l", level);
        data.put("o", step.role() == Role.HOLD ? step.fcnw() * GameRules.PAYLINE_COUNT : step.redisUnits());
        data.put("oid", rid);
        data.put("res", result(step, tw));
        data.put("rid", rid);
        data.put("sg", start);
        data.put("small_game_type", step.role() == Role.ORDINARY ? 0 : 2);
        data.put("t", idle ? 1 : wireType);
        data.put("tw", tw);
        data.put("u", user);
        return data;
    }

    static Object feature(FeatureStep step, BigDecimal lineBet, int level, BigDecimal twa) {
        if (step.role() == Role.ORDINARY) return List.of();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ba", totalBet(lineBet, level));
        f.put("bet", lineBet);
        f.put("fbf", 0);
        f.put("fca", step.role() == Role.HOLD ? GameRules.HOLD_FCA : 0);
        f.put("fcc", step.role() == Role.HOLD ? step.fcc() : GameRules.COIN_TOTAL_COUNT);
        f.put("fcn", step.fcn());
        f.put("fcnw", step.fcnw());
        f.put("fcp", step.role() == Role.HOLD ? step.fcp() : List.of());
        f.put("l", level);
        f.put("st", step.st());
        f.put("t", step.featureT());
        f.put("tt", step.tt());
        f.put("twa", twa);
        return f;
    }

    static Map<String, Object> result(FeatureStep step, BigDecimal tw) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("gt", step.resGt());
        res.put("ps", step.cells());
        if (step.role() == Role.HOLD) {
            res.put("sc", 0);
            res.put("tws", tw);
            res.put("wa", List.of());
            return res;
        }
        EvaluatedBoard board = step.evaluatedBoard();
        List<Map<String, Object>> awards = new ArrayList<>();
        for (Award award : board.awards()) {
            awards.add(Map.of("c", award.c(), "ln", award.ln(), "o", award.o(), "s", award.s()));
        }
        res.put("sc", board.scatterCount());
        res.put("tws", tw);
        res.put("wa", awards);
        return res;
    }
}
