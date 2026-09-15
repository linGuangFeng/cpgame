package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 独立结果复核器：只按已验收规则重算，不信任 GameRuleCore 给出的中奖或金额。 */
public final class ResultUtil {
    private ResultUtil() {}

    public static WinEvaluation evaluate(List<String> board, BigDecimal betSize, int betLevel, int rpx) {
        requireBoard(board);
        Map<String, Map<String, Integer>> wins = new LinkedHashMap<String, Map<String, Integer>>();
        Map<Integer, BigDecimal> lineAwards = new LinkedHashMap<Integer, BigDecimal>();
        BigDecimal total = BigDecimal.ZERO;
        for (int lineIndex=0; lineIndex<GameRules.PAYLINES.length; lineIndex++) {
            Candidate best = null;
            for (String candidate : GameRules.SYMBOLS) {
                if (GameRules.SCATTER.equals(candidate)) continue;
                Candidate evaluated = evaluateCandidate(board, lineIndex, candidate, betSize, betLevel, rpx);
                if (evaluated != null && (best == null || evaluated.award.compareTo(best.award) > 0)) best = evaluated;
            }
            if (best != null && best.award.signum() > 0) {
                int lineNumber = lineIndex + 1;
                Map<String, Integer> one = new LinkedHashMap<String, Integer>();
                one.put(best.symbol, best.count);
                wins.put(String.valueOf(lineNumber), one);
                lineAwards.put(lineNumber, best.award);
                total = total.add(best.award);
            }
        }
        return new WinEvaluation(money(total), wins, lineAwards);
    }

