package com.cpgame.saci.generator;

import java.nio.file.Path;

public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        Path configPath = findConfig(args).toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);
        RedisLoader.LoadSummary summary = new RedisLoader().load(config);
        System.out.printf("Saci Redis Loader 完成：未中奖=%d，普通中奖=%d，特殊=%d，事务批次=%d，候选=%d%n",
                summary.lossMembers(), summary.winMembers(), summary.specialMembers(), summary.batches(),
                summary.candidates());
        System.out.println("普通实际倍率(百分之一)分布=" + summary.normalDistribution());
        System.out.println("特殊实际倍率(百分之一)分布=" + summary.specialDistribution());
        System.out.println("rulesHash=" + GameRules.RULES_HASH);
    }

    private static Path findConfig(String[] args) {
        Path positional = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank() || "--no-pause".equalsIgnoreCase(arg)) continue;
            if (arg.startsWith("--config=")) return Path.of(arg.substring("--config=".length()));
            if ("--config".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("缺少 --config 路径");
                return Path.of(args[++i]);
            }
            if (arg.startsWith("--")) continue;
            if (positional == null) positional = Path.of(arg);
        }
        return positional != null ? positional : Path.of("generator.properties");
    }
}
