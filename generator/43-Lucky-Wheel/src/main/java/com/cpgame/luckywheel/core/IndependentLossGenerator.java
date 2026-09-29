package com.cpgame.luckywheel.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Constructed H0/H1 0-win facts used by the formal realtime generator. */
public final class IndependentLossGenerator {
    public static final int DEFAULT_ATTEMPTS = 5;
    private final SecureRandom random = new SecureRandom();
    private final java.util.Map<Integer, java.util.List<RoundFacts>> defaults = new java.util.HashMap<>();

    public IndependentLossGenerator() {
        for (int profile : new int[]{1, 5}) {
            var pool = new java.util.ArrayList<RoundFacts>(10);
            for (int i = 0; i < 10; i++) {
                RoundFacts f = nextLossCandidate(profile);
                if (ResultUtil.analyze(f).outcome() != OutcomeType.ORDINARY_LOSS)
                    throw new IllegalStateException("invalid default loss");
                pool.add(f);
            }
            defaults.put(profile, java.util.List.copyOf(pool));
        }
    }

    public RoundFacts generate(int betProfile) {
        return generateWithCandidates(betProfile, () -> nextLossCandidate(betProfile));
    }

    RoundFacts generateWithCandidates(int betProfile, java.util.function.Supplier<RoundFacts> proposals) {
        for (int i = 0; i < DEFAULT_ATTEMPTS; i++) {
            RoundFacts facts = proposals.get();
            if (facts == null) continue;
            ResultAnalysis analysis = ResultUtil.analyze(facts);
            if (analysis.outcome() == OutcomeType.ORDINARY_LOSS && !analysis.continuationRequired()) return facts;
        }
        return defaults.get(betProfile == 1 ? 1 : 5).get(random.nextInt(10));
    }

    RoundFacts nextLossCandidate(int betProfile) {
        int n = betProfile == 1 ? 2 : 3;
        List<String> symbols = new ArrayList<>(n);
        for (int i = 0; i < n; i++) symbols.add(random.nextBoolean() ? "H0" : "H1");
        return RoundFacts.ordinary(betProfile, symbols);
    }
}
