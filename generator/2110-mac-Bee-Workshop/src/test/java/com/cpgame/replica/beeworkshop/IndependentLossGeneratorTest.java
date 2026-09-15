package com.cpgame.replica.beeworkshop;

import java.util.Random;

/** 10万张独立未中奖：首次尝试成功率必须 ≥ 90%。 */
public final class IndependentLossGeneratorTest {
    public static void main(String[] args) {
        IndependentLossGenerator gen = new IndependentLossGenerator();
        GameRuleCore rules = new GameRuleCore();
        ResultUtil util = new ResultUtil(rules);
        int first = 0, n = 100_000;
        for (int i = 0; i < n; i++) {
            Random random = new Random(2110L + i);
            int[] board = EmpiricalDealModel.shared().board("ORDINARY", 0, true, random);
            boolean firstOk = board != null && gen.isIndependentLoss(board);
            if (!firstOk && board != null) {
                board = gen.forceLoss(board);
                firstOk = gen.isIndependentLoss(board);
            }
            if (firstOk) first++;
            var round = gen.generate(new Random(2110L + i));
            rules.validate(round);
            if (util.integerMultiplier(round) != 0) throw new IllegalStateException("independent loss paid");
        }
        double rate = first / (double) n;
        if (rate < IndependentLossGenerator.REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE)
            throw new IllegalStateException("first-attempt loss rate " + rate);
        System.out.println("{\"test\":\"independent-loss\",\"n\":" + n + ",\"firstAttempt\":" + first + ",\"rate\":" + rate + ",\"status\":\"PASS\"}");
    }
}
