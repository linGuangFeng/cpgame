package com.cpgame.crazybirds.generator;

import java.nio.file.Path;

public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        Path configPath = findConfig(args).toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);
        RedisLoader.LoadSummary summary = new RedisLoader().load(config);
        System.out.printf("Crazy Birds Redis Loader 完成：尝试=%d，有效=%d，丢弃=%d，批次=%d%n",
                summary.attempts(), summary.accepted(), summary.rejected(), summary.batches());
        System.out.println("玩法分布=" + summary.modes());
        System.out.println("普通倍率索引分布=" + summary.normalDistribution());
        System.out.println("免费完整局倍率索引分布=" + summary.freeDistribution());
        System.out.println("拒绝原因=" + summary.rejectionReasons());
        System.out.println("权重相位尝试=" + summary.phaseAttempts());
        System.out.println("权重相位有效=" + summary.phaseAccepted());
        System.out.println("rulesHash=" + GameRules.RULES_HASH);
    }

    private static Path findConfig(String[] args) {
        if (args.length == 0) return Path.of("generator.properties");
        if (args.length == 1 && !args[0].startsWith("--")) return Path.of(args[0]);
        throw new IllegalArgumentException("用法：java -jar crazybirds-loader.jar generator.properties");
    }
}
