package com.cpgame.luckywheel.core;

/** 从已按规则分类的联合模型直接抽取无奖候选；最多五次校验，十个默认结果兜底。 */
public final class IndependentLossGenerator {
    public static final int DEFAULT_ATTEMPTS = 5;
    private final RandomCandidateGenerator candidates;
    private final java.util.Map<Integer, java.util.List<RoundFacts>> defaults;

    IndependentLossGenerator(RandomCandidateGenerator candidates) {
        this.candidates = candidates;
        defaults = new java.util.HashMap<>();
        for (int profile : new int[]{1, 5}) {
            var pool = new java.util.ArrayList<RoundFacts>(10);
            for (int i = 0; i < 10; i++) {
                RoundFacts f = candidates.nextLossCandidate(profile);
                if (ResultUtil.analyze(f).outcome() != OutcomeType.ORDINARY_LOSS)
                    throw new IllegalStateException("invalid default loss");
                pool.add(f);
            }
            defaults.put(profile, java.util.List.copyOf(pool));
        }
    }

    RoundFacts generate(int betProfile) {
        return generateWithCandidates(betProfile, () -> candidates.nextLossCandidate(betProfile));
    }

    RoundFacts generateWithCandidates(int betProfile, java.util.function.Supplier<RoundFacts> proposals) {
        for (int i = 0; i < DEFAULT_ATTEMPTS; i++) {
            RoundFacts facts = proposals.get();
            if(facts==null)continue;
            ResultAnalysis analysis = ResultUtil.analyze(facts);
            if (analysis.outcome() == OutcomeType.ORDINARY_LOSS && !analysis.continuationRequired()) return facts;
        }
        return defaults.get(betProfile == 1 ? 1 : 5).get(candidates.randomIndex(10));
    }
}
