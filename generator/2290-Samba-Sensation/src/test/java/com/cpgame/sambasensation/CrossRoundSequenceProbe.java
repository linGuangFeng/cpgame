package com.cpgame.sambasensation;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.generator.CompleteRoundFactory;
import com.cpgame.sambasensation.generator.GeneratorConfig;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.SplittableRandom;

/** 测试专用：100个连续Session各100局；正式Loader不读取seed。 */
public final class CrossRoundSequenceProbe {
    private CrossRoundSequenceProbe() { }

    public static void main(String[] args) throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        CompleteRoundFactory factory = new CompleteRoundFactory(config);
        SplittableRandom random = new SplittableRandom(22900002L);
        EnumMap<GameRuleCore.CollectionBranch, Integer> branches = new EnumMap<>(GameRuleCore.CollectionBranch.class);
        int applied = 0, rejectedIncompatible = 0, scatterViolations = 0, coinViolations = 0;
        for (int session = 0; session < 100; session++) {
            GameRuleCore.CollectionState state = GameRuleCore.CollectionState.initial();
            for (int round = 0; round < 100; round++) {
                CompleteRoundFactory.GeneratedRound generated = null;
                for (int attempt = 0; attempt < 10000; attempt++) {
                    CompleteRoundFactory.GeneratedRound candidate = factory.generateNatural(random);
                    if (GameRuleCore.canApplyCollectionTransition(state, candidate.fact())) { generated = candidate; break; }
                    rejectedIncompatible++;
                }
                if (generated == null) throw new IllegalStateException("no applicable generated member");
                int beforeCoin = GameRuleCore.coinCount(state.coins());
                int expectedScatter = (state.scatterProgress() + generated.fact().scatterDelta()) % GameRuleCore.SCATTER_METER_SIZE;
                GameRuleCore.CollectionProjection projection = GameRuleCore.applyCollectionTransition(state, generated.fact());
                if (projection.scatterProgress() != expectedScatter) scatterViolations++;
                int afterCoin = GameRuleCore.coinCount(projection.coins());
                switch (projection.branch()) {
                    case UNCHANGED -> { if (afterCoin != beforeCoin) coinViolations++; }
                    case INCREMENT_NONDECREASING -> { if (afterCoin <= beforeCoin) coinViolations++; }
                    case FULL_TRIGGER -> { if (!projection.fullReward() || afterCoin != generated.fact().coinRewardCount()) coinViolations++; }
                    case RESET_AFTER_FULL -> { if (afterCoin != 0) coinViolations++; }
                }
                branches.merge(projection.branch(), 1, Integer::sum);
                state = projection.nextState();
                applied++;
            }
        }
        System.out.println("sessions=100");
        System.out.println("generatedRoundCount=" + applied);
        System.out.println("rejectedIncompatible=" + rejectedIncompatible);
        System.out.println("branches=" + branches);
        System.out.println("scatterTransitionViolations=" + scatterViolations);
        System.out.println("coinTransitionViolations=" + coinViolations);
    }
}
