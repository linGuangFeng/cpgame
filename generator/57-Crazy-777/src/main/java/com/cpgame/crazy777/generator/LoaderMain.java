package com.cpgame.crazy777.generator;

import java.nio.file.Path;
import java.util.Locale;

/** 正式 Redis Loader 入口。runRedis 只传配置文件时先普通奖再玛丽；指定路口时只跑一池。 */
public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        Parsed parsed = parse(args);
        GeneratorConfig config = GeneratorConfig.load(parsed.config.toAbsolutePath().normalize());
        RedisLoader loader = new RedisLoader();
        if (parsed.both()) {
            System.out.println("Crazy 777 runRedis：先普通奖，再玛丽");
            RedisLoader.LoadSummary ordinary = loader.load(config, RedisLoader.Pool.ORDINARY);
            reportOrdinary(ordinary);
            RedisLoader.LoadSummary mary = loader.load(config, RedisLoader.Pool.MARY);
            reportMary(mary);
            reportComplete(ordinary.lossMembers() + ordinary.winMembers(), mary.specialMembers(),
                    ordinary.batches() + mary.batches());
        } else if (parsed.pool() == RedisLoader.Pool.ORDINARY) {
            RedisLoader.LoadSummary ordinary = loader.load(config, RedisLoader.Pool.ORDINARY);
            reportOrdinary(ordinary);
            reportComplete(ordinary.lossMembers() + ordinary.winMembers(), 0, ordinary.batches());
        } else {
            RedisLoader.LoadSummary mary = loader.load(config, RedisLoader.Pool.MARY);
            reportMary(mary);
            reportComplete(0, mary.specialMembers(), mary.batches());
        }
        System.out.println("rulesHash=" + GameRules.RULES_HASH);
    }

    static Parsed parse(String[] args) {
        if (args == null || args.length == 0 || args.length > 2) throw usage();
        String first = args[0] == null ? "" : args[0].trim();
        if (first.isEmpty() || first.startsWith("--")) throw usage();
        Kind kind = kind(first);
        if (args.length == 1) {
            if (kind == Kind.ORDINARY) return new Parsed(RedisLoader.Pool.ORDINARY, Path.of("generator.properties"), false);
            if (kind == Kind.MARY) return new Parsed(RedisLoader.Pool.MARY, Path.of("generator.properties"), false);
            if (kind == Kind.BOTH) return new Parsed(null, Path.of("generator.properties"), true);
            return new Parsed(null, Path.of(first), true);
        }
        String second = args[1] == null ? "" : args[1].trim();
        if (second.isEmpty() || second.startsWith("--") || kind == Kind.CONFIG) throw usage();
        if (kind == Kind.ORDINARY) return new Parsed(RedisLoader.Pool.ORDINARY, Path.of(second), false);
        if (kind == Kind.MARY) return new Parsed(RedisLoader.Pool.MARY, Path.of(second), false);
        return new Parsed(null, Path.of(second), true);
    }

    private static void reportOrdinary(RedisLoader.LoadSummary summary) {
        System.out.printf("Crazy 777 普通奖完成：未中奖=%d，普通中奖=%d，事务批次=%d，候选=%d%n",
                summary.lossMembers(), summary.winMembers(), summary.batches(), summary.candidates());
        System.out.println("普通实际倍率分布=" + summary.normalDistribution());
    }

    private static void reportMary(RedisLoader.LoadSummary summary) {
        System.out.printf("Crazy 777 玛丽完成：免费=%d，事务批次=%d，候选=%d%n",
                summary.specialMembers(), summary.batches(), summary.candidates());
        System.out.println("特殊实际倍率分布=" + summary.specialDistribution());
    }

    private static void reportComplete(int normal, int special, int batches) {
        System.out.printf("LOAD_COMPLETE normal=%d special=%d batches=%d loaded=%d%n",
                normal, special, batches, normal + special);
    }

    private static Kind kind(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        String trimmed = raw.trim();
        if ("ordinary".equals(value) || "normal".equals(value) || "普通".equals(trimmed) || "普通奖".equals(trimmed)) {
            return Kind.ORDINARY;
        }
        if ("mary".equals(value) || "special".equals(value) || "玛丽".equals(trimmed) || "玛丽奖".equals(trimmed)) {
            return Kind.MARY;
        }
        if ("both".equals(value) || "all".equals(value) || "全部".equals(trimmed)) {
            return Kind.BOTH;
        }
        return Kind.CONFIG;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "用法：java -jar crazy777-loader.jar <generator.properties>，或 ordinary|mary|both [generator.properties]");
    }

    private enum Kind { ORDINARY, MARY, BOTH, CONFIG }

    record Parsed(RedisLoader.Pool pool, Path config, boolean both) {}
}
