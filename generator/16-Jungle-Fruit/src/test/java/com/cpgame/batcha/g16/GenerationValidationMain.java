package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

/** Acceptance-scale independent generation and codec validation. */
public final class GenerationValidationMain {
    private GenerationValidationMain() {}

    public static void main(String[] args) throws Exception {
        int total = args.length == 0 ? 10_000 : Integer.parseInt(args[0]);
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(10, 30);
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 10, 30);
        MemberCodec codec = new MemberCodec();
        Map<RoundMode,Integer> counts = new EnumMap<>(RoundMode.class);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long steps = 0;
        for (int i = 0; i < total; i++) {
            // The published category counts include ordinary-WIN coverage alongside special
            // strata; use their normalized 926:200:164:36 generation-validation mix.
            int draw = random.nextInt(1326);
            RoundMode mode = draw < 926 ? RoundMode.LOSS
                : draw < 1126 ? RoundMode.WIN
                : draw < 1290 ? RoundMode.MARY : RoundMode.FREE;
            CompleteRound round = factory.generate(mode, random, new BigDecimal("0.05"), 1);
            verifier.verifyCodecRoundTrip(round, codec);
            byte[] member = codec.encode(round);
            digest.update(member);
            counts.merge(mode, 1, Integer::sum);
            steps += round.steps().size();
        }
        System.out.println("GENERATION_VALIDATION_PASS rounds=" + total + " steps=" + steps
            + " counts=" + counts + " sha256=" + HexFormat.of().formatHex(digest.digest()));
    }
}
