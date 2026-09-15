package com.cpgame.luckycatii;

import java.nio.file.Path;

public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        Path configPath = findConfig(args).toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);
        RedisLoader.LoadSummary summary = new RedisLoader().load(config);
        System.out.printf("Lucky Cat II Redis Loader 完成：未中奖=%d，普通中奖=%d，特殊=%d（Lucky=%d Wheel=%d），事务批次=%d，候选=%d%n",
                summary.lossMembers(), summary.winMembers(), summary.specialMembers(),
                summary.luckyMembers(), summary.wheelMembers(), summary.batches(), summary.candidates());
        System.out.println("普通实际倍率分布=" + summary.normalDistribution());
        System.out.println("特殊实际倍率分布=" + summary.specialDistribution());
        System.out.println("rulesHash=" + GameRules.RULES_HASH);
    }

    private static Path findConfig(String[] args) {
        if (args.length == 0) return Path.of("generator.properties");
        if (args.length == 1 && !args[0].startsWith("--")) return Path.of(args[0]);
        throw new IllegalArgumentException("用法：java -jar lucky-cat-ii-loader.jar generator.properties");
    }
}
