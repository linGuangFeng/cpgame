package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundCodec;
import com.cpgame.replica.luckypanda.CompleteRoundFactory;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

final class TestMembers {
    static final BigDecimal BS = new BigDecimal("0.02");
    static final int BL = 1;

    private TestMembers() { }

    static Map<WeightScene, int[]> weights() {
        EnumMap<WeightScene, int[]> map = new EnumMap<>(WeightScene.class);
        map.put(WeightScene.PAID_START, new int[]{652, 4254, 4182, 3987, 3995, 3897, 3962, 4003, 4075, 3966, 3882, 182, 783});
        map.put(WeightScene.CASCADE_REFILL, new int[]{287, 1181, 1125, 1045, 1001, 1034, 965, 972, 1001, 942, 899, 337, 237});
        map.put(WeightScene.FREE_START, new int[]{212, 1133, 1244, 1126, 1067, 1102, 1017, 1065, 1083, 1140, 1092, 70, 209});
        map.put(WeightScene.FREE_CASCADE_REFILL, new int[]{85, 297, 291, 283, 312, 285, 289, 274, 266, 272, 288, 46, 59});
        return map;
    }

    static Map<WeightScene, int[]> boostedScatter() {
        Map<WeightScene, int[]> copy = new EnumMap<>(WeightScene.class);
        for (var entry : weights().entrySet()) {
            int[] table = entry.getValue().clone();
            if (entry.getKey() == WeightScene.PAID_START) {
                table[table.length - 1] = table[table.length - 1] * 10;
            }
            copy.put(entry.getKey(), table);
        }
        return copy;
    }

    static String lossMember() {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundFactory.GeneratedRound generated = factory.generate(new Random(7), 10, 10, weights(), true);
        return new CompleteRoundCodec().encode(generated.fact());
    }

    static CompleteRoundFactory.GeneratedRound generate(RoundClass wanted, Random random, int attempts) {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Map<WeightScene, int[]> special = boostedScatter();
        for (int i = 0; i < attempts; i++) {
            boolean forceLoss = wanted == RoundClass.ORDINARY_LOSS;
            boolean specialEntry = wanted == RoundClass.SCATTER_FREE;
            try {
                CompleteRoundFactory.GeneratedRound generated = factory.generate(
                        random, 10, 30, specialEntry ? special : weights(), forceLoss);
                RoundClass kind = codec.verify(codec.encode(generated.fact()), 10).roundClass();
                if (kind == wanted) return generated;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
            }
        }
        throw new AssertionError("unable to generate " + wanted);
    }
}
