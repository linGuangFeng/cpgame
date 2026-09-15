package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;
import com.cpgame.luckycatii.model.RoundStep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent settlement oracle. Does not call RoundFactory or GameRuleCore and does not read fixtures.
 */
public final class ResultUtil {
    private ResultUtil() {}

    public static ResultAnalysis analyze(RoundResult round) {
        if (round == null) throw new IllegalArgumentException("完整 Round 不能为空");
        ResultAnalysis inferred = analyze(new RoundFacts(round.roundKey(), round.createdAtEpochSecond(),
                round.betSize(), round.betLevel(), round.paidBoard(), round.finalBoard(), round.rpx(),
                round.gameMode() == 1));
        if (round.gameMode() != inferred.gameMode()) throw new IllegalArgumentException("gm 与牌面不一致");
        if (round.respinReelIndex() != inferred.respinReelIndex()) throw new IllegalArgumentException("rdri 与触发轴不一致");
        if (!inferred.winningLines().equals(round.winningLines())) throw new IllegalArgumentException("wmkl 与终盘不一致");
        if (inferred.award().compareTo(round.award()) != 0) throw new IllegalArgumentException("wa 公式不一致");
        if (inferred.betAmount().compareTo(round.betAmount()) != 0) throw new IllegalArgumentException("ba 与 bl*bs*5 不一致");
        if (round.rpx() != inferred.rpx()) throw new IllegalArgumentException("rpx 不一致");
        verifySteps(round, inferred);
        return inferred;
    }

    public static ResultAnalysis analyze(RoundFacts facts) {
        if (facts == null) throw new IllegalArgumentException("完整 Round 事实不能为空");
        if (facts.roundKey() == null || facts.roundKey().isBlank()) throw new IllegalArgumentException("roundKey 不能为空");
        if (facts.createdAtEpochSecond() <= 0) throw new IllegalArgumentException("创建时间无效");
        if (!GameRules.legalBet(facts.betSize(), facts.betLevel())) throw new IllegalArgumentException("非法下注档位");
        List<String> paid = requireBoard(facts.paidBoard(), "S01");
        List<String> fin = requireBoard(facts.finalBoard(), "S02/rskl");
        enforceWildCaps(paid);
        enforceWildCaps(fin);

        Map<Integer, String> paidWins = evaluatePaylines(paid);
        LuckyTrigger trigger = findLuckyTrigger(paid);
        boolean lucky = facts.luckyRespin();
        if (lucky) {
            if (trigger == null) throw new IllegalArgumentException("Lucky Respin 前缀 WILD 触发条件非法");
            if (!paidWins.isEmpty()) throw new IllegalArgumentException("Lucky Respin 付费起点不得已有线奖");
            for (int reel = 0; reel < 3; reel++) {
                if (reel != trigger.respinReelIndex() && !reel(paid, reel).equals(reel(fin, reel))) {
                    throw new IllegalArgumentException("Lucky Respin 非重转转轴发生变化");
                }
            }
        } else if (trigger != null) {
            throw new IllegalArgumentException("满足 Lucky Respin 触发条件的付费牌面不能伪装成普通局");
        } else if (!paid.equals(fin)) {
            throw new IllegalArgumentException("普通局 S01 与终盘必须相同");
        }

        Map<Integer, String> wins = evaluatePaylines(fin);
        boolean wheel = isWheelBoard(fin);
        int rpx = facts.rpx();
        if (wheel) {
            if (!GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(rpx) || rpx > GameRules.WHEEL_RULE_MAXIMUM) {
                throw new IllegalArgumentException("Wheel 倍率不在当前证据确认集合中");
            }
        } else if (rpx != 1) {
            throw new IllegalArgumentException("Wheel 触发与 rpx 不一致");
        }
        BigDecimal betAmount = GameRules.betAmount(facts.betSize(), facts.betLevel());
        BigDecimal award = calculateAward(wins, facts.betSize(), facts.betLevel(), rpx);
        int units = integerMultiplier(award, facts.betSize(), facts.betLevel());
        int gm = lucky ? 1 : 0;
        int rdri = lucky ? trigger.respinReelIndex() : 0;
        List<String> pools = new ArrayList<>();
        RoundMode pool;
        if (lucky) {
            pools.add("LUCKY_RESPIN");
            pool = RoundMode.LUCKY_RESPIN;
        } else if (wheel) {
            pool = RoundMode.MULTIPLIER_WHEEL;
        } else {
            pool = award.signum() == 0 ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
            pools.add(pool.name());
        }
        if (wheel) pools.add("MULTIPLIER_WHEEL");
        if (lucky || wheel) pool = lucky ? RoundMode.LUCKY_RESPIN : RoundMode.MULTIPLIER_WHEEL;
        return new ResultAnalysis(redisPool(lucky, wheel, award), lucky, wheel, gm, rdri, rpx, units,
                Map.copyOf(wins), betAmount, award, List.copyOf(pools), true);
    }

