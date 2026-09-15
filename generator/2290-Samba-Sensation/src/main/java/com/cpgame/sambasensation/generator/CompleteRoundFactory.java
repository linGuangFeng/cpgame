package com.cpgame.sambasensation.generator;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.core.BoardCaps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 完整局工厂。先生成生命周期联合结构，再按列型+入口权重生成可见状态；
 * 不为拼 0 倍而限制中奖符号个数，也不追逐指定倍率。
 */
public final class CompleteRoundFactory {
    private static final int[] NORMAL_BET_TYPE_WEIGHTS = {2293, 95, 2337};
    private static final int[] FREE_BET_TYPE_WEIGHTS = {85, 5, 67};
    private static final int[] SPECIAL_CLASS_WEIGHTS = {158, 17};
    private static final int[] NATURAL_CLASS_WEIGHTS = {4725, 158, 17};
    private static final int[] ORDINARY_OUTCOME_WEIGHTS = {4079, 646};
    /** 逐列三格的联合相等关系，分别来自三种bet_type的训练列计数。 */
    private static final int[][] COLUMN_PATTERN_WEIGHTS = {
            {1265,2599,2593,33,5400}, {98,199,202,2,499}, {3648,7825,7618,31,17193}
    };
    private static final int[][] FEATURE_NON_SCATTER = {{3,3,4,5,6,6},{2,7,8,8,9},{2,4,4,4}};
    private static final int[] FULL_COIN_COUNTS = {5,6,8,9,9,9,10,11,11,13,14,14,14,16,21,23,497};
    /** 683个非满槽单格+1增量按轴位计数；另有1次原厂双格增量[0,2,0,0,4]。 */
    private static final int[] SINGLE_COIN_POSITION_WEIGHTS = {156,166,154,43,164};
    private static final int[] RARE_TWO_SLOT_DELTA = {0,2,0,0,4};
    private static final int MAX_BOARD_ATTEMPTS = 10000;

    private final GeneratorConfig config;
    private final java.util.Map<Integer,ZeroLossSupport<GeneratedRound>> losses;

    public CompleteRoundFactory(GeneratorConfig config) {
        this.config=config;var pools=new java.util.HashMap<Integer,ZeroLossSupport<GeneratedRound>>();
        var setup=new java.security.SecureRandom();
        for(int type=1;type<=3;type++){final int t=type;pools.put(t,new ZeroLossSupport<>(()->lossCandidate(setup,t),r->r.evaluation().roundClass()==GameRuleCore.RoundClass.ORDINARY_LOSS,r->r));}
        losses=java.util.Map.copyOf(pools);
    }

    public GeneratedRound generateNormal(RandomGenerator random) {
        GameRuleCore.RoundClass selected = weightedIndex(random, ORDINARY_OUTCOME_WEIGHTS) == 0
                ? GameRuleCore.RoundClass.ORDINARY_LOSS : GameRuleCore.RoundClass.ORDINARY_WIN;
        int betType = weightedIndex(random, NORMAL_BET_TYPE_WEIGHTS) + 1;
        if(selected==GameRuleCore.RoundClass.ORDINARY_LOSS)return generateLoss(random,betType);
        for (int attempt = 0; attempt < MAX_BOARD_ATTEMPTS; attempt++) {
            List<int[]> initial = paidPage(random, betType);
            CoinFact coin = randomNonFullCoinTransition(random);
            GameRuleCore.CompleteRoundFact fact = fact(GameRuleCore.EntryKind.PAID_INITIAL, betType, initial,
                    coin, List.of(new GameRuleCore.Step(initial)));
            GeneratedRound generated = verified(fact);
            if (generated.evaluation().roundClass() == selected) return generated;
        }
        throw new RoundRejectedException("ordinary joint outcome sampler exhausted");
    }

    public GeneratedRound generateSpecial(RandomGenerator random) {
        int special = weightedIndex(random, SPECIAL_CLASS_WEIGHTS);
        if (special == 0) return generateFree(random, true);
        if (special == 1) return generateCoinReward(random);
        throw new IllegalStateException("special class partition incomplete");
    }

    public GeneratedRound generateNatural(RandomGenerator random) {
        int kind = weightedIndex(random, NATURAL_CLASS_WEIGHTS);
        if (kind == 0) return generateNormal(random);
        if (kind == 1) return generateFree(random, false);
        if (kind == 2) return generateCoinReward(random);
        throw new IllegalStateException("natural class partition incomplete");
    }

    public GeneratedRound generateFree(RandomGenerator random) { return generateFree(random, false); }

