package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.service.GameProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 离线随机验收入口。它不连接 Redis、不读取 fixture/capture/history，逐局调用唯一
 * GameRuleCore，再由独立 ResultUtil、结构校验器和最小事实 Codec 做双重重算。
 */
public final class RandomRoundVerifierMain {
    private RandomRoundVerifierMain() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：RandomRoundVerifierMain <generator.properties> <round-count>");
        }
        Path configurationPath = Path.of(args[0]).toAbsolutePath().normalize();
        int count = Integer.parseInt(args[1]);
        if (count < 1 || count > 10_000_000) throw new IllegalArgumentException("round-count 超出范围");

        GeneratorConfiguration configuration = GeneratorConfiguration.load(configurationPath);
        GameRuleCore core = new GameRuleCore(new GameProperties());
        WeightedGameRuleRandom.install(core, configuration.symbolWeights,
                configuration.silverCardWeight, configuration.goldCardWeight);
        RoundResultUtil resultUtil = new RoundResultUtil();
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(resultUtil, configuration);
        MinimalFactCodec codec = new MinimalFactCodec();
        Map<String, Long> scenarios = new LinkedHashMap<>();
        long positive = 0;
        long zero = 0;
        int maximumSteps = 0;
        long started = System.nanoTime();

        for (int index = 0; index < count; index++) {
            RoundPlan round = core.generateRuntimeRound(UUID.randomUUID().toString(),
                    Long.toUnsignedString(System.nanoTime()) + "-" + index, 1,
                    new BigDecimal("0.02"), BigDecimal.ZERO, System.currentTimeMillis());
            RoundResultUtil.RoundAnalysis first = verifier.verify(round);
            RoundResultUtil.RoundAnalysis rebuilt = verifier.verify(codec.rebuild(codec.encode(round)));
            if (!first.pool().equals(rebuilt.pool())
                    || first.multiplier().compareTo(rebuilt.multiplier()) != 0
                    || first.stepCount() != rebuilt.stepCount()
                    || !first.rulesHash().equals(rebuilt.rulesHash())) {
                throw new IllegalStateException("第 " + index + " 局 Codec/Verifier 重算不一致");
            }
            scenarios.merge(round.scenario, 1L, Long::sum);
            if (first.multiplier().signum() == 0) zero++; else positive++;
            maximumSteps = Math.max(maximumSteps, first.stepCount());
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        System.out.printf(Locale.ROOT,
                "VERIFIED rounds=%d positive=%d zero=%d maxSteps=%d scenarios=%s elapsedMs=%d rulesVersion=%s rulesHash=%s%n",
                count, positive, zero, maximumSteps, scenarios, elapsedMillis,
                core.rulesVersion(), core.rulesHash());
    }
}
