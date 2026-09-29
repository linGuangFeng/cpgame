package com.cpgame.replica.beeworkshop;

import com.cpgame.demo.redis.RedisFloorLookup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class RedisRoundRepository implements AutoCloseable {
    private final RedisIo redis;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final CompleteRoundCodec codec = new CompleteRoundCodec();
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil util = new ResultUtil(rules);
    private final long gameId;

    RedisRoundRepository(Properties p) throws IOException {
        String host = p.getProperty("redis.host", "54.172.218.28");
        int port = Integer.parseInt(p.getProperty("redis.port", "8016"));
        int db = Integer.parseInt(p.getProperty("redis.database", "0"));
        gameId = Long.parseLong(p.getProperty("redis.game-id", "8002110"));
        RedisKeys.requireGame(gameId);
        boolean nas = "192.168.10.3".equals(host) && port == 6379;
        boolean aws = "18.234.101.161".equals(host) && port == 8021;
        boolean fd = "54.172.218.28".equals(host) && port == 8016;
        boolean local = "127.0.0.1".equals(host) && port == 6379;
        if ((!nas && !aws && !fd && !local) || db < 0) {
            throw new IllegalArgumentException("unauthorized Redis endpoint: " + host + ":" + port);
        }
        redis = RedisIo.connect(host, port, p.getProperty("redis.username", ""), p.getProperty("redis.password", ""),
                db, false, 5000, 30000);
    }

    synchronized Claimed claim(String requested) throws IOException {
        if (requested != null && !requested.isBlank()) {
            return claimKind(GameRuleCore.RoundKind.valueOf(requested.toUpperCase(Locale.ROOT)));
        }
        boolean wantWin = random.nextInt(1929) >= 1485;
        Claimed claimed = wantWin ? claimWin() : claimLoss();
        if (claimed == null) claimed = wantWin ? claimLoss() : claimWin();
        if (claimed == null) throw new CacheEmptyException("PREGENERATED_CACHE_EMPTY");
        return claimed;
    }

    private Claimed claimKind(GameRuleCore.RoundKind kind) throws IOException {
        boolean special = RedisKeys.special(kind);
        boolean loss = kind == GameRuleCore.RoundKind.ORDINARY_LOSS;
        var buckets = buckets(special, loss ? 0 : 1, loss ? 0 : Integer.MAX_VALUE);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            Claimed claimed = readMember(special, multiplier);
            if (claimed != null && claimed.round.kind() == kind) return claimed;
        }
        throw new CacheEmptyException("empty " + kind);
    }

    private Claimed claimLoss() throws IOException { return readMember(false, 0); }

    private Claimed claimWin() throws IOException {
        boolean firstSpecial = random.nextBoolean();
        for (boolean special : new boolean[]{firstSpecial, !firstSpecial}) {
            var buckets = buckets(special, 1, Integer.MAX_VALUE);
            Integer multiplier;
            while ((multiplier = buckets.next()) != null) {
                Claimed claimed = readMember(special, multiplier);
                if (claimed != null) return claimed;
            }
        }
        return null;
    }

    private RedisFloorLookup.Cursor<IOException> buckets(boolean special, int minimum, int maximum) throws IOException {
        return RedisFloorLookup.open(redis::command, RedisKeys.index(gameId, special),
                m -> RedisKeys.list(gameId, special, m), random, minimum, maximum);
    }

    private Claimed readMember(boolean special, int multiplier) throws IOException {
        String list = RedisKeys.list(gameId, special, multiplier);
        long length = Long.parseLong(redis.command("LLEN", list).toString());
        if (length <= 0) return null;
        Object popped = redis.command("LINDEX", list, Long.toString(random.nextLong(length)));
        if (popped == null) return null;
        var round = codec.decode(String.valueOf(popped));
        rules.validate(round);
        if (util.integerMultiplier(round) != multiplier) throw new IllegalStateException("bucket/member multiplier mismatch");
        if (RedisKeys.special(round.kind()) != special) throw new IllegalStateException("bucket/member classification mismatch");
        return new Claimed(String.valueOf(popped), round, multiplier);
    }

    @Override public void close() throws IOException { redis.close(); }

    record Claimed(String member, GameRuleCore.CompleteRound round, int multiplier) {}

    static final class CacheEmptyException extends RuntimeException {
        CacheEmptyException(String message) { super(message); }
    }
}
