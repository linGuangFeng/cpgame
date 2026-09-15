package com.cpgame.sambasensation.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Samba Sensation 唯一规则核心。服务端必须依赖本 Maven artifact，不得复制第二份判定。
 * 规则来自 rulesHash 对应的 rules-core.json；本类不读取 fixtures/captures。
 */
public final class GameRuleCore {
    public static final int GAME_ID = 2290;
    public static final String RULES_VERSION = "2290-protocol-v3-template39-cross-round-state-v2";
    public static final String RULES_HASH = "46ef48cf0977a2785f257825d1e499b8049d293900e2a10d4a0dec7ad1ba2205";
    public static final int ROWS = 3;
    public static final int COLUMNS = 5;
    public static final int CELLS = 15;
    public static final int SCATTER = 10;
    public static final int WILD = 0;
    public static final int FREE_STEPS = 5;
    public static final int SCATTER_METER_SIZE = 30;
    public static final int COIN_SLOTS = 5;
    public static final int COIN_PLATE_CAP = 128;

    /** 每条线按列给出行号；数组顺序就是供应方 1..25 线。 */
    public static final int[][] PAYLINES = {
            {2,2,2,2,2},{1,1,1,1,1},{0,0,0,0,0},{2,2,1,0,0},{0,0,1,2,2},
            {2,2,0,2,2},{0,0,2,0,0},{2,1,2,1,2},{0,1,0,1,0},{2,1,1,1,2},
            {0,1,1,1,0},{2,1,0,1,2},{0,1,2,1,0},{2,0,2,0,2},{0,2,0,2,0},
            {2,0,1,0,2},{0,2,1,2,0},{2,0,0,0,2},{0,2,2,2,0},{1,2,2,2,1},
            {1,0,0,0,1},{1,2,1,2,1},{1,0,1,0,1},{1,2,0,2,1},{1,0,2,0,1}
    };

    /** pay_out × 25，因 bet_gold=bet×level×25，level=1 时直接得到整数倍率。 */
    private static final int[][] PAY_MULTIPLIER = {
            {0,0,0,0,0,0},
            {0,0,0,100,300,750}, {0,0,0,75,250,500}, {0,0,0,50,150,300},
            {0,0,0,25,50,200}, {0,0,0,10,25,100}, {0,0,0,10,25,100},
            {0,0,0,5,10,50}, {0,0,0,5,10,50}, {0,0,0,5,10,50}
    };

    private GameRuleCore() { }

    public enum EntryKind { PAID_INITIAL, FEATURE_BUY_INITIAL }
    /** 4874组相邻原厂状态只出现：不变、非递减增量、满槽；满槽后的复位由会话状态派生。 */
    public enum CoinTransition { UNCHANGED, INCREMENT_NONDECREASING, FULL_TRIGGER }
    public enum CollectionBranch { UNCHANGED, INCREMENT_NONDECREASING, FULL_TRIGGER, RESET_AFTER_FULL }
    public enum FreePhase { ABSENT, ACTIVE, TERMINAL }
    public enum RoundClass { ORDINARY_LOSS, ORDINARY_WIN, FREE_SPINS_SPECIAL, COIN_COLLECTION_REWARD }

    public record Step(List<int[]> boards) {
        public Step {
            if (boards == null || boards.isEmpty()) throw new IllegalArgumentException("step boards empty");
            List<int[]> copy = new ArrayList<>(boards.size());
            for (int[] board : boards) {
                if (board == null || board.length != CELLS) throw new IllegalArgumentException("board must contain 15 symbols");
                copy.add(board.clone());
            }
            boards = List.copyOf(copy);
        }
        @Override public List<int[]> boards() {
            List<int[]> copy = new ArrayList<>(boards.size());
            for (int[] board : boards) copy.add(board.clone());
            return List.copyOf(copy);
        }
    }