    private GeneratedRound generateFree(RandomGenerator random, boolean amplifiedForSpecialPool) {
        boolean featureBuy = amplifiedForSpecialPool ? random.nextInt(4) == 0 : random.nextInt(158) == 0;
        GameRuleCore.EntryKind entry = featureBuy ? GameRuleCore.EntryKind.FEATURE_BUY_INITIAL : GameRuleCore.EntryKind.PAID_INITIAL;
        int betType = featureBuy ? 1 : weightedIndex(random, FREE_BET_TYPE_WEIGHTS) + 1;
        List<GameRuleCore.Step> steps = new ArrayList<>(6);
        steps.add(new GameRuleCore.Step(featureBuy ? featureBuyPage(random) : paidPage(random, betType)));
        for (int spin = 0; spin < GameRuleCore.FREE_STEPS; spin++) steps.add(new GameRuleCore.Step(freePage(random)));
        CoinFact coin = featureBuy ? CoinFact.unchanged() : randomNonFullCoinTransition(random);
        GameRuleCore.CompleteRoundFact fact = fact(entry, betType, steps.get(0).boards(), coin, steps);
        return verified(fact);
    }

    public GeneratedRound generateCoinReward(RandomGenerator random) {
        List<int[]> initial = paidPage(random, 3);
        int count = FULL_COIN_COUNTS[random.nextInt(FULL_COIN_COUNTS.length)];
        CoinFact coin = new CoinFact(GameRuleCore.CoinTransition.FULL_TRIGGER, new int[5], count);
        GameRuleCore.CompleteRoundFact fact = fact(GameRuleCore.EntryKind.PAID_INITIAL, 3, initial,
                coin, List.of(new GameRuleCore.Step(initial)));
        return verified(fact);
    }

    private GameRuleCore.CompleteRoundFact fact(GameRuleCore.EntryKind entry, int betType, List<int[]> initial,
                                                CoinFact coin, List<GameRuleCore.Step> steps) {
        int scatterDelta = GameRuleCore.countSymbol(initial, GameRuleCore.SCATTER);
        return new GameRuleCore.CompleteRoundFact(entry, betType, scatterDelta, coin.delta(),
                coin.transition(), coin.rewardCount(), steps);
    }

    private GeneratedRound verified(GameRuleCore.CompleteRoundFact fact) {
        ResultUtil.Evaluation evaluation = ResultUtil.evaluate(fact);
        return new GeneratedRound(fact, evaluation);
    }

    private List<int[]> paidPage(RandomGenerator random, int betType) {
        for (int attempt = 0; attempt < MAX_BOARD_ATTEMPTS; attempt++) {
            List<int[]> boards = new ArrayList<>(betType);
            for (int axis = 0; axis < betType; axis++) boards.add(paidBoard(random, betType, axis));
            try { BoardCaps.validatePaid(betType, boards); return boards; }
            catch (IllegalArgumentException rejected) { /* 整页重发，不改符号凑合法。 */ }
        }
        throw new RoundRejectedException("paid page could not satisfy captured caps");
    }

    private int[] paidBoard(RandomGenerator random, int betType, int axis) {
        int[] board = new int[15];
        int[] symbolWeights = config.paidWeights[betType - 1][axis];
        for (int column = 0; column < 5; column++) {
            int pattern = weightedIndex(random, COLUMN_PATTERN_WEIGHTS[betType - 1]);
            int a = weightedIndex(random, symbolWeights);
            int b = distinct(random, symbolWeights, a, -1);
            int c = distinct(random, symbolWeights, a, b);
            int[] values = switch (pattern) {
                case 0 -> new int[]{a,a,a};
                case 1 -> new int[]{a,a,b};
                case 2 -> new int[]{a,b,b};
                case 3 -> new int[]{a,b,a};
                case 4 -> new int[]{a,b,c};
                default -> throw new IllegalStateException("column pattern partition incomplete");
            };
            for (int row = 0; row < 3; row++) board[row * 5 + column] = values[row];
        }
        return board;
    }

    private List<int[]> freePage(RandomGenerator random) {
        for (int attempt = 0; attempt < MAX_BOARD_ATTEMPTS; attempt++) {
            List<int[]> boards = new ArrayList<>(3);
            for (int axis = 0; axis < 3; axis++) {
                int[] board = new int[15];
                int big = weightedIndex(random, config.freeBigWeights[axis]);
                int[] center = {1,2,3,6,7,8,11,12,13};
                for (int position : center) board[position] = big;
                int[] outer = {0,4,5,9,10,14};
                for (int position : outer) board[position] = weightedIndex(random, config.freeOuterWeights[axis]);
                boards.add(board);
            }
            try { BoardCaps.validateFree(boards); return boards; }
            catch (IllegalArgumentException rejected) { /* 三轴整页重发。 */ }
        }
        throw new RoundRejectedException("free page could not satisfy captured caps");
    }

