package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.ResultAnalysis;
import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import com.cpgame.crazy777.generator.model.SpinStep;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立开奖复核。坐标使用证据中的 01..23，即每轴五项里的可见行 1..3。
 * 不读取 RoundFactory 的计算结果。
 */
public final class ResultUtil {
    private static final Map<String, Integer> COLOR = Map.of(
            "H1", 0, "H4", 0, "H2", 1, "H5", 1, "H3", 2, "H6", 2);

    private ResultUtil() {}

    public static void validateBoard(List<String> board, boolean freeMode) {
        if (board == null || board.size() != GameRules.BOARD_SIZE) {
            throw new IllegalArgumentException("rskl 必须恰好包含 15 项");
        }
        int wildTotal = 0;
        int scTotal = 0;
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            int blanks = 0;
            int scAny = 0;
            int scVisible = 0;
            int wilds = 0;
            for (int pos = 0; pos < GameRules.SLOTS_PER_REEL; pos++) {
                String symbol = board.get(reel * GameRules.SLOTS_PER_REEL + pos);
                if (!GameRules.ALL_SYMBOLS.contains(symbol)) {
                    throw new IllegalArgumentException("未知符号: " + symbol);
                }
                if (freeMode && "SC".equals(symbol)) {
                    throw new IllegalArgumentException("免费 Spin 中不得出现 SC");
                }
                if ("BLANK".equals(symbol)) blanks++;
                if ("SC".equals(symbol)) {
                    scAny++;
                    scTotal++;
                    if (pos >= 1 && pos <= 3) scVisible++;
                }
                if ("WILD".equals(symbol)) {
                    wilds++;
                    wildTotal++;
                }
                if (pos > 0) {
                    boolean prevBlank = "BLANK".equals(board.get(reel * GameRules.SLOTS_PER_REEL + pos - 1));
                    if (prevBlank == "BLANK".equals(symbol)) {
                        throw new IllegalArgumentException("每卷轴 BLANK 与符号必须交替");
                    }
                }
            }
            if (blanks < 2 || blanks > 3) throw new IllegalArgumentException("每卷轴必须含 2 或 3 个 BLANK");
            if (scAny > GameRules.SC_PER_REEL_MAX) throw new IllegalArgumentException("单轴 SC 超过抓包上限");
            if (scVisible > GameRules.SC_VISIBLE_PER_REEL_MAX) {
                throw new IllegalArgumentException("单轴可见 SC 超过抓包上限");
            }
            if (wilds > GameRules.WILD_PER_REEL_MAX) throw new IllegalArgumentException("单轴 WILD 超过抓包上限");
        }
        if (!freeMode && scTotal > GameRules.SC_BOARD_MAX) {
            throw new IllegalArgumentException("整盘 SC 超过抓包上限");
        }
        if (wildTotal > GameRules.WILD_BOARD_MAX) throw new IllegalArgumentException("整盘 WILD 超过抓包上限");
    }

    public static boolean isScatterTrigger(List<String> board) {
        validateBoard(board, false);
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            boolean visible = false;
            for (int row : GameRules.VISIBLE_ROWS) {
                if ("SC".equals(board.get(reel * GameRules.SLOTS_PER_REEL + row))) {
                    visible = true;
                    break;
                }
            }
            if (!visible) return false;
        }
        return true;
    }

    public static Map<String, String> evaluateRegularLines(List<String> board) {
        validateBoard(board, false);
        Map<String, String> wins = new LinkedHashMap<>();
        for (int line = 0; line < GameRules.PAYLINES.length; line++) {
            String reward = bestReward(lineSymbols(board, line));
            if (reward != null) wins.put(String.valueOf(line), reward);
        }
        return wins;
    }

    public static Map<String, String> expectedWins(List<String> board, boolean freeMode) {
        if (freeMode) {
            validateBoard(board, true);
            Map<String, String> wins = new LinkedHashMap<>();
            for (int line = 0; line < GameRules.PAYLINES.length; line++) {
                String reward = bestReward(lineSymbols(board, line));
                if (reward != null) wins.put(String.valueOf(line), reward);
            }
            return wins;
        }
        if (isScatterTrigger(board)) return Map.of(GameRules.SCATTER_KEY, "SC");
        return evaluateRegularLines(board);
    }

    public static BigDecimal payout(Map<String, String> wmkl, int bl, BigDecimal bs, int rpx) {
        BigDecimal total = BigDecimal.ZERO;
        for (String reward : wmkl.values()) {
            Integer multiplier = GameRules.PAYTABLE.get(reward);
            if (multiplier == null) throw new IllegalArgumentException("奖表中不存在: " + reward);
            total = total.add(GameRules.betAmount(bl, bs)
                    .multiply(BigDecimal.valueOf(rpx))
                    .multiply(BigDecimal.valueOf(multiplier)));
        }
        return total.stripTrailingZeros();
    }

    public static void verifyStep(SpinStep step) {
        boolean free = step.gt() == 2 && step.nfsc() > 0;
        Map<String, String> expected = expectedWins(step.rskl(), free);
        if (!expected.equals(step.wmkl())) {
            throw new IllegalArgumentException("独立中奖线复核失败 expected=" + expected + ", actual=" + step.wmkl());
        }
        BigDecimal expectedPayout = payout(step.wmkl(), step.bl(), step.bs(), step.rpx());
        if (expectedPayout.compareTo(step.wa()) != 0) {
            throw new IllegalArgumentException("独立赔付复核失败 expected=" + expectedPayout + ", actual=" + step.wa());
        }
    }

    public static ResultAnalysis analyze(RoundResult round) {
        if (round == null || round.steps().isEmpty()) throw new IllegalArgumentException("完整 Round 不能为空");
        BigDecimal bet = GameRules.betAmount(round.bl(), round.bs());
        BigDecimal expectedBalance = round.startingBalance();
        BigDecimal freeAccumulated = BigDecimal.ZERO;
        BigDecimal totalWin = BigDecimal.ZERO;
        int freeDeliveries = 0;
        for (int index = 0; index < round.steps().size(); index++) {
            SpinStep step = round.steps().get(index);
            if (step.bl() != round.bl() || step.bs().compareTo(round.bs()) != 0) {
                throw new IllegalArgumentException("Round 内下注参数漂移");
            }
            verifyStep(step);
            if (index == 0) {
                if (step.ba().compareTo(bet) != 0) throw new IllegalArgumentException("付费起点必须且只扣一次完整下注");
                expectedBalance = expectedBalance.subtract(step.ba());
            } else if (step.ba().compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("后续 Delivery 不得重复扣注");
            }
            expectedBalance = expectedBalance.add(step.wa());
            totalWin = totalWin.add(step.wa());
            if (expectedBalance.compareTo(step.pb()) != 0) {
                throw new IllegalArgumentException("pb 余额链不连续");
            }
            if (step.nfsc() > 0) {
                freeDeliveries++;
                freeAccumulated = freeAccumulated.add(step.wa());
                if (freeAccumulated.compareTo(step.rwa()) != 0) {
                    throw new IllegalArgumentException("免费阶段 rwa 累计不一致");
                }
            } else if (step.rwa().compareTo(step.wa()) != 0) {
                throw new IllegalArgumentException("普通/触发 Step 的 rwa 应等于 wa");
            }
        }
        RoundMode mode;
        if (round.steps().size() == 1 && round.steps().get(0).fsn() == 0) {
            requireOrdinary(round.steps().get(0));
            mode = round.steps().get(0).wmkl().isEmpty() ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
        } else {
            requireFullFree(round.steps());
            mode = RoundMode.FREE_SPINS;
            freeDeliveries = GameRules.FREE_SPIN_COUNT;
        }
        if (mode != round.mode()) throw new IllegalArgumentException("Round 分类与独立反推不一致");
        BigDecimal multiplier = totalWin.divide(bet).stripTrailingZeros();
        return new ResultAnalysis(mode, bet, totalWin.stripTrailingZeros(), multiplier, 1, freeDeliveries,
                round.steps().size());
    }

    public static ResultAnalysis analyze(RoundFacts facts) {
        return analyze(new RoundFactory().restore(facts));
    }

    private static void requireOrdinary(SpinStep step) {
        if (step.gt() != 1 || step.nfsc() != 0 || step.rpx() != GameRules.PAID_RPX
                || step.smallGameType() != 0 || step.ss() != 1) {
            throw new IllegalArgumentException("普通付费单步状态字段不合法");
        }
        if (isScatterTrigger(step.rskl())) throw new IllegalArgumentException("普通局不得触发免费模式");
    }

    private static void requireFullFree(List<SpinStep> steps) {
        if (steps.size() != GameRules.FULL_FREE_STEPS) {
            throw new IllegalArgumentException("免费完整局必须是触发 Step 加 10 个免费 Delivery");
        }
        SpinStep trigger = steps.get(0);
        if (trigger.fsn() != GameRules.FREE_SPIN_COUNT || trigger.nfsc() != 0 || trigger.gt() != 1
                || trigger.rpx() != 1 || trigger.smallGameType() != 2 || trigger.ss() != 1
                || !Map.of(GameRules.SCATTER_KEY, "SC").equals(trigger.wmkl())
                || !isScatterTrigger(trigger.rskl())) {
            throw new IllegalArgumentException("免费模式付费触发 Step 不合法");
        }
        for (int nfsc = 1; nfsc <= GameRules.FREE_SPIN_COUNT; nfsc++) {
            SpinStep step = steps.get(nfsc);
            int expectedSs = nfsc == GameRules.FREE_SPIN_COUNT ? 1 : 0;
            if (step.fsn() != GameRules.FREE_SPIN_COUNT || step.nfsc() != nfsc || step.gt() != 2
                    || step.rpx() != GameRules.FREE_RPX || step.smallGameType() != 2
                    || step.ss() != expectedSs || step.rskl().contains("SC")) {
                throw new IllegalArgumentException("免费 Delivery 状态或归属不合法，nfsc=" + nfsc);
            }
        }
    }

    private static List<String> lineSymbols(List<String> board, int line) {
        List<String> result = new ArrayList<>(3);
        for (int reel = 0; reel < 3; reel++) {
            result.add(board.get(reel * GameRules.SLOTS_PER_REEL + GameRules.PAYLINES[line][reel]));
        }
        return result;
    }

    private static String bestReward(List<String> symbols) {
        if (symbols.stream().anyMatch(s -> "BLANK".equals(s) || "SC".equals(s))) return null;
        String best = null;
        int bestPay = -1;
        for (String candidate : List.of("WILD", "H1", "H2", "H3", "H4", "H5", "H6", "MIX")) {
            if (matches(candidate, symbols) && GameRules.PAYTABLE.get(candidate) > bestPay) {
                best = candidate;
                bestPay = GameRules.PAYTABLE.get(candidate);
            }
        }
        return best;
    }

    private static boolean matches(String candidate, List<String> symbols) {
        if ("WILD".equals(candidate)) return symbols.stream().allMatch("WILD"::equals);
        if (candidate.startsWith("H")) {
            int number = Integer.parseInt(candidate.substring(1));
            String gradient = number <= 3 ? candidate : "H" + (number - 3);
            return symbols.stream().allMatch(s -> "WILD".equals(s) || candidate.equals(s)
                    || (number >= 4 && gradient.equals(s)));
        }
        if ("MIX".equals(candidate)) {
            boolean[] seen = new boolean[3];
            int wilds = 0;
            for (String symbol : symbols) {
                if ("WILD".equals(symbol)) {
                    wilds++;
                    continue;
                }
                Integer color = COLOR.get(symbol);
                if (color == null || seen[color]) return false;
                seen[color] = true;
            }
            int missing = 0;
            for (boolean value : seen) if (!value) missing++;
            return missing == wilds;
        }
        return false;
    }
}
