package com.cpgame.junglekings;

import java.security.SecureRandom;
import java.util.List;

/** Connects to an unused Redis database and asserts claimAny fails closed. */
public final class EmptyPoolCheck {
    private EmptyPoolCheck() { }

    public static void main(String[] args) throws Exception {
        int database = args.length > 0 ? Integer.parseInt(args[0]) : 14;
        try (RedisRoundStore store = new RedisRespRoundStore("192.168.10.3", 6379, "", database, 3000, 3000)) {
            RedisRoundWriter writer = new RedisRoundWriter(store, new MemberCodec(), new IndependentVerifier(), 300);
            var claimed = writer.claimAny(List.of(RoundMode.LOSS, RoundMode.WIN), new SecureRandom());
            if (claimed.isPresent()) {
                System.out.println("NOT_EMPTY " + claimed.get().poolKey());
                System.exit(3);
            }
            System.out.println("EMPTY_POOL_OK database=" + database);
        }
    }
}
