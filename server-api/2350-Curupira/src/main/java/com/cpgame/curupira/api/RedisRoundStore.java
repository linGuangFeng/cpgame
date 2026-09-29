package com.cpgame.curupira.api;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.redis.RedisContractGate;
import com.cpgame.curupira.verify.RoundVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Redis-only source for normal paid starts and the two independent Mary families. */
public final class RedisRoundStore implements RoundSource {
    private static final Logger LOG = Logger.getLogger(RedisRoundStore.class.getName());

    private final int gameId;
    private final SecureRandom random = new SecureRandom();
    private final RedisCommands redis;
    private final MinimalFactCodec codec = new MinimalFactCodec();
    private final ResultUtil resultUtil = new ResultUtil();
    private final RoundVerifier verifier = new RoundVerifier();
    private final RedisContractGate keys = new RedisContractGate();

    RedisRoundStore(RedisCommands redis) { this(redis, 2350); }
    RedisRoundStore(RedisCommands redis, int gameId) {
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        this.redis = redis;
        this.gameId = gameId;
    }

    public static RedisRoundStore connect(Properties config) throws IOException {
        int gameId = Integer.parseInt(config.getProperty("redis.game-id", "8002350"));
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(SocketRedisCommands.connect(config), gameId);
    }

    @Override
    public synchronized CompleteRoundFact peekLoss() {
        return find(Kind.LOSS, 0, fact -> fact.kind() == Kind.LOSS);
    }

    @Override
    public synchronized CompleteRoundFact claimPaidAtOrBelow(int targetMultiplier) {
        return find(Kind.LOSS, targetMultiplier,
                fact -> fact.kind() == Kind.LOSS || fact.kind() == Kind.WIN
                        || fact.kind() == Kind.EXPANDING_WILD || fact.kind() == Kind.TRIGGER);
    }

    @Override
    public synchronized CompleteRoundFact claimMaryAtOrBelow(Kind kind, int targetMultiplier) {
        if (kind != Kind.FREE_EW && kind != Kind.HOLD) {
            throw new IllegalArgumentException("Mary kind must be FREE_EW or HOLD");
        }
        return find(kind, targetMultiplier, fact -> fact.kind() == kind);
    }

    private CompleteRoundFact find(Kind keyKind, int targetMultiplier, Predicate<CompleteRoundFact> accepted) {
        if (targetMultiplier < 0) throw new IllegalArgumentException("target multiplier cannot be negative");
        try {
            Object raw = redis.command("ZREVRANGEBYSCORE", keys.indexFor(keyKind, gameId),
                    Integer.toString(targetMultiplier), "0");
            if (!(raw instanceof List<?> buckets)) throw new IOException("Redis multiplier index reply is invalid");
            for (Object bucket : buckets) {
                int multiplier = Integer.parseInt(text(bucket));
                CompleteRoundFact fact = scanList(keys.listFor(keyKind, multiplier, gameId), multiplier, accepted);
                if (fact != null) {
                    LOG.info(() -> "Curupira Redis hit gameId=" + gameId + " requestedKind=" + keyKind
                            + " actualKind=" + fact.kind() + " target=" + targetMultiplier
                            + " actual=" + multiplier + " memberSha256=" + memberHash(codec.encode(fact)));
                    return fact;
                }
            }
            throw empty("no valid " + keyKind + " member at or below multiplier " + targetMultiplier);
        } catch (IOException | NumberFormatException failure) {
            throw empty(failure.getMessage());
        }
    }

    private CompleteRoundFact scanList(String listKey, int multiplier,
                                       Predicate<CompleteRoundFact> accepted) throws IOException {
        long length = Long.parseLong(text(redis.command("LLEN", listKey)));
        if (length <= 0) return null;
        long start = random.nextLong(length);
        for (long visited = 0; visited < length; visited++) {
            Object raw = redis.command("LINDEX", listKey, Long.toString((start + visited) % length));
            if (raw == null) continue;
            String member = text(raw);
            if (member.startsWith("{") || member.startsWith("[")) continue;
            try {
                CompleteRoundFact fact = codec.decode(member);
                verifier.verifyFact(fact);
                int derived = resultUtil.redisMultiplier(fact);
                if (derived != multiplier || derived != fact.redisMultiplier() || !accepted.test(fact)) continue;
                return fact;
            } catch (RuntimeException invalidLegacyMember) {
                // Invalid legacy markers and malformed members are skipped; they are never regenerated live.
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    private static String memberHash(String member) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(member.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException empty(String detail) {
        return new IllegalStateException("Curupira Redis round cache unavailable or empty: " + detail);
    }

    @Override public void close() {
        try { redis.close(); } catch (IOException ignored) { }
    }
}
