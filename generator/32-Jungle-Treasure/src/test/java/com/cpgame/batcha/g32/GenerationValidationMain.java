package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GenerationValidationMain {
    public static void main(String[] args) {
        int target = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        CompleteRoundFactory factory = new CompleteRoundFactory(12, 10);
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 12, 10);
        MemberCodec codec = new MemberCodec();
        SecureRandom random = new SecureRandom();
        Map<RoundMode, Integer> modes = new EnumMap<>(RoundMode.class);
        Set<String> fingerprints = new HashSet<>();
        int failures = 0;
        int generated = 0;
        while (generated < target) {
            RoundMode mode = RoundMode.values()[random.nextInt(RoundMode.values().length)];
            try {
                CompleteRound round = factory.generate(mode, random, new BigDecimal("0.02"), 10);
                verifier.verify(round);
                verifier.verifyCodecRoundTrip(round, codec);
                String fp = String.join("|", round.steps().getFirst().tokens());
                fingerprints.add(fp);
                modes.merge(round.mode(), 1, Integer::sum);
                generated++;
                if (generated % 500 == 0) System.out.println("GEN_PROGRESS " + generated);
            } catch (RuntimeException error) {
                failures++;
                if (failures <= 8) System.err.println("GEN_FAIL " + error.getMessage());
                if (failures > 200) break;
            }
        }
        System.out.printf("GENERATED=%d failures=%d uniqueInitial=%d modes=%s%n",
            generated, failures, fingerprints.size(), modes);
        if (generated < target || failures != 0) System.exit(1);
    }
}
