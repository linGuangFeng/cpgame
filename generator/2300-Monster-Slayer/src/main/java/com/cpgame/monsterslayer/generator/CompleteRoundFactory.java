package com.cpgame.monsterslayer.generator;

import com.cpgame.monsterslayer.core.GameRuleCore;
import com.cpgame.monsterslayer.core.MinimalRoundFactCodec;
import com.cpgame.monsterslayer.core.ResultUtil;
import java.security.SecureRandom;
import java.util.List;

/** Ordinary generation and rule-driven complete hunting rounds. */
public final class CompleteRoundFactory {
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    private final int[] normalWeights;
    private final int[] maryWeights;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;

    public CompleteRoundFactory() {
        this(MonsterSlayerBoardGenerator.defaultNormalWeights(), MonsterSlayerBoardGenerator.defaultMaryWeights());
    }

    public CompleteRoundFactory(int[] normalWeights) {
        this(normalWeights, MonsterSlayerBoardGenerator.defaultMaryWeights());
    }

    public CompleteRoundFactory(int[] normalWeights, int[] maryWeights) {
        this.normalWeights = MonsterSlayerBoardGenerator.validatedWeights(normalWeights, "normal");
        this.maryWeights = MonsterSlayerBoardGenerator.validatedWeights(maryWeights, "Mary");
        var defaultsRandom=new SecureRandom();
        losses=new ZeroLossSupport<>(()->lossCandidate(defaultsRandom),r->ResultUtil.evaluate(r).multiplierCenti()==0,r->r);
    }

    public GameRuleCore.CompleteRound generateLoss(SecureRandom random) { return losses.generate(()->lossCandidate(random),random::nextInt); }
    public GameRuleCore.CompleteRound generateWin(SecureRandom random) { return ordinary(random, true); }

    private GameRuleCore.CompleteRound ordinary(SecureRandom random, boolean winning) {
        MonsterSlayerBoardGenerator boards = new MonsterSlayerBoardGenerator(random, normalWeights, maryWeights);
        for (int attempt = 0; attempt < 100000; attempt++) {
            int[] board = boards.generateOrdinary();
            if (!MonsterSlayerBoardGenerator.legalOrdinary(board)) continue;
            GameRuleCore.CompleteRound round = new GameRuleCore.CompleteRound(false,
                    List.of(new GameRuleCore.Step(board, 0, 0)));
            if ((ResultUtil.evaluate(round).multiplierCenti() > 0) == winning) return round;
        }
        throw new IllegalStateException("ordinary per-cell model exhausted rejection limit");
    }

    private final MonsterFeatureGenerator features = new MonsterFeatureGenerator();
    public GameRuleCore.CompleteRound generateSpecial(SecureRandom random) { return features.generate(0, random); }
    public GameRuleCore.CompleteRound generateBuy(int buyType, SecureRandom random) { return features.generate(buyType, random); }

    public GameRuleCore.CompleteRound lossCandidate(SecureRandom random){
        int[] b=new MonsterSlayerBoardGenerator(random,normalWeights,maryWeights).generateLossCandidate();
        return new GameRuleCore.CompleteRound(false,List.of(new GameRuleCore.Step(b,0,0)));
    }
}
