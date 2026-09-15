package com.cpgame.glacier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Projects a complete-round fact into original gameResult deliveries. */
public final class ProtocolProjector {
    private static final AtomicLong OIDS = new AtomicLong(1780_000_000_000L);

    public List<Map<String,Object>> deliveries(CompleteRoundFact fact, BigDecimal betSize, int level,
                                               BigDecimal startBalance, boolean featureBuy) {
        GameRuleCore core = new GameRuleCore();
        BigDecimal unit = core.stakeUnit(betSize, level);
        BigDecimal betGold = core.betAmount(betSize, level);
        BigDecimal debit = featureBuy ? core.buyCost(betSize, level) : betGold;
        BigDecimal cursor = startBalance;
        var out = new ArrayList<Map<String,Object>>();
        int freeAward = 0;
        int freeRemaining = 0;
        int freeTotal = 0;
        int freeMult = 2;
        BigDecimal freeWin = BigDecimal.ZERO;
        for (int si=0; si<fact.spins().size(); si++) {
            boolean free = si>0;
            int mult = free ? freeMult : 1;
            var pages = fact.spins().get(si);
            var props = new ArrayList<Object>();
            BigDecimal spinWin = BigDecimal.ZERO;
            int endMult = mult;
            GameRuleCore.Evaluation last = null;
            for (var board : pages) {
                var eval = core.evaluate(board, unit, mult);
                props.add(core.stepJson(board, eval));
                spinWin = spinWin.add(eval.total());
                last = eval;
                if (!eval.terminal()) {
                    endMult = core.nextMultiplier(mult, free, true);
                    mult = endMult;
                }
            }
            int nums = last==null ? 0 : last.scatters();
            int newTimes = 0;
            if (!free) {
                newTimes = core.initialFreeAward(core.scatterSymbolCount(pages.get(pages.size()-1)));
                freeAward = newTimes;
                freeTotal = newTimes;
                freeRemaining = newTimes;
                freeMult = 2;
                freeWin = BigDecimal.ZERO;
            } else {
                freeWin = freeWin.add(spinWin);
                freeRemaining = Math.max(0, freeRemaining-1);
                freeMult = endMult;
            }
            BigDecimal charged = free ? BigDecimal.ZERO : debit;
            BigDecimal start = cursor;
            BigDecimal change = spinWin.subtract(charged);
            cursor = start.add(change);
            Map<String,Object> frees;
            if (freeTotal==0) {
                frees = freeZero();
            } else if (!free) {
                frees = freeMap(betSize, charged, level, 2, freeRemaining, freeTotal, BigDecimal.ZERO);
            } else {
                frees = freeMap(betSize, BigDecimal.ZERO, level, endMult, freeRemaining, freeTotal, freeWin);
            }
            Map<String,Object> nf = new LinkedHashMap<>();
            nf.put("new_times", newTimes);
            nf.put("nums", nums);
            nf.put("prop", 12);
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("bet", betSize);
            data.put("bet_gold", betGold);
            data.put("big_win", core.bigWinFlag(spinWin, betGold));
            data.put("change_gold", change);
            data.put("end_gold", cursor);
            data.put("frees", frees);
            data.put("gid", 1780);
            data.put("level", level);
            data.put("new_free", nf);
            data.put("odds", spinWin.signum()==0 ? BigDecimal.ZERO
                : spinWin.divide(betGold, 2, RoundingMode.HALF_UP));
            data.put("oid", OIDS.incrementAndGet());
            data.put("props", props);
            data.put("small_game_type", 0);
            data.put("start_gold", start);
            data.put("total_win", spinWin);
            data.put("type", free ? 2 : 1);
            out.add(data);
        }
        return out;
    }

    private static Map<String,Object> freeZero() {
        var m=new LinkedHashMap<String,Object>();
        m.put("bet", 0); m.put("bet_amount", 0); m.put("fw", 0); m.put("last_round_id_win", 0);
        m.put("level", 0); m.put("multiple", 0); m.put("surplus_times", 0);
        m.put("total_times", 0); m.put("total_win_amount", 0);
        return m;
    }

    private static Map<String,Object> freeMap(BigDecimal bet, BigDecimal betAmount, int level,
                                              int multiple, int surplus, int total, BigDecimal twa) {
        var m=new LinkedHashMap<String,Object>();
        m.put("bet", bet);
        m.put("bet_amount", betAmount);
        m.put("free_origin_type", 3);
        m.put("fw", 0);
        m.put("last_round_id_win", 0);
        m.put("level", level);
        m.put("multiple", multiple);
        m.put("surplus_times", surplus);
        m.put("total_times", total);
        m.put("total_win_amount", twa);
        return m;
    }
}
