package com.hd.cpgame.riocarnival.loader;

import com.hd.cpgame.riocarnival.core.GameRules;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 双击 CMD 或 java -jar JAR generator.properties 的正式入口。 */
public final class RoundLoaderApplication {
    private RoundLoaderApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("用法: java -jar rio-carnival-loader.jar [generator.properties]");
        for (String arg : args) {
            if (arg.toLowerCase(java.util.Locale.ROOT).contains("seed"))
                throw new IllegalArgumentException("正式 Loader 禁止 seed");
        }
        Path configPath = (args.length == 0 ? Paths.get(RoundLoaderApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("generator.properties") : Paths.get(args[0]))
            .toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);
        RedisLoader.LoadSummary summary = new RedisLoader().load(config);
        System.out.println("LOAD_COMPLETE gameId=" + summary.redisGameId
            + " loss=" + summary.lossMembers + " normalWin=" + summary.normalMembers + " special=" + summary.specialMembers
            + " batches=" + summary.batches + " candidates=" + summary.candidates
            + " zeroSkipped=" + summary.zeroSkipped + " limitSkipped=" + summary.limitSkipped
            + " maxFreeSteps=" + summary.maxFreeSteps + " rulesHash=" + GameRules.RULES_HASH);
        System.out.println("普通倍率分布=" + summary.normalDistribution);
        System.out.println("特殊倍率分布=" + summary.specialDistribution);
    }
}
