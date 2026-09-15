package com.cpgame.replica.beeworkshop;

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
        String host = p.getProperty("redis.host", "18.234.101.161");
        int port = Integer.parseInt(p.getProperty("redis.port", "8021"));
        int db = Integer.parseInt(p.getProperty("redis.database", "0"));
        gameId = Long.parseLong(p.getProperty("redis.game-id", "8002110"));
        RedisKeys.requireGame(gameId);
        if (!host.equals("18.234.101.161") || port != 8021 || db < 0) {
            throw new IllegalArgumentException("Bee Workshop requires Redis 18.234.101.161:8021 DB15");
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
        if (claimed == null) throw new CacheEmptyException("PREGENERATED_CACHE_EMPTY");
        return claimed;
    }

    private Claimed claimKind(GameRuleCore.RoundKind kind) throws IOException {
        boolean special = RedisKeys.special(kind);
        List<Integer> ratios = ratios(special, kind == GameRuleCore.RoundKind.ORDINARY_LOSS, kind != GameRuleCore.RoundKind.ORDINARY_LOSS);
        while (!ratios.isEmpty()) {
            int multiplier = ratios.remove(random.nextInt(ratios.size()));
            Claimed claimed = popMember(special, multiplier);
            if (claimed == null) continue;
            if (claimed.round.kind() != kind) continue;
            return claimed;
        }
        throw new CacheEmptyException("empty " + kind);
    }

    private Claimed claimLoss() throws IOException {
        if (ratios(false, true, false).isEmpty()) return null;
        return popMember(false, 0);
    }

    private Claimed claimWin() throws IOException {
        List<int[]> candidates = new ArrayList<>();
        for (boolean special : new boolean[]{false, true}) {
            for (int multiplier : ratios(special, false, true)) {
                candidates.add(new int[]{special ? 1 : 0, multiplier});
            }
        }
        while (!candidates.isEmpty()) {
            int[] chosen = candidates.remove(random.nextInt(candidates.size()));
            Claimed claimed = popMember(chosen[0] == 1, chosen[1]);
            if (claimed != null) return claimed;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> ratios(boolean special, boolean lossOnly, boolean positiveOnly) throws IOException {
        Object raw = redis.command("ZRANGE", RedisKeys.index(gameId, special), "0", "-1");
        List<Integer> out = new ArrayList<>();
        if (!(raw instanceof List<?> values)) return out;
        for (Object value : values) {
            int multiplier = Integer.parseInt(String.valueOf(value));
            if (lossOnly && multiplier != 0) continue;
            if (positiveOnly && multiplier <= 0) continue;
            Object len = redis.command("LLEN", RedisKeys.list(gameId, special, multiplier));
            if (len instanceof Long n && n > 0) out.add(multiplier);
        }
        return out;
    }

    private Claimed popMember(boolean special, int multiplier) throws IOException {
        String list = RedisKeys.list(gameId, special, multiplier);
        Object popped = redis.command("RPOP", list);
        if (popped == null) return null;
        Object remaining = redis.command("LLEN", list);
        if (remaining instanceof Long n && n == 0) {
            redis.command("ZREM", RedisKeys.index(gameId, special), Integer.toString(multiplier));
        }
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
