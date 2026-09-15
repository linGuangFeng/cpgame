package com.cpgame.batcha.g16;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Read-only DB15 pool inventory. */
public final class RedisPoolAuditMain {
    private RedisPoolAuditMain() {}

    public static void main(String[] args) throws Exception {
        LoaderConfig config = LoaderConfig.load(Path.of(args[0]));
        try (RedisRespRoundStore store = new RedisRespRoundStore(config.redisHost(), config.redisPort(),
            config.redisPassword(), config.redisDatabase(), config.connectTimeoutMillis(), config.readTimeoutMillis())) {
            long total = 0;
            for (boolean special : new boolean[] { false, true }) {
                long modeTotal = 0;
                long asciiMembersSampled = 0;
                var ratios = store.ratios(special);
                for (String token : ratios) {
                    int ratio = Integer.parseInt(token);
                    String key = RedisKeys.list(special, ratio);
                    modeTotal += store.listLength(special, ratio);
                    var head = store.peek(key);
                    if (head.isPresent()) {
                        String value = new String(head.get(), StandardCharsets.US_ASCII);
                        if (!value.startsWith("JF16V1.") || !value.matches("[A-Za-z0-9._-]+")) {
                            throw new IllegalStateException("non-ASCII or invalid member envelope at " + key);
                        }
                        asciiMembersSampled++;
                    }
                }
                total += modeTotal;
                System.out.println((special ? "MARY" : "PER") + " buckets=" + ratios.size() + " members=" + modeTotal
                    + " asciiHeads=" + asciiMembersSampled);
            }
            System.out.println("REDIS_POOL_AUDIT_PASS total=" + total + " database=" + config.redisDatabase());
        }
    }
}
