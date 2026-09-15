package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertTrue;

/** 验证正式本地复刻权重被实际用于抽样，且不是隐式均匀随机。 */
public final class WeightDistributionTest {
    @Test
    public void explicitCurrentGameWeightsProduceExpectedDistribution() throws Exception {
        int[] weights = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
        SymbolWeightPolicy symbols = new SymbolWeightPolicy(weights);
        SecureRandom random = seeded(2001007L);
        int[] observed = new int[weights.length];
        int samples = 170000;
        for (int i = 0; i < samples; i++) observed[symbols.select(random) - 3]++;

        int totalWeight = 85;
        for (int i = 0; i < weights.length; i++) {
            double expected = samples * (double) weights[i] / totalWeight;
            double relativeError = Math.abs(observed[i] - expected) / expected;
            assertTrue("符号 " + (i + 3) + " 分布偏差过大: " + observed[i], relativeError < 0.06);
        }
        assertTrue("牌面权重疑似退化为均匀随机", observed[9] > observed[0] * 2.8);

        TrialProbabilityPolicy modes = new TrialProbabilityPolicy(80, 12, 4, 4);
        int loss = 0, base = 0, xSplit = 0, xBomb = 0;
        random = seeded(2001008L);
        for (int i = 0; i < 100000; i++) {
            RoundMode mode = modes.select(random);
            if (mode == RoundMode.LOSS) loss++;
            else if (mode == RoundMode.BASE_WIN) base++;
            else if (mode == RoundMode.XSPLIT) xSplit++;
            else if (mode == RoundMode.XBOMB_WILD) xBomb++;
        }
        assertTrue("LOSS 模式权重未生效: " + loss, loss > 78000 && loss < 82000);
        assertTrue("BASE_WIN 模式权重未生效: " + base, base > 11000 && base < 13000);
        assertTrue("XSPLIT 模式权重未生效: " + xSplit, xSplit > 3400 && xSplit < 4600);
        assertTrue("XBOMB_WILD 模式权重未生效: " + xBomb, xBomb > 3400 && xBomb < 4600);
    }

    private static SecureRandom seeded(long seed) throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        return random;
    }
}
