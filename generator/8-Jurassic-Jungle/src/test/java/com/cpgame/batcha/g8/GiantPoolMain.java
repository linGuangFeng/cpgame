package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Extra Loader pass that only writes complete Rounds that reach Giant (remove_status=4). */
public final class GiantPoolMain {
    public static void main(String[] args) throws Exception {
        LoaderConfig config = LoaderConfig.load(Path.of(
            args.length > 0 ? args[0] : "generator/8-Jurassic-Jungle/dist/generator.properties"));
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(config.maximumSteps());
        IndependentVerifier verifier = new IndependentVerifier(config.maximumRoundMultiplier(), config.maximumSteps());
        MemberCodec codec = new MemberCodec();
        int want = 30, written = 0, candidates = 0;
        try (RedisRoundStore store = new RedisRespRoundStore(config.redisHost(), config.redisPort(),
            config.redisPassword(), config.redisDatabase(), config.connectTimeoutMillis(),
            config.readTimeoutMillis())) {
            RedisRoundWriter writer = new RedisRoundWriter(store, codec, verifier, config.maximumMembersPerMultiplier());
            while (written < want && candidates < 20000) {
                CompleteRound round = factory.generate(RoundMode.DRAGON, random, config.betSize(), config.betLevel());
                candidates++;
                boolean giant = round.steps().stream().anyMatch(step -> step.removeStatus() == 4);
                if (!giant) continue;
                verifier.verify(round);
                writer.write(round);
                written++;
                System.out.println("GIANT_WRITTEN n=" + written + " steps=" + round.steps().size()
                    + " units=" + round.unitRatio() + " candidates=" + candidates);
            }
        }
        if (written < want) throw new IllegalStateException("giant pool short " + written);
        System.out.println("GIANT_POOL_DONE written=" + written + " candidates=" + candidates);
    }
}