    public static RoundMode redisPool(boolean lucky, boolean wheel, BigDecimal award) {
        if (lucky || wheel) return wheel && !lucky ? RoundMode.MULTIPLIER_WHEEL : RoundMode.LUCKY_RESPIN;
        return award.signum() == 0 ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
    }

    public static boolean isSpecialPool(ResultAnalysis analysis) {
        return analysis.luckyRespin() || analysis.wheel();
    }

    public static Map<Integer, String> evaluatePaylines(List<String> board) {
        requireBoard(board, "rskl");
        Map<Integer, String> wins = new TreeMap<>();
        for (int line = 0; line < GameRules.PAYLINE_ROWS.length; line++) {
            String[] cells = new String[3];
            for (int reel = 0; reel < 3; reel++) {
                cells[reel] = board.get(GameRules.cellIndex(reel, GameRules.PAYLINE_ROWS[line][reel]));
            }
            String paid = paidSymbol(cells);
            if (paid != null) wins.put(line + 1, paid);
        }
        return wins;
    }

    public static BigDecimal calculateAward(Map<Integer, String> wins, BigDecimal betSize, int betLevel, int rpx) {
        int units = 0;
        for (String symbol : wins.values()) {
            Integer pay = GameRules.PAYTABLE.get(symbol);
            if (pay == null) throw new IllegalArgumentException("未知赔付符号: " + symbol);
            units += pay;
        }
        return betSize.multiply(BigDecimal.valueOf(betLevel))
                .multiply(BigDecimal.valueOf(units))
                .multiply(BigDecimal.valueOf(rpx))
                .stripTrailingZeros();
    }

    public static int integerMultiplier(BigDecimal award, BigDecimal betSize, int betLevel) {
        if (award.signum() == 0) return 0;
        BigDecimal unit = betSize.multiply(BigDecimal.valueOf(betLevel));
        return award.divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
    }

    public static boolean isWheelBoard(List<String> board) {
        requireBoard(board, "rskl");
        String seen = null;
        for (String symbol : board) {
            if ("WILD".equals(symbol)) continue;
            if (seen == null) seen = symbol;
            else if (!seen.equals(symbol)) return false;
        }
        return true;
    }

    public static LuckyTrigger findLuckyTrigger(List<String> initialBoard) {
        requireBoard(initialBoard, "S01");
        if (!evaluatePaylines(initialBoard).isEmpty()) return null;
        for (int first = 0; first < 3; first++) {
            for (int second = first + 1; second < 3; second++) {
                if (intersects(compatibleTargets(initialBoard, first), compatibleTargets(initialBoard, second))) {
                    return new LuckyTrigger(3 - first - second, List.of(first, second));
                }
            }
        }
        return null;
    }

    public static void verify(RoundResult round) {
        analyze(round);
    }

