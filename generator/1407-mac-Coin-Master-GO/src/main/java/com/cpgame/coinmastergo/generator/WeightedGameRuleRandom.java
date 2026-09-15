package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.GameRules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校验可调采样配置，并通过 GameRuleCore 的显式入口安装。
 * WILD 不是可直接抽取的牌，只能由中奖金牌在下一次掉落时变出。
 */
final class WeightedGameRuleRandom {
    private final SecureRandom entropy;
    private final int total;
    private final List<String> symbols;
    private final int[] cumulative;

    WeightedGameRuleRandom(SecureRandom entropy, Map<String, Integer> weights) {
        this.entropy = java.util.Objects.requireNonNull(entropy, "entropy");
        if (!Integer.valueOf(0).equals(weights.get("WILD"))) {
            throw new IllegalArgumentException("WILD direct-draw weight must be zero");
        }
        this.symbols = List.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "SC");
        this.cumulative = new int[symbols.size()];
        long running = 0;
        for (int index = 0; index < cumulative.length; index++) {
            running += positive(weights, symbols.get(index));
            if (running > Integer.MAX_VALUE) throw new IllegalArgumentException("符号总权重过大");
            cumulative[index] = (int) running;
        }
        total = (int) running;
    }

    static WeightedGameRuleRandom install(GameRuleCore core, Map<String, Integer> weights,
                                          int silverCardWeight, int goldCardWeight) {
        WeightedGameRuleRandom random = new WeightedGameRuleRandom(new SecureRandom(), weights);
        core.installRuntimeSymbolWeights(weights);
        core.configureCardMaterialWeights(silverCardWeight, goldCardWeight);
        return random;
    }

    String drawSymbolForDistributionCheck() {
        int draw = entropy.nextInt(total);
        for (int index = 0; index < cumulative.length; index++) {
            if (draw < cumulative[index]) return symbols.get(index);
        }
        throw new IllegalStateException("带权符号抽样越界");
    }

    List<Double> expectedProbabilities(Map<String, Integer> weights) {
        List<Double> values = new ArrayList<>();
        for (String symbol : GeneratorConfiguration.symbolOrder()) values.add(weights.get(symbol) / (double) total);
        return List.copyOf(values);
    }

    private static int positive(Map<String, Integer> weights, String symbol) {
        Integer value = weights.get(symbol);
        if (value == null || value <= 0) throw new IllegalArgumentException(symbol + " 权重必须是正数");
        return value;
    }
}
