package com.cpgame.hiddenrealm.loader;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.DealingModel;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.ResultUtil;
import com.cpgame.hiddenrealm.core.RoundCodec;
import com.cpgame.hiddenrealm.core.RoundGenerator;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 100 origin holdout rounds (ResultUtil vs captured win_array) plus 10000 newly generated rounds.
 */
public final class GenerationModelValidation {
    public static void main(String[] args) throws Exception {
        GameRuleCore rules = new GameRuleCore();
        ResultUtil util = new ResultUtil(rules);
        RoundCodec codec = new RoundCodec();
        RoundGenerator generator = new RoundGenerator(new SecureRandom(), rules);
        OriginHoldout.check(rules, util);
        int loss = 0, win = 0, special = 0, pages = 0;
        int[] phase = new int[5];
        int[] initial = new int[10];
        int initialCells = 0;
        for (int i = 0; i < 10000; i++) {
            CompleteRound round = generator.natural();
            rules.validateRound(round);
            ResultUtil.Analysis a = util.analyze(round);
            if (a.oddsSum() != round.totalOdds()) throw new IllegalStateException("oracle mismatch");
            String member = codec.encode(round);
            if (!member.equals(codec.encode(codec.decode(member)))) throw new IllegalStateException("codec");
            if (member.startsWith("{") || member.startsWith("[")) throw new IllegalStateException("json member");
            int[][] first = round.deliveries().get(0).pages().get(0).board();
            for (int c = 0; c < 5; c++) {
                int colWild = 0;
                for (int r = 0; r < 5; r++) {
                    int s = first[c][r];
                    initial[s]++;
                    initialCells++;
                    if (s == 9) colWild++;
                }
                if (colWild > DealingModel.WILD_INIT_COL) throw new IllegalStateException("initial col wild cap");
            }
            if (rules.wildCount(first) > DealingModel.WILD_INIT_PAGE) throw new IllegalStateException("initial page wild cap");
            switch (a.outcome()) {
                case NORMAL_LOSS -> loss++;
                case NORMAL_WIN -> win++;
                case SPECIAL -> special++;
            }
            phase[round.maxPhase()]++;
            pages += a.pages();
        }
        System.out.printf(Locale.ROOT,
                "generation-model-validation PASS generated=10000 loss=%d win=%d special=%d pages=%d phases=%d/%d/%d/%d/%d rulesHash=%s initialWildPct=%.4f%n",
                loss, win, special, pages, phase[0], phase[1], phase[2], phase[3], phase[4],
                GameRuleCore.RULES_HASH, 100.0 * initial[9] / initialCells);
    }
}