    private static void verifySteps(RoundResult round, ResultAnalysis inferred) {
        List<RoundStep> steps = round.steps();
        if (inferred.luckyRespin()) {
            if (steps.size() != 2) throw new IllegalArgumentException("Lucky Respin 必须恰好两步");
            RoundStep paid = steps.get(0);
            RoundStep free = steps.get(1);
            if (!paid.paid() || free.paid()
                    || !"S01_PAID_SPIN_START".equals(paid.id())
                    || !"S02_LUCKY_RESPIN_FINAL".equals(free.id())) {
                throw new IllegalArgumentException("Lucky Respin 步骤边界非法");
            }
            if (!round.paidBoard().equals(paid.board()) || !round.finalBoard().equals(free.board())) {
                throw new IllegalArgumentException("Lucky Respin 步骤牌面与事实不一致");
            }
            if (!round.respinSymbols().equals(reel(round.paidBoard(), inferred.respinReelIndex()))) {
                throw new IllegalArgumentException("rdskl 与付费起始牌面不一致");
            }
        } else {
            if (steps.size() != 1) throw new IllegalArgumentException("普通局只能有一个付费步骤");
            RoundStep paid = steps.get(0);
            if (!paid.paid() || !"S01_PAID_BOARD".equals(paid.id())
                    || !round.finalBoard().equals(paid.board())
                    || !round.paidBoard().equals(round.finalBoard())
                    || !round.respinSymbols().isEmpty()) {
                throw new IllegalArgumentException("普通局步骤边界非法");
            }
        }
    }

    private static String paidSymbol(String[] cells) {
        String target = null;
        for (String cell : cells) {
            if (!"WILD".equals(cell)) {
                target = cell;
                break;
            }
        }
        if (target == null) return "WILD";
        for (String cell : cells) {
            if (!"WILD".equals(cell) && !target.equals(cell)) return null;
        }
        return target;
    }

    private static Set<String> compatibleTargets(List<String> board, int reel) {
        Set<String> targets = new java.util.LinkedHashSet<>();
        for (String target : GameRules.SYMBOLS) {
            if ("WILD".equals(target)) continue;
            boolean seenTarget = false;
            boolean ok = true;
            for (int row = 0; row < 3; row++) {
                String cell = board.get(GameRules.cellIndex(reel, row));
                if (!seenTarget && "WILD".equals(cell)) continue;
                if (target.equals(cell)) seenTarget = true;
                else {
                    ok = false;
                    break;
                }
            }
            if (ok) targets.add(target);
        }
        return targets;
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) if (right.contains(value)) return true;
        return false;
    }

    public static List<String> reel(List<String> board, int reelIndex) {
        return List.copyOf(board.subList(reelIndex * 3, reelIndex * 3 + 3));
    }

    public static List<String> requireBoard(List<String> board, String label) {
        if (board == null || board.size() != 9) throw new IllegalArgumentException(label + " 必须是 9 个当前游戏符号");
        for (String symbol : board) {
            if (!GameRules.PAYTABLE.containsKey(symbol)) {
                throw new IllegalArgumentException(label + " 含非法符号: " + symbol);
            }
        }
        return board;
    }

    public static void enforceWildCaps(List<String> board) {
        int total = 0;
        for (int reel = 0; reel < 3; reel++) {
            int reelWild = 0;
            for (int row = 0; row < 3; row++) {
                if ("WILD".equals(board.get(GameRules.cellIndex(reel, row)))) {
                    reelWild++;
                    total++;
                }
            }
            if (reelWild > GameRules.MAX_WILD_PER_REEL) {
                throw new IllegalArgumentException("单轴 WILD 超过抓包上限");
            }
        }
        if (total > GameRules.MAX_WILD_PER_BOARD) throw new IllegalArgumentException("整盘 WILD 超过抓包上限");
    }

    public record LuckyTrigger(int respinReelIndex, List<Integer> heldReels) {}
}
