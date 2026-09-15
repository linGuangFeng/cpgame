package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.generator.RuntimeSpinGenerator;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** 将运行时收集状态与无状态Redis牌面/Free尾部组合成可由共享规则重新判定的完整局。 */
final class RuntimeRoundComposer {
    private static final int SCALE = 25;
    private final RedisRoundStore store;
    private final RuntimeSpinGenerator generator;
    private final int[] payoutCapOdds;
    private final int[] payoutCapWeights;
    private final int naturalMaryWeight;
    private final int ordinaryWeight;
    private final int winWeight;
    private final int lossWeight;

    RuntimeRoundComposer(RedisRoundStore store, RuntimeSpinGenerator generator,
                         int[] payoutCapOdds, int[] payoutCapWeights,
                         int naturalMaryWeight, int ordinaryWeight) {
        this(store, generator, payoutCapOdds, payoutCapWeights, naturalMaryWeight, ordinaryWeight,
                DemoRuntimePolicy.WIN_WEIGHT, DemoRuntimePolicy.LOSS_WEIGHT);
    }

    RuntimeRoundComposer(RedisRoundStore store, RuntimeSpinGenerator generator,
                         int[] payoutCapOdds, int[] payoutCapWeights,
                         int naturalMaryWeight, int ordinaryWeight, int winWeight, int lossWeight) {
        this.store = store;
        this.generator = generator;
        this.payoutCapOdds = payoutCapOdds.clone();
        this.payoutCapWeights = payoutCapWeights.clone();
        this.naturalMaryWeight = naturalMaryWeight;
        this.ordinaryWeight = ordinaryWeight;
        this.winWeight = winWeight;
        this.lossWeight = lossWeight;
        if (this.payoutCapOdds.length == 0 || this.payoutCapOdds.length != this.payoutCapWeights.length) {
            throw new IllegalArgumentException("payout cap configuration mismatch");
        }
        if (winWeight < 0 || lossWeight < 0 || (long) winWeight + lossWeight <= 0
                || naturalMaryWeight < 0 || ordinaryWeight < 0 || (long) naturalMaryWeight + ordinaryWeight <= 0)
            throw new IllegalArgumentException("invalid outcome weights");
        long sum = 0;
        for (int i = 0; i < this.payoutCapOdds.length; i++) {
            if (this.payoutCapOdds[i] < 0 || this.payoutCapOdds[i] > Integer.MAX_VALUE / SCALE
                    || this.payoutCapWeights[i] < 0) throw new IllegalArgumentException("invalid payout cap");
            sum += this.payoutCapWeights[i];
        }
        if (sum == 0) throw new IllegalArgumentException("empty payout weights");
    }

    RedisRoundStore.ClaimedRound composePaid(SecureRandom random, int betType,
                                              GameRuleCore.CollectionState state) throws IOException {
        int capMultiplier = payoutCapOdds[weighted(random, payoutCapWeights)] * SCALE;
        boolean naturalMary = weighted(random, new int[]{ordinaryWeight, naturalMaryWeight}) == 1;

        RuntimeSpinGenerator.CoinDecision coin = generator.coinDecision(random, state,
                !naturalMary && betType == 3, capMultiplier / SCALE);
        if (coin.transition() == GameRuleCore.CoinTransition.FULL_TRIGGER) {
            int coinMultiplier = coin.rewardCount() * SCALE;
            int remaining = capMultiplier - coinMultiplier;
            RedisRoundStore.ClaimedRound template = store.claimPurePaidTemplateAtMost(random, betType, remaining);
            RuntimeSpinGenerator.PaidPage page = paidPage(random, betType, state, template);
            return checked(fact(betType, page, coin, List.of()), state, capMultiplier, true, "runtime-coin-full");
        }

        if (naturalMary) {
            RuntimeSpinGenerator.PaidPage page = generator.paidPage(random, betType, 0,
                    GameRuleCore.SCATTER_METER_SIZE, 0, true);
            boolean meterMary = state.scatterProgress() + page.scatterDelta() >= GameRuleCore.SCATTER_METER_SIZE;
            RedisRoundStore.TailTemplate tail = store.claimFreeTailAtMost(random, capMultiplier);
            if (tail == null) throw new IllegalStateException("Mary Free尾部缓存没有不超过当前中奖上限的兼容结果");
            List<GameRuleCore.Step> tailSteps = new ArrayList<>(tail.steps());
            return checked(fact(betType, page, coin, tailSteps), state, capMultiplier, true,
                    meterMary ? "runtime-scatter-full" : "runtime-natural-mary");
        }
        boolean wantWin = weighted(random, new int[]{lossWeight, winWeight}) == 1;
        RedisRoundStore.ClaimedRound template = wantWin
                ? store.claimPurePaidTemplateAtMost(random, betType, capMultiplier) : null;
        if (wantWin && template == null) throw new IllegalStateException("当前楼层/中奖上限没有普通正奖缓存，未进行实时中奖生成");
        RuntimeSpinGenerator.PaidPage page = paidPage(random, betType, state, template);
        return checked(fact(betType, page, coin, List.of()), state, capMultiplier, false,
                template == null ? "runtime-zero" : "cached-ordinary-win");
    }

    private RuntimeSpinGenerator.PaidPage paidPage(SecureRandom random, int betType,
                                                   GameRuleCore.CollectionState state,
                                                   RedisRoundStore.ClaimedRound template) {
        int maxScatter = GameRuleCore.SCATTER_METER_SIZE - 1 - state.scatterProgress();
        if (template == null) return generator.paidPage(random, betType, 0, maxScatter, 0, true);
        return generator.addScatter(random, new RuntimeSpinGenerator.PaidPage(
                template.fact().steps().get(0).boards(), 0, template.multiplier()), betType, maxScatter);
    }

    private static RedisRoundStore.ClaimedRound checked(GameRuleCore.CompleteRoundFact fact,
            GameRuleCore.CollectionState state, int cap, boolean special, String source) {
        GameRuleCore.applyCollectionTransition(state, fact);
        RedisRoundStore.ClaimedRound result = completed(fact, special, source);
        if (result.multiplier() > cap) throw new IllegalStateException("assembled payout exceeds cap");
        return result;
    }

    private static GameRuleCore.CompleteRoundFact fact(int betType, RuntimeSpinGenerator.PaidPage page,
                                                        RuntimeSpinGenerator.CoinDecision coin,
                                                        List<GameRuleCore.Step> tail) {
        List<GameRuleCore.Step> steps = new ArrayList<>();
        steps.add(new GameRuleCore.Step(page.boards()));
        steps.addAll(tail);
        return new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, betType,
                page.scatterDelta(), coin.delta(), coin.transition(), coin.rewardCount(), steps);
    }

    private static RedisRoundStore.ClaimedRound completed(GameRuleCore.CompleteRoundFact fact,
                                                           boolean special, String source) {
        ResultUtil.Evaluation evaluation = ResultUtil.evaluate(fact);
        return new RedisRoundStore.ClaimedRound(fact, evaluation, evaluation.roundClass(), special,
                evaluation.multiplier(), source);
    }

    private static int weighted(SecureRandom random, int[] weights) {
        long total = 0;
        for (int weight : weights) { if (weight < 0) throw new IllegalArgumentException("negative weight"); total += weight; }
        if (total <= 0) throw new IllegalArgumentException("weight total must be positive");
        long draw = random.nextLong(total), cursor = 0;
        for (int i = 0; i < weights.length; i++) { cursor += weights[i]; if (draw < cursor) return i; }
        throw new IllegalStateException("weighted selection failed");
    }
}
