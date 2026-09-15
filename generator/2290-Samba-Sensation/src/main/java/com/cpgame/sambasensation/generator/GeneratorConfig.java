package com.cpgame.sambasensation.generator;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** 正式配置解析；每一个非注释键均在这里读取，未知键直接拒绝。 */
public final class GeneratorConfig {
    public final String redisHost;
    public final int redisPort;
    public final String redisUsername;
    public final String redisPassword;
    public final int redisDatabase;
    public final boolean redisSsl;
    public final int connectTimeoutMs;
    public final int socketTimeoutMs;
    public final long redisGameId;
    public final int normalCount;
    public final int specialCount;
    public final int batchSize;
    public final int maxMembersPerMultiplier;
    public final int specialMaxMembersPerMultiplier;
    public final int maxConsecutiveWins;
    public final int normalMinMultiplier;
    public final int specialMinMultiplier;
    public final int normalMaxMultiplier;
    public final int specialMaxMultiplier;
    public final int maxFreeSpins;
    public final int[][][] paidWeights = new int[3][3][11];
    public final int[][] freeOuterWeights = new int[3][10];
    public final int[][] freeBigWeights = new int[3][10];
    public final int[] coinTransitionWeights = new int[2];

    private final Properties values;
    private final Set<String> readKeys = new LinkedHashSet<>();

    private GeneratorConfig(Properties values) {
        this.values = values;
        redisHost = text("redis.host");
        redisPort = number("redis.port", 1, 65535);
        redisUsername = textAllowBlank("redis.username");
        redisPassword = textAllowBlank("redis.password");
        redisDatabase = number("redis.database", 0, 15);
        redisSsl = bool("redis.ssl");
        connectTimeoutMs = number("redis.connect-timeout-ms", 100, 120000);
        socketTimeoutMs = number("redis.socket-timeout-ms", 100, 120000);
        redisGameId = longNumber("redis.game-id", 1, Long.MAX_VALUE);
        normalCount = number("generation.normal-count", 1, Integer.MAX_VALUE);
        specialCount = number("generation.special-count", 2, Integer.MAX_VALUE);
        batchSize = number("generation.batch-size", 1, 10000);
        maxMembersPerMultiplier = number("generation.max-members-per-multiplier", 1, 100000);
        specialMaxMembersPerMultiplier = number("generation.special-max-members-per-multiplier", 1, 100000);
        maxConsecutiveWins = number("generation.max-consecutive-wins", 1, 1000);
        // 普通与特殊奖池分别限制完整Round累计中奖倍率；两项均由正式配置显式读取。
        normalMinMultiplier = number("generation.normal-min-win-multiplier", 1, Integer.MAX_VALUE);
        specialMinMultiplier = number("generation.special-min-win-multiplier", 1, Integer.MAX_VALUE);
        normalMaxMultiplier = number("generation.normal-max-win-multiplier", 1, Integer.MAX_VALUE);
        specialMaxMultiplier = number("generation.special-max-win-multiplier", 1, Integer.MAX_VALUE);
        maxFreeSpins = number("generation.mode.free-spins.max-spins", 1, 1000);
        if (maxFreeSpins < 5) throw new IllegalArgumentException("free-spins cap cannot be below captured fixed count 5");

        for (int betType = 1; betType <= 3; betType++) {
            for (int axis = 1; axis <= betType; axis++) {
                for (int symbol = 0; symbol <= 10; symbol++) {
                    paidWeights[betType - 1][axis - 1][symbol] = positive("generation.symbol.paid.bet-type-" + betType + ".axis-" + axis + ".symbol-" + symbol + "-weight");
                }
            }
        }
        for (int axis = 1; axis <= 3; axis++) for (int symbol = 0; symbol <= 9; symbol++) {
            freeOuterWeights[axis - 1][symbol] = positive("generation.symbol.free.axis-" + axis + ".outer.symbol-" + symbol + "-weight");
            freeBigWeights[axis - 1][symbol] = positive("generation.symbol.free.axis-" + axis + ".big.symbol-" + symbol + "-weight");
        }
        String[] branches = {"unchanged", "increment"};
        for (int i = 0; i < branches.length; i++) coinTransitionWeights[i] = positive("generation.state.coin-" + branches[i] + "-weight");
        Set<String> unread = new LinkedHashSet<>(values.stringPropertyNames());
        unread.removeAll(readKeys);
        if (!unread.isEmpty()) throw new IllegalArgumentException("generator.properties contains unread keys: " + unread);
    }

    public static GeneratorConfig load(Path file) throws IOException {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); LoaderLimits.checkKeys(values); }
        return new GeneratorConfig(values);
    }

    private String text(String key) {
        String value = textAllowBlank(key);
        if (value.isBlank()) throw new IllegalArgumentException(key + " cannot be blank");
        return value;
    }
    private String textAllowBlank(String key) {
        readKeys.add(key);
        String value = values.getProperty(key);
        if (value == null) throw new IllegalArgumentException("missing property " + key);
        return value.trim();
    }
    private int positive(String key) { return number(key, 1, Integer.MAX_VALUE); }
    private int number(String key, int min, int max) {
        int value = Math.toIntExact(longNumber(key, min, max));
        return value;
    }
    private long longNumber(String key, long min, long max) {
        String raw = text(key);
        long value = Long.parseLong(raw);
        if (value < min || value > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return value;
    }
    private boolean bool(String key) {
        String value = text(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