    private List<int[]> featureBuyPage(RandomGenerator random) {
        for (int attempt = 0; attempt < MAX_BOARD_ATTEMPTS; attempt++) {
            List<int[]> boards = new ArrayList<>(3);
            for (int axis = 0; axis < 3; axis++) {
                int[] board = new int[15];
                Arrays.fill(board, GameRuleCore.SCATTER);
                List<Integer> materials = new ArrayList<>();
                for (int value : FEATURE_NON_SCATTER[axis]) materials.add(value);
                Collections.shuffle(materials, new JavaUtilRandomAdapter(random));
                int materialIndex = 0;
                for (int position = 0; position < 15; position++) if (!contains(BoardCaps.BUY_SCATTER_POSITIONS[axis], position)) board[position] = materials.get(materialIndex++);
                boards.add(board);
            }
            try { BoardCaps.validateFeatureBuy(boards); return boards; }
            catch (IllegalArgumentException rejected) { /* 只重新排列非 Scatter 材质。 */ }
        }
        throw new RoundRejectedException("feature-buy page could not satisfy captured caps");
    }

    private CoinFact randomNonFullCoinTransition(RandomGenerator random) {
        int branch = weightedIndex(random, config.coinTransitionWeights);
        if (branch == 0) return CoinFact.unchanged();
        int[] delta = new int[5];
        // 699次非递减分支中仅1次双格增量，其余均为单格+1；这是经验权重，不声称原厂长期概率。
        if (random.nextInt(699) == 0) delta = RARE_TWO_SLOT_DELTA.clone();
        else delta[weightedIndex(random, SINGLE_COIN_POSITION_WEIGHTS)] = 1;
        return new CoinFact(GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, delta, 0);
    }

    private static int[] boundedPositiveComposition(RandomGenerator random, int total, int parts, int max) {
        int[] result = new int[parts];
        Arrays.fill(result, 1);
        distribute(random, result, total - parts, max);
        return result;
    }

    private static int[] boundedComposition(RandomGenerator random, int total, int parts, int max) {
        int[] result = new int[parts];
        distribute(random, result, total, max);
        return result;
    }

    private static void distribute(RandomGenerator random, int[] result, int remaining, int max) {
        while (remaining > 0) {
            int position = random.nextInt(result.length);
            if (result[position] < max) { result[position]++; remaining--; }
        }
    }

    private static int distinct(RandomGenerator random, int[] weights, int first, int second) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            int value = weightedIndex(random, weights);
            if (value != first && value != second) return value;
        }
        throw new RoundRejectedException("unable to draw distinct column symbols");
    }

    static int weightedIndex(RandomGenerator random, int[] weights) {
        long total = 0;
        for (int weight : weights) total += weight;
        if (total <= 0) throw new IllegalArgumentException("weights must have a positive sum");
        long draw = random.nextLong(total);
        long cursor = 0;
        for (int i = 0; i < weights.length; i++) {
            cursor += weights[i];
            if (draw < cursor) return i;
        }
        throw new IllegalStateException("weighted selection failed");
    }

    private static boolean contains(int[] values, int needle) {
        for (int value : values) if (value == needle) return true;
        return false;
    }

    public record GeneratedRound(GameRuleCore.CompleteRoundFact fact, ResultUtil.Evaluation evaluation) { }
    private record CoinFact(GameRuleCore.CoinTransition transition, int[] delta, int rewardCount) {
        CoinFact { delta = delta.clone(); }
        @Override public int[] delta() { return delta.clone(); }
        static CoinFact unchanged() { return new CoinFact(GameRuleCore.CoinTransition.UNCHANGED, new int[5], 0); }
    }
    public static final class RoundRejectedException extends RuntimeException { public RoundRejectedException(String message) { super(message); } }

    /** Collections.shuffle 只接受 Random；适配器仍委托给调用方的安全/测试随机源。 */
    private static final class JavaUtilRandomAdapter extends java.util.Random {
        private final RandomGenerator delegate;
        JavaUtilRandomAdapter(RandomGenerator delegate) { this.delegate = delegate; }
        @Override protected int next(int bits) { return delegate.nextInt() >>> (32 - bits); }
    }

    public GeneratedRound generateLoss(RandomGenerator random,int betType){return losses.get(betType).generate(()->lossCandidate(random,betType),random::nextInt);}
    public GeneratedRound lossCandidate(RandomGenerator random,int betType){
        List<int[]> boards=new ArrayList<>();
        for(int axis=0;axis<betType;axis++){
            int[] b=new int[15],counts=new int[11];boolean[] first=new boolean[11];
            for(int col=0;col<5;col++){
                boolean[] used=new boolean[11];
                for(int row=0;row<3;row++){
                    int[] w=config.paidWeights[betType-1][axis].clone();w[0]=w[10]=0;
                    for(int v=1;v<=9;v++)if(counts[v]>=2||used[v]||(col==1&&first[v]))w[v]=0;
                    int v=weightedIndex(random,w);b[row*5+col]=v;counts[v]++;used[v]=true;if(col==0)first[v]=true;
                }
            }
            boards.add(b);
        }
        BoardCaps.validatePaid(betType,boards);
        CoinFact coin=randomNonFullCoinTransition(random);
        return verified(fact(GameRuleCore.EntryKind.PAID_INITIAL,betType,boards,coin,List.of(new GameRuleCore.Step(boards))));
    }
}
