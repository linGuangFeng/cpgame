package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 双击CMD或java -jar JAR generator.properties的正式Java Redis Loader入口。 */
public final class LoaderMain {
    private static final int VERIFICATION_ROUND_COUNT = 10_000;
    private static final int VERIFICATION_LOSS_SAMPLES = 10_000;

    private LoaderMain() { }

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        boolean verifyOnly = false;
        Path optionConfig = null;
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--verify".equals(arg)) verifyOnly = true;
            else if ("--no-pause".equals(arg)) { /* CMD参数，不影响Java运行。 */ }
            else if ("--config".equals(arg) || (arg != null && arg.startsWith("--config="))) {
                if ("--config".equals(arg)) {
                    if(index+1>=args.length)throw new IllegalArgumentException("--config requires a path");
                    optionConfig=Path.of(args[++index]);
                } else optionConfig=Path.of(arg.substring("--config=".length()));
            } else {
                if (arg.toLowerCase(Locale.ROOT).contains("seed")) throw new IllegalArgumentException("正式Loader禁止seed");
                positional.add(arg);
            }
        }
        if (optionConfig != null) {
            if(!positional.isEmpty())throw new IllegalArgumentException("Conflicting configuration paths");
            java.util.Properties supplied=new java.util.Properties();
            try(var reader=java.nio.file.Files.newBufferedReader(optionConfig)){supplied.load(reader);}
            // Only a runtime config is a platform preload. Formal generator config must be loaded.
            boolean hasGeneration=supplied.stringPropertyNames().stream().anyMatch(k->k.startsWith("generation."));
            if(!hasGeneration && optionConfig.getFileName().toString().equals("runtime.properties")) {
                System.out.println("PRELOAD_SKIP runtime.properties: Redis rounds are loaded separately");
                return;
            }
            positional.add(optionConfig.toString());
        }
        if (positional.size() != 1)
            throw new IllegalArgumentException("用法: java -jar cyber-go-loader.jar generator.properties [--verify]");
        Path configPath = Path.of(positional.getFirst()).toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);

        if (verifyOnly) {
            RoundEngineVerifier.Summary summary = RoundEngineVerifier.verify(config.limits(),
                    VERIFICATION_ROUND_COUNT, VERIFICATION_LOSS_SAMPLES, BigDecimal.ZERO);
            if (!summary.redisEncodingEnabled()) throw new IllegalStateException("Redis member编码未启用");
            System.out.printf("VERIFY_PASS rounds=%d naturalCandidateLossRate=%s rulesHash=%s factsSha256=%s kinds=%s%n",
                    summary.verifiedRounds(), summary.lossFirstSuccessRate(), summary.rulesHash(),
                    summary.generatedFactsSha256(), summary.kinds());
            return;
        }

        RedisLoader.LoadSummary summary = new RedisLoader().load(config);
        System.out.printf("LOAD_COMPLETE redisGameId=%d normal=%d special=%d batches=%d candidates=%d fractionalMultiplierSkipped=%d limitSkipped=%d maxDeliveries=%d rulesHash=%s%n",
                summary.redisGameId(), summary.normalMembers(), summary.specialMembers(), summary.batches(),
                summary.candidates(), summary.zeroSkipped(), summary.limitSkipped(), summary.maxDeliveries(),
                CyberGoRules.RULES_HASH);
        System.out.println("普通实际倍率分布=" + summary.normalDistribution());
        System.out.println("特殊实际倍率分布=" + summary.specialDistribution());
    }
}
