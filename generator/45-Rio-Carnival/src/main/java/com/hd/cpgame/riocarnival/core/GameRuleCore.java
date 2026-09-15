package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.Map;
/** 唯一规则入口：试玩、服务端和正式 Loader 都通过同一个完整局工厂。 */
public final class GameRuleCore {
    private final CompleteRoundFactory roundFactory;

    public GameRuleCore() { this(new SecureRoundRandom()); }

    public GameRuleCore(RandomSource random) {
        this(random, GameRules.DEFAULT_NORMAL_WEIGHTS, GameRules.DEFAULT_FREE_WEIGHTS);
    }

    public GameRuleCore(RandomSource random, Map<String, Integer> normalWeights,
                        Map<String, Integer> freeWeights) {
        if (random == null) throw new IllegalArgumentException("random 不能为空");
        this.roundFactory = new CompleteRoundFactory(
            new RandomBoardCandidateGenerator(random, normalWeights, freeWeights), random);
    }

    public GeneratedRound generate(BigDecimal betSize, int betLevel) {
        return roundFactory.create(betSize, betLevel);
    }

    public GeneratedRound generateIndependentLoss(BigDecimal bs,int bl){return roundFactory.createIndependentLoss(bs,bl);}
}
