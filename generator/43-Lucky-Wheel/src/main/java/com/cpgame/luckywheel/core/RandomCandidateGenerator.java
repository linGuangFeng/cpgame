package com.cpgame.luckywheel.core;

import java.util.Map;
import java.util.random.RandomGenerator;

/** 随机候选只从完整局联合模型产生不可重算事实，不计算派奖。 */
public final class RandomCandidateGenerator {
    private final RandomGenerator random;
    private final JointRoundModel lockedModel;
    private final JointRoundModel unlockedModel;

    RandomCandidateGenerator(RandomGenerator random, Map<String, Integer> outcomeWeights,
                             Map<String, Integer> jointStateWeights) {
        this.random = random;
        this.lockedModel = new JointRoundModel(random, 1, outcomeWeights, jointStateWeights);
        this.unlockedModel = new JointRoundModel(random, 5, outcomeWeights, jointStateWeights);
    }

    RoundFacts nextCandidate(int betProfile) {
        return (betProfile == 1 ? lockedModel : unlockedModel).sample();
    }

    RoundFacts nextLossCandidate(int betProfile) {
        return (betProfile == 1 ? lockedModel : unlockedModel).sample(OutcomeType.ORDINARY_LOSS);
    }

    int randomIndex(int bound) { return random.nextInt(bound); }

    String nextRoundKey() {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        StringBuilder value = new StringBuilder("R43-");
        for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }
}
