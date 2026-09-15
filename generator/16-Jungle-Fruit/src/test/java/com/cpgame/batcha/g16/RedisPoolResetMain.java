package com.cpgame.batcha.g16;

import java.nio.file.Path;

/** Deletes only Jungle Fruit gid-16 PerKeyList/MaryKeyList and matching BetLog/MaryLog lists. */
public final class RedisPoolResetMain {
    private RedisPoolResetMain() {}

    public static void main(String[] args) throws Exception {
        LoaderConfig config = LoaderConfig.load(Path.of(args[0]));
        try (RedisRespRoundStore store = new RedisRespRoundStore(config.redisHost(), config.redisPort(),
            config.redisPassword(), config.redisDatabase(), config.connectTimeoutMillis(), config.readTimeoutMillis())) {
            long deleted = 0;
            for (boolean special : new boolean[] { false, true }) {
                for (String ratio : store.ratios(special)) {
                    deleted += store.delete(RedisKeys.list(special, Integer.parseInt(ratio)));
                }
                deleted += store.delete(RedisKeys.index(special));
            }
            System.out.println("REDIS_POOL_RESET_PASS exactKeysDeleted=" + deleted + " database=" + config.redisDatabase());
        }
    }
}