    /**
     * Redis member保存一局不可重算事实：牌面、Scatter增量、金币增量/满槽目标总数。
     * 它不保存会话的绝对进度；绝对状态只能由Controller按相邻付费Round连续投影。
     */
    public record CompleteRoundFact(EntryKind entryKind, int betType, int scatterDelta,
                                    int[] coinDelta, CoinTransition coinTransition,
                                    int coinRewardCount, List<Step> steps) {
        public CompleteRoundFact {
            if (entryKind == null || coinTransition == null || steps == null) throw new IllegalArgumentException("explicit state is required");
            if (betType < 1 || betType > 3) throw new IllegalArgumentException("betType must be 1..3");
            if (scatterDelta < 0 || scatterDelta > SCATTER_METER_SIZE) throw new IllegalArgumentException("scatter delta must be 0..30");
            if (coinDelta == null || coinDelta.length != COIN_SLOTS) throw new IllegalArgumentException("coin delta length must be 5");
            coinDelta = coinDelta.clone();
            for (int delta : coinDelta) if (delta < 0 || delta > 4) throw new IllegalArgumentException("coin delta exceeds captured adjacent cap 4");
            int deltaTotal = Arrays.stream(coinDelta).sum();
            long changed = Arrays.stream(coinDelta).filter(value -> value > 0).count();
            if (coinTransition == CoinTransition.UNCHANGED && deltaTotal != 0) throw new IllegalArgumentException("UNCHANGED requires zero coin delta");
            if (coinTransition == CoinTransition.INCREMENT_NONDECREASING
                    && !(deltaTotal > 0 && changed <= 2)) throw new IllegalArgumentException("increment must match captured positive one/two-slot delta");
            if (coinTransition == CoinTransition.FULL_TRIGGER && deltaTotal > 0 && changed > 2) {
                throw new IllegalArgumentException("full trigger increment may change at most two slots");
            }
            if (coinTransition == CoinTransition.FULL_TRIGGER && (coinRewardCount < 5 || coinRewardCount > 497)) throw new IllegalArgumentException("full reward count outside captured 5..497");
            if (coinTransition != CoinTransition.FULL_TRIGGER && coinRewardCount != 0) throw new IllegalArgumentException("non-full transition cannot carry reward count");
            steps = List.copyOf(steps);
        }
        @Override public int[] coinDelta() { return coinDelta.clone(); }
    }

    public record CollectionState(int scatterProgress, int[] coins, boolean resetPending) {
        public CollectionState {
            if (scatterProgress < 0 || scatterProgress >= SCATTER_METER_SIZE) throw new IllegalArgumentException("scatter progress must be 0..29");
            if (coins == null || coins.length != COIN_SLOTS) throw new IllegalArgumentException("coin state length must be 5");
            coins = coins.clone();
            for (int coin : coins) if (coin < 0 || coin > COIN_PLATE_CAP) throw new IllegalArgumentException("coin plate exceeds captured cap 128");
        }
        @Override public int[] coins() { return coins.clone(); }
        public static CollectionState initial() { return new CollectionState(0, new int[COIN_SLOTS], false); }
    }

    public record CollectionProjection(int scatterProgress, int[] coins, boolean fullReward,
                                       CollectionBranch branch, CollectionState nextState) {
        public CollectionProjection {
            coins = coins.clone();
            if (nextState == null || branch == null) throw new IllegalArgumentException("complete collection projection required");
        }
        @Override public int[] coins() { return coins.clone(); }
    }

    public static int payMultiplier(int symbol, int count) {
        if (symbol < 1 || symbol > 9 || count < 3 || count > 5) return 0;
        return PAY_MULTIPLIER[symbol][count];
    }

    public static FreePhase freePhase(CompleteRoundFact fact, int deliveryIndex) {
        boolean freeRound = fact.steps().size() == 6;
        if (!freeRound && deliveryIndex == 0) return FreePhase.ABSENT;
        if (freeRound && deliveryIndex >= 0 && deliveryIndex < 5) return FreePhase.ACTIVE;
        if (freeRound && deliveryIndex == 5) return FreePhase.TERMINAL;
        throw new IllegalArgumentException("deliveryIndex is outside the explicit free-state partition");
    }

    public static int freeRemaining(CompleteRoundFact fact, int deliveryIndex) {
        return switch (freePhase(fact, deliveryIndex)) {
            case ABSENT -> 0;
            case ACTIVE -> 5 - deliveryIndex;
            case TERMINAL -> 0;
        };
    }

    public static void validateStructure(CompleteRoundFact fact) {
        int stepCount = fact.steps().size();
        if (stepCount != 1 && stepCount != 6) throw new IllegalArgumentException("round must have one or six delivery steps");
        int expectedInitialAxes = fact.entryKind() == EntryKind.FEATURE_BUY_INITIAL ? 3 : fact.betType();
        if (fact.steps().get(0).boards().size() != expectedInitialAxes) throw new IllegalArgumentException("initial axis count mismatch");
        if (fact.entryKind() == EntryKind.FEATURE_BUY_INITIAL && stepCount != 6) throw new IllegalArgumentException("feature buy must enter the same free state machine");
        if (fact.coinTransition() == CoinTransition.FULL_TRIGGER && stepCount != 1) throw new IllegalArgumentException("coin reward is single response");
        if (fact.coinTransition() == CoinTransition.FULL_TRIGGER && fact.betType() != 3) throw new IllegalArgumentException("captured coin reward only exists on betType 3");
        if (fact.entryKind() == EntryKind.FEATURE_BUY_INITIAL && fact.coinTransition() != CoinTransition.UNCHANGED) throw new IllegalArgumentException("feature buy does not generate a paid coin transition");
        for (int i = 1; i < stepCount; i++) if (fact.steps().get(i).boards().size() != 3) throw new IllegalArgumentException("free step must contain three axes");
        for (Step step : fact.steps()) for (int[] board : step.boards()) for (int symbol : board) {
            if (symbol < 0 || symbol > 10) throw new IllegalArgumentException("unknown symbol " + symbol);
        }
        for (int i = 1; i < stepCount; i++) for (int[] board : fact.steps().get(i).boards()) {
            for (int symbol : board) if (symbol == SCATTER) throw new IllegalArgumentException("Scatter was never observed in FREE_SPIN entry");
            int big = board[1];
            int[] center = {1,2,3,6,7,8,11,12,13};
            for (int position : center) if (board[position] != big) throw new IllegalArgumentException("free center must be one repeated 3x3 material");
        }
        int observedScatterDelta = countSymbol(fact.steps().get(0).boards(), SCATTER);
        if (fact.scatterDelta() != observedScatterDelta) throw new IllegalArgumentException("scatter delta must equal actual paid initial board count");
        BoardCaps.validateCompleteFact(fact);
    }

