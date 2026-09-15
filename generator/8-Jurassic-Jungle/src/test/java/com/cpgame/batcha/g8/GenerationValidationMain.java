package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Generate >=10000 complete Rounds and check entry/result/step/state distributions. */
public final class GenerationValidationMain {
    private GenerationValidationMain() { }

    public static void main(String[] args) {
        int target = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(GameRuleCore.MAX_STEPS_OBSERVED);
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), GameRuleCore.MAX_STEPS_OBSERVED);
        MemberCodec codec = new MemberCodec();
        Map<RoundMode, Integer> modes = new EnumMap<>(RoundMode.class);
        Map<Integer, Integer> stepCounts = new HashMap<>();
        Map<Integer, Integer> unitRatios = new HashMap<>();
        int wildCapFail = 0;
        int verifyFail = 0;
        int generated = 0;
        RoundMode[] requests = {RoundMode.LOSS, RoundMode.LOSS, RoundMode.DRAGON};
        long started = System.currentTimeMillis();
        while (generated < target) {
            RoundMode requested = requests[random.nextInt(requests.length)];
            try {
                CompleteRound round = factory.generate(requested, random, new BigDecimal("0.05"), 4);
                verifier.verify(round);
                verifier.verifyCodecRoundTrip(round, codec);
                modes.merge(round.mode(), 1, Integer::sum);
                stepCounts.merge(round.steps().size(), 1, Integer::sum);
                unitRatios.merge(round.unitRatio(), 1, Integer::sum);
                generated++;
                if (generated % 500 == 0) {
                    System.out.println("VALIDATION_PROGRESS generated=" + generated + " modes=" + modes);
                }
            } catch (RuntimeException failure) {
                if (String.valueOf(failure.getMessage()).contains("wild")) wildCapFail++;
                else verifyFail++;
                if (verifyFail > 20) throw failure;
            }
        }
        System.out.println("generation-validation generated=" + generated
            + " elapsedMs=" + (System.currentTimeMillis() - started)
            + " modes=" + modes
            + " wildCapFail=" + wildCapFail
            + " verifyFail=" + verifyFail
            + " uniqueUnitRatios=" + unitRatios.size()
            + " maxSteps=" + stepCounts.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        if (generated < target || modes.getOrDefault(RoundMode.LOSS, 0) < 100
            || modes.getOrDefault(RoundMode.DRAGON, 0) < 50 || verifyFail > 0) {
            System.exit(2);
        }
    }
}
