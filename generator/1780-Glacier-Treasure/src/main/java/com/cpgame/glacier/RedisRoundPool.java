package com.cpgame.glacier;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Claims one complete-round ASCII member. Empty pools fail closed. */
public final class RedisRoundPool {
    public record Claimed(String member, boolean special, int ratio) {}

    private final String host;
    private final int port;
    private final int database;
    private final String username;
    private final String password;
    private final long gameId;
    private final SecureRandom random = new SecureRandom();

    public RedisRoundPool(String host, int port, int database, String username, String password, long gameId) {
        this.host=host; this.port=port; this.database=database;
        this.username=username==null?"":username; this.password=password==null?"":password;
        this.gameId=gameId;
    }

    public Claimed claim(Kind kind) throws IOException {
        try (RedisDirectLoader.RedisConnection c = connect()) {
            boolean special = kind==Kind.SPECIAL;
            boolean loss = kind==Kind.LOSS;
            String index = special ? RedisKeys.maryIndex(gameId) : RedisKeys.normalIndex(gameId);
            List<Integer> ratios = ratios(c, index, special, loss);
            while (!ratios.isEmpty()) {
                int ratio = ratios.remove(random.nextInt(ratios.size()));
                String list = special ? RedisKeys.maryList(gameId, ratio) : RedisKeys.normalList(gameId, ratio);
                Object length = c.command("LLEN", list);
                long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
                if (len<=0) continue;
                Object member = c.command("LINDEX", list, Integer.toString(random.nextInt((int)Math.min(len, Integer.MAX_VALUE))));
                if (member==null) continue;
                String ascii = member.toString();
                if (ascii.isEmpty() || ascii.charAt(0)=='{' || ascii.charAt(0)=='[')
                    throw new IllegalStateException("Redis member is JSON");
                CompleteRoundFact fact = new CompleteRoundCodec().decode(ascii, kind==Kind.SPECIAL);
                int actual = CompleteRoundFactory.verifyRatio(fact);
                if (actual!=ratio) throw new IllegalStateException("Redis multiplier mismatch");
                if (fact.special()!=special || (!special && loss!=(actual==0)))
                    throw new IllegalStateException("Redis pool classification mismatch");
                return new Claimed(ascii, special, ratio);
            }
        }
        throw new IllegalStateException("requested Redis cache pool is empty: "+kind);
    }

    private List<Integer> ratios(RedisDirectLoader.RedisConnection c, String index, boolean special, boolean loss)
            throws IOException {
        Object raw = c.command("ZRANGE", index, "0", "-1");
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?> list)
            for (Object v : list) {
                int n = Integer.parseInt(v.toString());
                if (special ? n>=0 : (loss ? n==0 : n>0)) out.add(n);
            }
        return out;
    }

    private RedisDirectLoader.RedisConnection connect() throws IOException {
        return RedisDirectLoader.RedisConnection.connect(host, port, username, password, database,
            false, 5000, 30000);
    }

    public enum Kind { LOSS, WIN, SPECIAL }
}
