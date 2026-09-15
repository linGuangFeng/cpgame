package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.model.RoundPlan;

public final class CompleteRoundVerifier {
    private final RoundResultUtil resultUtil;
    private final GeneratorConfiguration configuration;
    private final RoundValidator structuralValidator = new RoundValidator();

    public CompleteRoundVerifier(RoundResultUtil resultUtil, GeneratorConfiguration configuration) {
        this.resultUtil = resultUtil;
        this.configuration = configuration;
    }

    public RoundResultUtil.RoundAnalysis verify(RoundPlan round) {
        structuralValidator.validate(round);
        RoundResultUtil.RoundAnalysis result = resultUtil.analyze(round);
        if (result.multiplier().compareTo(java.math.BigDecimal.valueOf(configuration.maximumMultiplier(result.special()))) > 0) {
            throw new IllegalArgumentException("完整 Round 超过对应奖池累计最大倍数");
        }
        if (result.freeStepCount() > configuration.maxFreeSpins) {
            throw new IllegalArgumentException("完整 Round 超过免费 Spin 上限");
        }
        if (result.longestConsecutiveWins() > configuration.maxConsecutiveWins) {
            throw new IllegalArgumentException("完整 Round 超过连续中奖上限");
        }
        return result;
    }
}