    private static Candidate evaluateCandidate(List<String> board, int lineIndex, String candidate,
                                               BigDecimal betSize, int betLevel, int rpx) {
        int count = 0;
        boolean containsWild = false;
        for (int reel=0; reel<GameRules.REELS; reel++) {
            String actual = board.get(reel * GameRules.ROWS + GameRules.PAYLINES[lineIndex][reel]);
            boolean match = GameRules.WILD.equals(candidate) ? GameRules.WILD.equals(actual)
                : candidate.equals(actual) || GameRules.WILD.equals(actual);
            if (!match) break;
            count++;
            if (GameRules.WILD.equals(actual)) containsWild = true;
        }
        Integer multiplier = GameRules.PAYTABLE.get(candidate).get(count);
        if (multiplier == null || multiplier == 0) return null;
        BigDecimal award = betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(multiplier));
        if (containsWild) award = award.multiply(BigDecimal.valueOf(2));
        if (rpx != 0) award = award.multiply(BigDecimal.valueOf(rpx));
        return new Candidate(candidate, count, money(award));
    }

    /**
     * 从牌面和押注重新反推模式、金额、免费局进度与终止点。
     * 本方法不采用待校验对象中的 wa/rwa/wmkl 作为期望值。
     */
    public static RoundResult infer(GeneratedRound round) {
        if (round == null || round.steps == null || round.steps.isEmpty()) throw new IllegalArgumentException("Round 不能为空");
        if (!GameRules.BET_SIZES.contains(round.betSize) || !GameRules.BET_LEVELS.contains(round.betLevel))
            throw new IllegalArgumentException("押注不在当前游戏配置内");
        SpinStep first = round.steps.get(0);
        requireBoard(first.rskl);
        int paidScatters = scatterCount(first.rskl);
        boolean freeMode = paidScatters >= 3;
        int initialFreeSpins = freeMode ? first.fsn : 0;
        int freeMultiplier = freeMode ? first.rpx : 0;
        if (freeMode) {
            if (!contains(GameRules.initialChoices(paidScatters), initialFreeSpins)) fail("初始免费次数", 0);
            if (freeMultiplier != 2 && freeMultiplier != 5 && freeMultiplier != 8) fail("免费倍率", 0);
        } else if (first.fsn != 0 || first.rpx != 0 || round.steps.size() != 1) {
            fail("普通局边界", 0);
        }

        BigDecimal cumulative = BigDecimal.ZERO;
        int scheduled = initialFreeSpins;
        int retriggerCount = 0;
        int consecutiveWins = 0;
        int maximumConsecutiveWins = 0;
        for (int i=0; i<round.steps.size(); i++) {
            SpinStep step = round.steps.get(i);
            requireBoard(step.rskl);
            BigDecimal expectedBa = i == 0 ? round.totalBet() : BigDecimal.ZERO;
            if (step.ba.compareTo(expectedBa) != 0) fail("ba", i);
            if (i == 0) {
                if (step.gt != 1 || step.small_game_type != 0 || step.ss != 1 || step.nfsc != 0)
                    fail("付费起点", i);
            } else {
                if (!freeMode || step.gt != 2 || step.small_game_type != 2 || step.nfsc != i || step.rpx != freeMultiplier)
                    fail("免费Delivery", i);
                int scatters = scatterCount(step.rskl);
                if (scatters >= 3) {
                    scheduled += GameRules.scatterAward(scatters);
                    retriggerCount++;
                }
                if (step.fsn != scheduled) fail("免费次数进度", i);
                int expectedSs = step.nfsc == scheduled ? 1 : 0;
                if (step.ss != expectedSs) fail("终止状态", i);
            }
            WinEvaluation oracle = evaluate(step.rskl, round.betSize, round.betLevel, step.rpx);
            if (step.wa.compareTo(oracle.award) != 0 || !sameMatches(step.wmkl, oracle.matches)) fail("中奖复核", i);
            cumulative = cumulative.add(step.wa);
            if (step.rwa.compareTo(money(cumulative)) != 0) fail("rwa", i);
            if (i < round.steps.size()-1 && step.terminal()) fail("提前终止", i);
            if (step.wa.signum() > 0) {
                consecutiveWins++;
                maximumConsecutiveWins = Math.max(maximumConsecutiveWins, consecutiveWins);
            } else {
                consecutiveWins = 0;
            }
        }
        if (!round.terminal() || (freeMode && round.steps.size() - 1 != scheduled))
            throw new IllegalArgumentException("Round 未合法终止");
        String mode = freeMode ? "FREE_SPINS" : (cumulative.signum() == 0 ? "ORDINARY_LOSS" : "ORDINARY_WIN");
        return new RoundResult(mode, money(cumulative), initialFreeSpins, freeMultiplier,
            round.steps.size() - 1, retriggerCount, maximumConsecutiveWins);
    }

    /** 兼容旧调用；新增代码应通过独立 RoundVerifier。 */
    public static void validate(GeneratedRound round) {
        RoundVerifier.verify(round);
    }

    private static boolean sameMatches(Object actual, Map<String, Map<String, Integer>> expected) {
        if (expected.isEmpty()) {
            if (actual instanceof List) return ((List<?>) actual).isEmpty();
            if (actual instanceof Map) return ((Map<?,?>) actual).isEmpty();
            return false;
        }
        return actual instanceof Map && expected.equals(actual);
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    public static int scatterCount(List<String> board) {
        int count = 0;
        for (String symbol : board) if (GameRules.SCATTER.equals(symbol)) count++;
        return count;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static void requireBoard(List<String> board) {
        if (board == null || board.size() != GameRules.REELS * GameRules.ROWS) throw new IllegalArgumentException("rskl 必须为15格");
        for (String symbol : board) if (!GameRules.SYMBOLS.contains(symbol)) throw new IllegalArgumentException("未知符号: " + symbol);
        if (scatterCount(board)>5) throw new IllegalArgumentException("当前规则只定义3/4/5个Scat分支");
    }

    private static void fail(String field, int step) {
        throw new IllegalArgumentException("Step " + step + " 的 " + field + " 不符合独立规则复核");
    }

    private static final class Candidate {
        private final String symbol; private final int count; private final BigDecimal award;
        private Candidate(String symbol, int count, BigDecimal award) { this.symbol=symbol; this.count=count; this.award=award; }
    }
}
