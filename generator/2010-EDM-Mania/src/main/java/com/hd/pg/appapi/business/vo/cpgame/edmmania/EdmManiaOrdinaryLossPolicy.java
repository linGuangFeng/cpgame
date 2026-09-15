package com.hd.pg.appapi.business.vo.cpgame.edmmania;

import java.util.Random;

/** 只作用于已经被 ResultUtil 复核为普通单步空奖的展示频率策略。 */
public final class EdmManiaOrdinaryLossPolicy {
    private EdmManiaOrdinaryLossPolicy() { }

    /**
     * 以 50/50 混合原基线空奖与无 Ball 空奖，因此“空奖含 x2”的条件概率恰好减半。
     * 中奖候选、玛丽/免费局及结算均不会进入本方法。
     */
    public static EdmManiaBoard halveBallOccurrence(EdmManiaBoard baselineLoss, Random random,
                                                       int[] normalWeights, int[] maryWeights) {
        if (!EdmManiaIndependentLossGenerator.isIndependentLoss(baselineLoss)) {
            throw new IllegalArgumentException("policy accepts only independently verified losses");
        }
        if (random.nextBoolean() || !containsBall(baselineLoss)) return baselineLoss;
        int[] noBallNormal = normalWeights.clone();
        noBallNormal[EdmManiaResultUtil.BALL - 1] = 0;
        EdmManiaBoardGenerator generator = new EdmManiaBoardGenerator(random, noBallNormal, maryWeights);
        for (int attempt = 0; attempt < EdmManiaIndependentLossGenerator.RANDOM_ATTEMPTS; attempt++) {
            EdmManiaBoard candidate = generator.generateIndependentLossCandidate(false);
            if (!containsBall(candidate) && EdmManiaIndependentLossGenerator.isIndependentLoss(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot construct verified no-Ball ordinary loss");
    }

    public static boolean containsBall(EdmManiaBoard board) {
        for (int symbol : board.getProp()) if (symbol == EdmManiaResultUtil.BALL) return true;
        for (int symbol : board.getTrl()) if (symbol == EdmManiaResultUtil.BALL) return true;
        return false;
    }
}
