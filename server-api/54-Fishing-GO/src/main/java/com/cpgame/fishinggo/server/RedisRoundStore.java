package com.cpgame.fishinggo.server;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.RedisKeys;
import com.cpgame.fishinggo.core.ResultUtil;
import com.cpgame.fishinggo.core.RoundCodec;
import com.cpgame.fishinggo.core.RoundGenerator;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class RedisRoundStore implements AutoCloseable {
    enum Selection {
        LOSS, SMALL_WIN, BIG_WIN, MEGA_WIN, SUPER_WIN, WIN,
        SPECIAL, SPECIAL_X1, SPECIAL_X2, SPECIAL_X3, ANY
    }
    record Claim(String member, CompleteRound round, ResultUtil.Analysis analysis) {}
    static final class CacheEmptyException extends RuntimeException {
        CacheEmptyException(String m) { super(m); }
    }

    private final Jedis jedis;
    private final RoundCodec codec = new RoundCodec();
    private final RoundGenerator generator = new RoundGenerator(new SecureRandom());
    private final ResultUtil util = new ResultUtil();
    private final SecureRandom random = new SecureRandom();

    RedisRoundStore(Properties p) {
        String host = p.getProperty("redis.host");
        int port = Integer.parseInt(p.getProperty("redis.port", "8021"));
        int db = Integer.parseInt(p.getProperty("redis.database", "0"));
        if (!"18.234.101.161".equals(host) || port != 8021 || db < 0)
            throw new IllegalArgumentException("authorized Redis endpoint is 18.234.101.161:8021 DB15");
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder().database(db)
                .connectionTimeoutMillis(Integer.parseInt(p.getProperty("redis.connect-timeout-ms", "5000")))
                .socketTimeoutMillis(Integer.parseInt(p.getProperty("redis.socket-timeout-ms", "10000")));
        String username = p.getProperty("redis.username", "").strip();
        String password = p.getProperty("redis.password", "");
        if (!username.isEmpty()) cfg.user(username);
        if (!password.isEmpty()) cfg.password(password);
        cfg.ssl(Boolean.parseBoolean(p.getProperty("redis.ssl", "false")));
        jedis = new Jedis(host, port, cfg.build());
        jedis.connect();
        if (!"PONG".equalsIgnoreCase(jedis.ping())) throw new IllegalStateException("redis ping failed");
    }

    synchronized Claim claim(Selection selection) {
        CacheEmptyException last = null;
        for (Selection candidate : fallbacks(selection)) {
            try {
                return claimExact(candidate);
            } catch (CacheEmptyException e) {
                last = e;
            }
        }
        throw last != null ? last : new CacheEmptyException("selected pool empty");
    }

    private Claim claimExact(Selection selection) {
        List<int[]> available = new ArrayList<>();
        int wantScatter = 0;
        switch (selection) {
            case LOSS -> collect(available, false, 0, 0);
            case SMALL_WIN -> collect(available, false, 1, 100);
            case BIG_WIN -> collect(available, false, 101, 399);
            case MEGA_WIN -> collect(available, false, 400, 599);
            case SUPER_WIN -> collect(available, false, 600, Integer.MAX_VALUE);
            case WIN -> collect(available, false, 1, Integer.MAX_VALUE);
            case SPECIAL -> collect(available, true, 0, Integer.MAX_VALUE);
            case SPECIAL_X1 -> { collect(available, true, 0, Integer.MAX_VALUE); wantScatter = 5; }
            case SPECIAL_X2 -> { collect(available, true, 0, Integer.MAX_VALUE); wantScatter = 6; }
            case SPECIAL_X3 -> { collect(available, true, 0, Integer.MAX_VALUE); wantScatter = 7; }
            case ANY -> {
                if (random.nextBoolean()) collect(available, false, 0, 0);
                else {
                    collect(available, false, 1, Integer.MAX_VALUE);
                    collect(available, true, 0, Integer.MAX_VALUE);
                }
            }
        }
        if (available.isEmpty()) throw new CacheEmptyException("selected pool empty");
        Claim last = null;
        int tries = Math.max(24, available.size());
        for (int i = 0; i < tries; i++) {
            last = read(available.get(random.nextInt(available.size())), -1);
            if (matchesScatter(last, wantScatter)) return last;
        }
        if (wantScatter > 0) {
            for (int[] chosen : available) {
                long len = jedis.llen(listKey(chosen));
                int n = (int) Math.min(len, 8);
                for (int i = 0; i < n; i++) {
                    last = read(chosen, i);
                    if (matchesScatter(last, wantScatter)) return last;
                }
            }
        }
        if (last == null) throw new CacheEmptyException("member exhausted");
        return last;
    }

    private static List<Selection> fallbacks(Selection selection) {
        return switch (selection) {
            case LOSS -> List.of(Selection.LOSS);
            case SMALL_WIN -> List.of(Selection.SMALL_WIN, Selection.WIN);
            case BIG_WIN -> List.of(Selection.BIG_WIN, Selection.MEGA_WIN, Selection.SUPER_WIN, Selection.SMALL_WIN, Selection.WIN);
            case MEGA_WIN -> List.of(Selection.MEGA_WIN, Selection.SUPER_WIN, Selection.BIG_WIN, Selection.WIN);
            case SUPER_WIN -> List.of(Selection.SUPER_WIN, Selection.MEGA_WIN, Selection.BIG_WIN, Selection.WIN);
            case SPECIAL_X1 -> List.of(Selection.SPECIAL_X1, Selection.SPECIAL);
            case SPECIAL_X2 -> List.of(Selection.SPECIAL_X2, Selection.SPECIAL);
            case SPECIAL_X3 -> List.of(Selection.SPECIAL_X3, Selection.SPECIAL);
            case SPECIAL -> List.of(Selection.SPECIAL);
            case WIN -> List.of(Selection.WIN);
            case ANY -> List.of(Selection.ANY);
        };
    }

    private Claim read(int[] chosen, int index) {
        String list = listKey(chosen);
        long len = jedis.llen(list);
        if (len <= 0) throw new CacheEmptyException("member exhausted");
        int at = index >= 0 ? index : random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
        String member = jedis.lindex(list, at);
        if (member == null) throw new CacheEmptyException("member exhausted");
        CompleteRound round = codec.decode(member, generator);
        ResultUtil.Analysis a = util.analyze(round);
        if (a.odds() != chosen[1]) throw new IllegalStateException("member multiplier");
        return new Claim(member, round, a);
    }

    private boolean matchesScatter(Claim claim, int wantScatter) {
        if (wantScatter <= 0) return true;
        return util.scatter(claim.round().steps().get(0).board()) == wantScatter;
    }

    private static String listKey(int[] chosen) {
        return chosen[0] == 1 ? RedisKeys.maryList(chosen[1]) : RedisKeys.normalList(chosen[1]);
    }

    private void collect(List<int[]> available, boolean special, int minOdds, int maxOdds) {
        String index = special ? RedisKeys.maryIndex() : RedisKeys.normalIndex();
        for (String entry : jedis.zrange(index, 0, -1)) {
            int m;
            try {
                m = Integer.parseInt(entry);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (m < minOdds || m > maxOdds) continue;
            String list = special ? RedisKeys.maryList(m) : RedisKeys.normalList(m);
            if (jedis.llen(list) > 0) available.add(new int[] {special ? 1 : 0, m});
        }
    }

    @Override public void close() { jedis.close(); }
}
