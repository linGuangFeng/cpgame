package com.cpgame.sambasensation.generator;

import com.cpgame.sambasensation.core.BoardCaps;
import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.ResultUtil;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 运行时只生成会受玩家跨局状态影响的付费页、Scatter和金币增量。
 * 所有概率与重试上限都由调用方传入；本类不携带Demo或正式环境概率。
 */
public final class RuntimeSpinGenerator {
    public record Parameters(int[][][] paidSymbolWeights,
                             int coinUnchangedWeight,
                             int coinIncrementWeight,
                             int coinSingleWeight,
                             int coinDoubleWeight,
                             int[] coinPositionWeights,
                             int[] coinIncrementWeights,
                             int maxAttempts) {
        public Parameters {
            paidSymbolWeights = copy(paidSymbolWeights);
            coinPositionWeights = coinPositionWeights.clone();
            coinIncrementWeights = coinIncrementWeights.clone();
            if (paidSymbolWeights.length != 3 || coinPositionWeights.length != GameRuleCore.COIN_SLOTS
                    || coinIncrementWeights.length != 4 || maxAttempts <= 0) {
                throw new IllegalArgumentException("invalid runtime generation dimensions");
            }
            positiveTotal(coinUnchangedWeight, coinIncrementWeight);
            positiveTotal(coinSingleWeight, coinDoubleWeight);
            positiveTotal(coinPositionWeights);
            positiveTotal(coinIncrementWeights);
            for (int bet = 0; bet < 3; bet++) {
                if (paidSymbolWeights[bet].length != bet + 1) throw new IllegalArgumentException("paid axis count mismatch");
                for (int[] weights : paidSymbolWeights[bet]) {
                    if (weights.length != 11) throw new IllegalArgumentException("paid symbol weights must contain symbols 0..10");
                    positiveTotal(weights);
                }
            }
        }
        @Override public int[][][] paidSymbolWeights() { return copy(paidSymbolWeights); }
        @Override public int[] coinPositionWeights() { return coinPositionWeights.clone(); }
        @Override public int[] coinIncrementWeights() { return coinIncrementWeights.clone(); }
        private static int[][][] copy(int[][][] source) {
            int[][][] result = new int[source.length][][];
            for (int i = 0; i < source.length; i++) {
                result[i] = new int[source[i].length][];
                for (int j = 0; j < source[i].length; j++) result[i][j] = source[i][j].clone();
            }
            return result;
        }
        private static void positiveTotal(int... weights) {
            long total = 0;
            for (int weight : weights) { if (weight < 0) throw new IllegalArgumentException("negative weight"); total += weight; }
            if (total <= 0) throw new IllegalArgumentException("weight total must be positive");
        }
    }

    public record PaidPage(List<int[]> boards, int scatterDelta, int multiplier) {
        public PaidPage {
            List<int[]> copy = new ArrayList<>(boards.size());
            for (int[] board : boards) copy.add(board.clone());
            boards = List.copyOf(copy);
        }
        @Override public List<int[]> boards() {
            List<int[]> copy = new ArrayList<>(boards.size());
            for (int[] board : boards) copy.add(board.clone());
            return List.copyOf(copy);
        }
    }

    public record CoinDecision(GameRuleCore.CoinTransition transition, int[] delta, int rewardCount) {
        public CoinDecision { delta = delta.clone(); }
        @Override public int[] delta() { return delta.clone(); }
        public static CoinDecision unchanged() {
            return new CoinDecision(GameRuleCore.CoinTransition.UNCHANGED, new int[GameRuleCore.COIN_SLOTS], 0);
        }
    }

    private final Parameters parameters;

    public RuntimeSpinGenerator(Parameters parameters) { this.parameters = parameters; }

    /** 生成满足当前赔率预算和Scatter区间的真实付费页；0倍也由同一概率模型自然生成。 */
    public PaidPage paidPage(SecureRandom random, int betType, int minScatter, int maxScatter,
                             int maxMultiplier, boolean requireZero) {
        if (random == null) throw new IllegalArgumentException("SecureRandom required");
        if (betType < 1 || betType > 3 || minScatter < 0 || maxScatter < minScatter || maxMultiplier < 0) {
            throw new IllegalArgumentException("invalid paid page constraint");
        }
        int[][][] allWeights = parameters.paidSymbolWeights();
        for (int attempt = 0; attempt < parameters.maxAttempts(); attempt++) {
            List<int[]> boards = new ArrayList<>(betType);
            for (int axis = 0; axis < betType; axis++) {
                int[] board = new int[GameRuleCore.CELLS];
                for (int position = 0; position < board.length; position++) board[position] = weighted(random, allWeights[betType - 1][axis]);
                boards.add(board);
            }
            try { BoardCaps.validatePaid(betType, boards); }
            catch (IllegalArgumentException invalid) { continue; }
            int scatter = GameRuleCore.countSymbol(boards, GameRuleCore.SCATTER);
            if (scatter < minScatter || scatter > maxScatter) continue;
            GameRuleCore.CompleteRoundFact probe = new GameRuleCore.CompleteRoundFact(
                    GameRuleCore.EntryKind.PAID_INITIAL, betType, scatter, new int[GameRuleCore.COIN_SLOTS],
                    GameRuleCore.CoinTransition.UNCHANGED, 0, List.of(new GameRuleCore.Step(boards)));
            int multiplier = ResultUtil.evaluate(probe).multiplier();
            if (multiplier > maxMultiplier || requireZero && multiplier != 0) continue;
            return new PaidPage(boards, scatter, multiplier);
        }
        throw new IllegalStateException("runtime paid page constraints exhausted");
    }

