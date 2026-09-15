package com.cpgame.batcha.g16;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

/** Optional local-Redis smoke: atomically LPOP one member, verify/decode it, then append it back. */
public final class RedisIntegrationSmoke {
    private RedisIntegrationSmoke() { }

    public static void main(String[] args) throws Exception {
        LoaderConfig config = LoaderConfig.load(Path.of(args[0]));
        MemberCodec codec = new MemberCodec();
        IndependentVerifier verifier = new IndependentVerifier(config.maximumRoundMultiplier(),
            config.maximumCascades(), config.maximumSpecialSpins());
        try (RedisRoundStore store = new RedisRespRoundStore(config.redisHost(), config.redisPort(),
            config.redisPassword(), config.redisDatabase(), config.connectTimeoutMillis(),
            config.readTimeoutMillis())) {
            RedisRoundWriter writer = new RedisRoundWriter(store, codec, verifier,
                config.maximumMembersPerMultiplier());
            RedisRoundWriter.ClaimedRound claimed = writer.claimAny(
                List.of(RoundMode.MARY, RoundMode.FREE), new SecureRandom()).orElseThrow();
            verifier.verifyCodecRoundTrip(claimed.round(), codec);
            writer.write(claimed.round());
            System.out.println("RedisIntegrationSmoke PASS pool=" + claimed.poolKey()
                + " mode=" + claimed.round().mode() + " steps=" + claimed.round().steps().size());
        }
    }
}
