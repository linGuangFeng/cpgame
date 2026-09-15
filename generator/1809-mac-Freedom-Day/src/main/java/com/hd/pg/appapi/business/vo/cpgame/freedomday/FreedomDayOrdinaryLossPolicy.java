package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.util.Random;

/** 只作用于已经被 ResultUtil 复核为普通单步空奖的展示频率策略。 */
public final class FreedomDayOrdinaryLossPolicy {
    private FreedomDayOrdinaryLossPolicy() { }

    /**
     * 以 50/50 混合原基线空奖与无 Ball 空奖，因此“空奖含 x2”的条件概率恰好减半。
     * 中奖候选、玛丽/免费局及结算均不会进入本方法。
     */
    public static FreedomDayBoard halveBallOccurrence(FreedomDayBoard baselineLoss, Random random,
                                                       int[] normalWeights, int[] maryWeights) {
        if (!FreedomDayIndependentLossGenerator.isIndependentLoss(baselineLoss)) {
            throw new IllegalArgumentException("policy accepts only independently verified losses");
        }
        if (random.nextBoolean() || !containsBall(baselineLoss)) return baselineLoss;
        int[] noBallNormal = normalWeights.clone();
        noBallNormal[FreedomDayResultUtil.BALL - 1] = 0;
        FreedomDayBoardGenerator generator = new FreedomDayBoardGenerator(random, noBallNormal, maryWeights);
        for (int attempt = 0; attempt < FreedomDayIndependentLossGenerator.RANDOM_ATTEMPTS; attempt++) {
            FreedomDayBoard candidate = generator.generateIndependentLossCandidate(false);
            if (!containsBall(candidate) && FreedomDayIndependentLossGenerator.isIndependentLoss(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot construct verified no-Ball ordinary loss");
    }

    public static boolean containsBall(FreedomDayBoard board) {
        for (int symbol : board.getProp()) if (symbol == FreedomDayResultUtil.BALL) return true;
        for (int symbol : board.getTrl()) if (symbol == FreedomDayResultUtil.BALL) return true;
        return false;
    }
}