    /** 金币增量在线抽取；只有本次真实增量填满全部槽位时才标记FULL_TRIGGER。 */
    public PaidPage addScatter(SecureRandom random, PaidPage cached, int betType, int maxScatter) {
        List<int[]> boards = cached.boards();
        int scatter = cached.scatterDelta();
        int[][] weights = parameters.paidSymbolWeights()[betType - 1];
        // 每格至多检查一次，绝不为命中目标奖励反复生成整个牌面。
        for (int axis = 0; axis < boards.size(); axis++) {
            for (int position = 0; position < GameRuleCore.CELLS && scatter < maxScatter; position++) {
                if (weighted(random, weights[axis]) != GameRuleCore.SCATTER) continue;
                int[] board = boards.get(axis);
                int previous = board[position];
                if (previous == GameRuleCore.SCATTER) continue;
                board[position] = GameRuleCore.SCATTER;
                try {
                    GameRuleCore.CompleteRoundFact probe = new GameRuleCore.CompleteRoundFact(
                            GameRuleCore.EntryKind.PAID_INITIAL, betType, scatter + 1,
                            new int[GameRuleCore.COIN_SLOTS], GameRuleCore.CoinTransition.UNCHANGED,
                            0, List.of(new GameRuleCore.Step(boards)));
                    if (ResultUtil.evaluate(probe).multiplier() == cached.multiplier()) { scatter++; continue; }
                } catch (IllegalArgumentException invalid) { /* 超出牌面符号上限则保留原符号。 */ }
                board[position] = previous;
            }
        }
        return new PaidPage(boards, scatter, cached.multiplier());
    }

    /** 金币增量在线抽取；只有本次真实增量填满全部槽位时才标记FULL_TRIGGER。 */
    public CoinDecision coinDecision(SecureRandom random, GameRuleCore.CollectionState state,
                                     boolean allowFull, int maxRewardOdds) {
        if (state.resetPending()) return CoinDecision.unchanged();
        if (weighted(random, new int[]{parameters.coinUnchangedWeight(), parameters.coinIncrementWeight()}) == 0) {
            return CoinDecision.unchanged();
        }
        for (int attempt = 0; attempt < parameters.maxAttempts(); attempt++) {
            int[] delta = new int[GameRuleCore.COIN_SLOTS];
            int changed = weighted(random, new int[]{parameters.coinSingleWeight(), parameters.coinDoubleWeight()}) + 1;
            boolean[] selected = new boolean[delta.length];
            boolean validSelection = true;
            for (int i = 0; i < changed; i++) {
                int[] available = parameters.coinPositionWeights();
                for (int slot = 0; slot < selected.length; slot++) if (selected[slot]) available[slot] = 0;
                if (Arrays.stream(available).sum() == 0) { validSelection = false; break; }
                int slot = weighted(random, available);
                selected[slot] = true;
                delta[slot] = weighted(random, parameters.coinIncrementWeights()) + 1;
            }
            if (!validSelection) continue;
            int[] next = state.coins();
            boolean valid = true;
            for (int i = 0; i < next.length; i++) {
                next[i] += delta[i];
                if (next[i] > GameRuleCore.COIN_PLATE_CAP) valid = false;
            }
            if (!valid) continue;
            boolean full = Arrays.stream(next).allMatch(value -> value > 0);
            int count = Arrays.stream(next).sum();
            if (full && (!allowFull || count > maxRewardOdds)) continue;
            return new CoinDecision(full ? GameRuleCore.CoinTransition.FULL_TRIGGER
                    : GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, delta, full ? count : 0);
        }
        return CoinDecision.unchanged();
    }

    private static int weighted(SecureRandom random, int[] weights) {
        long total = 0;
        for (int weight : weights) total += weight;
        long draw = random.nextLong(total), cursor = 0;
        for (int i = 0; i < weights.length; i++) { cursor += weights[i]; if (draw < cursor) return i; }
        throw new IllegalStateException("weighted selection failed");
    }
}