    /** 按原厂相邻证据投影一次新的付费Round；非法回退、提前填满或无依据复位直接拒绝。 */
    public static CollectionProjection applyCollectionTransition(CollectionState previous, CompleteRoundFact fact) {
        validateStructure(fact);
        if (previous == null) throw new IllegalArgumentException("previous collection state required");
        int scatter = (previous.scatterProgress() + fact.scatterDelta()) % SCATTER_METER_SIZE;
        int[] base = previous.resetPending() ? new int[COIN_SLOTS] : previous.coins();
        if (previous.resetPending()) {
            if (fact.coinTransition() != CoinTransition.UNCHANGED) throw new IllegalArgumentException("first paid round after full reward must expose captured zero reset");
            CollectionState next = new CollectionState(scatter, base, false);
            return new CollectionProjection(scatter, base, false, CollectionBranch.RESET_AFTER_FULL, next);
        }
        return switch (fact.coinTransition()) {
            case UNCHANGED -> {
                CollectionState next = new CollectionState(scatter, base, false);
                yield new CollectionProjection(scatter, base, false, CollectionBranch.UNCHANGED, next);
            }
            case INCREMENT_NONDECREASING -> {
                int[] nextCoins = addCoinDelta(base, fact.coinDelta());
                if (allPositive(nextCoins)) throw new IllegalArgumentException("ordinary increment cannot silently enter full state");
                CollectionState next = new CollectionState(scatter, nextCoins, false);
                yield new CollectionProjection(scatter, nextCoins, false, CollectionBranch.INCREMENT_NONDECREASING, next);
            }
            case FULL_TRIGGER -> {
                int[] full;
                if (Arrays.stream(fact.coinDelta()).sum() == 0) {
                    // SS2旧缓存的满盘member不保存最后一个+1，继续兼容原有数据。
                    int empty = onlyEmptySlot(base);
                    if (empty < 0) throw new IllegalArgumentException("legacy full member requires exactly one empty slot");
                    full = base.clone();
                    full[empty] = 1;
                } else {
                    // 运行时生成允许一次付费局补齐一至两个空盘，增量事实必须完整保存。
                    full = addCoinDelta(base, fact.coinDelta());
                    if (!allPositive(full)) throw new IllegalArgumentException("full trigger must fill every coin slot");
                }
                if (coinCount(full) != fact.coinRewardCount()) throw new IllegalArgumentException("full reward count differs from projected coin state");
                CollectionState next = new CollectionState(scatter, full, true);
                yield new CollectionProjection(scatter, full, true, CollectionBranch.FULL_TRIGGER, next);
            }
        };
    }

    public static boolean canApplyCollectionTransition(CollectionState previous, CompleteRoundFact fact) {
        try { applyCollectionTransition(previous, fact); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static int coinCount(int[] coins) { return Arrays.stream(coins).sum(); }
    public static int countSymbol(List<int[]> boards, int symbol) {
        int count = 0;
        for (int[] board : boards) for (int value : board) if (value == symbol) count++;
        return count;
    }
    private static int[] addCoinDelta(int[] base, int[] delta) {
        int[] result = base.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] += delta[i];
            if (result[i] > COIN_PLATE_CAP) throw new IllegalArgumentException("coin increment exceeds captured plate cap 128");
        }
        return result;
    }
    private static boolean allPositive(int[] values) {
        for (int value : values) if (value == 0) return false;
        return true;
    }
    private static int onlyEmptySlot(int[] values) {
        int empty = -1;
        for (int i = 0; i < values.length; i++) if (values[i] == 0) {
            if (empty >= 0) return -1;
            empty = i;
        }
        return empty;
    }
}
