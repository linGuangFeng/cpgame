package com.hd.cpgame.magicscroll2.loader;

import java.io.File;

/** Formal Java Redis Loader entry. */
public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException(
                "用法: java -jar magic-scroll2-loader.jar [generator.properties]");
        File configFile = new File(args.length == 0 ? "generator.properties" : args[0]).getCanonicalFile();
        RedisLoader.LoadSummary summary = new RedisLoader().run(configFile);
        System.out.println("LOAD_COMPLETE redisGameId=" + summary.redisGameId
                + " loss=" + summary.lossMembers + " win=" + summary.winMembers
                + " special=" + summary.specialMembers
                + " batches=" + summary.batches + " candidates=" + summary.candidates
                + " maxDeliveries=" + summary.maxDeliveries);
        System.out.println("普通实际倍率分布=" + summary.normalDistribution);
        System.out.println("特殊实际倍率分布=" + summary.specialDistribution);
        System.out.println("rulesVersion=" + com.hd.cpgame.magicscroll2.core.GameConstants.RULES_VERSION
                + " rulesHash=" + com.hd.cpgame.magicscroll2.core.GameConstants.RULES_HASH);
    }
}
