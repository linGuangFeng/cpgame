package com.cpgame.christmasgift.api;

import com.cpgame.christmasgift.core.GameRuleCore;
import com.cpgame.christmasgift.core.ResultUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProviderPresenter {
    Map<String,Object> data(GameRuleCore.CompleteRound round, BigDecimal bet, int level,
                            BigDecimal startGold, long oid) {
        GameRuleCore.Evaluation evaluation = ResultUtil.evaluate(round);
        BigDecimal betGold = GameRuleCore.betGold(bet, level);
        BigDecimal totalWin = GameRuleCore.totalWin(bet, level, evaluation.multiplier());
        BigDecimal changeGold = totalWin.subtract(betGold);
        BigDecimal endGold = startGold.add(changeGold);
        List<Map<String,Object>> steps = new ArrayList<>();
        for (int index = 0; index < round.steps().size(); index++) {
            Map<String,Object> step = new LinkedHashMap<>();
            step.put("props", round.steps().get(index).newlyDealt());
            List<Map<String,Object>> wins = new ArrayList<>();
            for (GameRuleCore.Win win : evaluation.steps().get(index).wins()) {
                Map<String,Object> item = new LinkedHashMap<>();
                if (win.multiple() != 1) item.put("multiple", win.multiple());
                item.put("prop",win.prop()); item.put("roll",win.roll());
                item.put("win_amount", bet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(win.winOdd() * (long)win.multiple())));
                item.put("win_odd",win.winOdd());
                wins.add(item);
            }
            step.put("win_arrs",wins);
            steps.add(step);
        }
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("bet",bet); data.put("bet_gold",betGold); data.put("big",evaluation.bigWin()?1:0);
        data.put("big_win",evaluation.bigWin()); data.put("change_gold",changeGold); data.put("end_gold",endGold);
        data.put("level",level); data.put("luck_prop",round.targetSymbol()); data.put("odds",GameRuleCore.displayedOdds(evaluation.multiplier()));
        data.put("oid",oid); data.put("props",steps);
        if (round.mode()==GameRuleCore.Mode.CHRISTMAS_GIFT_FEATURE) data.put("small_game_type",3);
        data.put("start_gold",startGold); data.put("total_win",totalWin);
        data.put("type",round.mode()==GameRuleCore.Mode.ORDINARY?1:2);
        return data;
    }
}
