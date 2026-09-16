package com.cpgame.g2110.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.g2110.core.*;
import redis.clients.jedis.Jedis;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class RedisRoundRepository implements AutoCloseable {
    private final Jedis jedis;
    private final SecureRandom random = new SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil util = new ResultUtil(rules);

    RedisRoundRepository(Properties p) {
        String host = p.getProperty("redis.host", "18.234.101.161");
        int port = Integer.parseInt(p.getProperty("redis.port", "8021"));
        int db = Integer.parseInt(p.getProperty("redis.database", "0"));
        RedisKeys.requireGame(p.getProperty("redis.game-id", "8002110"));
        if (!host.equals("18.234.101.161") || port != 8021 || db < 0) {
            throw new IllegalArgumentException("Bee Workshop requires Redis 18.234.101.161:8021 DB15");
        }
        jedis = new Jedis(host, port, 3000);
        jedis.connect();
        String password = p.getProperty("redis.password", "").trim();
        if (!password.isEmpty()) jedis.auth(password);
        jedis.select(db);
    }

    synchronized Claimed claim(String requested) {
        if (requested != null && !requested.isBlank()) {
            return claimKind(GameRuleCore.RoundKind.valueOf(requested.toUpperCase(Locale.ROOT)));
        }
        boolean wantWin = random.nextInt(1428) >= 1189;
        Claimed claimed = wantWin ? claimWin() : claimLoss();
        if (claimed == null) throw new CacheEmptyException("empty PerKeyList/MaryKeyList");
        return claimed;
    }

    private Claimed claimKind(GameRuleCore.RoundKind kind) {
        boolean special = RedisKeys.special(kind);
        boolean loss = kind == GameRuleCore.RoundKind.ORDINARY_LOSS;
        var buckets = buckets(special, kind == GameRuleCore.RoundKind.ORDINARY_WIN ? 1 : 0,
                loss ? 0 : Integer.MAX_VALUE);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            Claimed claimed = readMember(special, multiplier);
            if (claimed != null && claimed.round.kind() == kind) return claimed;
        }
        throw new CacheEmptyException("empty " + kind);
    }

    private Claimed claimLoss() { return readMember(false, 0); }

    private Claimed claimWin() {
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

    private RedisFloorLookup.Cursor<RuntimeException> buckets(boolean special, int minimum, int maximum) {
        return RedisFloorLookup.open(this::floorCommand, RedisKeys.index(special),
                m -> RedisKeys.list(special, m), random, minimum, maximum);
    }
    private Object floorCommand(String... args) {
        return switch (args[0]) {
            case "ZREVRANGEBYSCORE" -> jedis.zrevrangeByScore(args[1], args[2], args[3], 0, 1);
            case "LLEN" -> jedis.llen(args[1]);
            default -> throw new IllegalArgumentException("unexpected lookup command");
        };
    }
    

    private long llen(boolean special, int multiplier) {
        return jedis.llen(RedisKeys.list(special, multiplier));
    }

    private Claimed readMember(boolean special, int multiplier) {
        long len = llen(special, multiplier);
        if (len <= 0) return null;
        String member = jedis.lindex(RedisKeys.list(special, multiplier), random.nextInt((int) Math.min(len, Integer.MAX_VALUE)));
        if (member == null) return null;
        var round = codec.decode(member);
        rules.validate(round);
        if (util.integerMultiplier(round) != multiplier) throw new IllegalStateException("bucket/member multiplier mismatch");
        if (RedisKeys.special(round.kind()) != special) throw new IllegalStateException("bucket/member classification mismatch");
        return new Claimed(member, round, multiplier);
    }

    @Override public void close() { jedis.close(); }

    record Claimed(String member, GameRuleCore.CompleteRound round, int multiplier) {}

    static final class CacheEmptyException extends RuntimeException {
        CacheEmptyException(String message) { super(message); }
    }
}
