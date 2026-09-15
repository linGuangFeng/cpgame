package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import com.cpgame.crazy777.generator.model.SpinStep;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 完整 Round 真值工厂；刻意不调用 ResultUtil，便于独立反推核验。 */
public final class RoundFactory {
    private final IdentitySource identitySource;

    public RoundFactory() {
        this(new SecureIdentitySource());
    }

    public RoundFactory(IdentitySource identitySource) {
        this.identitySource = identitySource;
    }

    public RoundResult create(RoundCandidate candidate, int bl, BigDecimal bs, BigDecimal startingBalance) {
        return create(identitySource.nextKey(), candidate.boards(), bl, bs, startingBalance);
    }

    public RoundResult restore(RoundFacts facts) {
        return create(facts.roundKey(), facts.boards(), facts.betLevel(), facts.betSize(),
                GameRules.betAmount(facts.betLevel(), facts.betSize()).multiply(BigDecimal.TEN));
    }

    public RoundResult restore(RoundFacts facts, BigDecimal startingBalance) {
        return create(facts.roundKey(), facts.boards(), facts.betLevel(), facts.betSize(), startingBalance);
    }

    private RoundResult create(String roundKey, List<List<String>> boards, int bl, BigDecimal bs,
                               BigDecimal startingBalance) {
        BigDecimal bet = GameRules.betAmount(bl, bs);
        if (startingBalance.compareTo(bet) < 0) throw new IllegalArgumentException("余额不足");
        if (boards == null || boards.isEmpty()) throw new IllegalArgumentException("最小事实至少需要一个牌面");
        if (isScatterTrigger(boards.get(0))) {
            if (boards.size() != GameRules.FULL_FREE_STEPS) {
                throw new IllegalArgumentException("免费完整局必须包含触发牌面和 10 个免费牌面");
            }
            return freeRound(roundKey, boards, bl, bs, startingBalance, bet);
        }
        if (boards.size() != 1) throw new IllegalArgumentException("普通局最小事实只能包含一个牌面");
        return ordinary(roundKey, boards.get(0), bl, bs, startingBalance, bet);
    }

    private RoundResult ordinary(String roundKey, List<String> board, int bl, BigDecimal bs,
                                 BigDecimal start, BigDecimal bet) {
        if (isScatterTrigger(board)) throw new IllegalArgumentException("普通局候选不得触发免费模式");
        validateBoard(board, false);
        Map<String, String> wins = generatedLineWins(board);
        BigDecimal win = generatedPayout(wins, bl, bs, GameRules.PAID_RPX);
        BigDecimal pb = start.subtract(bet).add(win);
        SpinStep step = new SpinStep(bet, bl, bs, 0, 1, 0, pb, 1, board, win, 0, 1, win, wins);
        RoundMode mode = wins.isEmpty() ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
        return new RoundResult(roundKey, mode, bl, bs, start, List.of(step));
    }

    private RoundResult freeRound(String roundKey, List<List<String>> boards, int bl, BigDecimal bs,
                                  BigDecimal start, BigDecimal bet) {
        if (!isScatterTrigger(boards.get(0))) throw new IllegalArgumentException("免费触发牌面缺少可见三轴 SC");
        List<SpinStep> steps = new ArrayList<>(boards.size());
        BigDecimal triggerWin = generatedPayout(Map.of(GameRules.SCATTER_KEY, "SC"), bl, bs, 1);
        BigDecimal balance = start.subtract(bet).add(triggerWin);
        steps.add(new SpinStep(bet, bl, bs, 10, 1, 0, balance, 1, boards.get(0), triggerWin, 2, 1, triggerWin,
                Map.of(GameRules.SCATTER_KEY, "SC")));
        BigDecimal freeAccumulated = BigDecimal.ZERO;
        for (int nfsc = 1; nfsc <= GameRules.FREE_SPIN_COUNT; nfsc++) {
            List<String> board = boards.get(nfsc);
            validateBoard(board, true);
            Map<String, String> wins = generatedLineWins(board);
            BigDecimal win = generatedPayout(wins, bl, bs, GameRules.FREE_RPX);
            freeAccumulated = freeAccumulated.add(win);
            balance = balance.add(win);
            steps.add(new SpinStep(BigDecimal.ZERO, bl, bs, 10, 2, nfsc, balance, 3, board, freeAccumulated, 2,
                    nfsc == GameRules.FREE_SPIN_COUNT ? 1 : 0, win, wins));
        }
        return new RoundResult(roundKey, RoundMode.FREE_SPINS, bl, bs, start, steps);
    }

    private static boolean isScatterTrigger(List<String> board) {
        validateBoard(board, false);
        for (int reel = 0; reel < 3; reel++) {
            boolean visible = false;
            for (int row : GameRules.VISIBLE_ROWS) {
                if ("SC".equals(board.get(reel * 5 + row))) {
                    visible = true;
                    break;
                }
            }
            if (!visible) return false;
        }
        return true;
    }

    private static void validateBoard(List<String> board, boolean freeMode) {
        if (board == null || board.size() != 15) throw new IllegalArgumentException("rskl 必须恰好包含 15 项");
        int wildTotal = 0;
        int scTotal = 0;
        for (int reel = 0; reel < 3; reel++) {
            int blanks = 0, scAny = 0, scVisible = 0, wilds = 0;
            for (int pos = 0; pos < 5; pos++) {
                String symbol = board.get(reel * 5 + pos);
                if (!GameRules.ALL_SYMBOLS.contains(symbol)) throw new IllegalArgumentException("未知符号: " + symbol);
                if (freeMode && "SC".equals(symbol)) throw new IllegalArgumentException("免费 Spin 中不得出现 SC");
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
                    boolean prevBlank = "BLANK".equals(board.get(reel * 5 + pos - 1));
                    if (prevBlank == "BLANK".equals(symbol)) {
                        throw new IllegalArgumentException("每卷轴 BLANK 与符号必须交替");
                    }
                }
            }
            if (blanks < 2 || blanks > 3) throw new IllegalArgumentException("每卷轴必须含 2 或 3 个 BLANK");
            if (scAny > GameRules.SC_PER_REEL_MAX || scVisible > GameRules.SC_VISIBLE_PER_REEL_MAX) {
                throw new IllegalArgumentException("SC 超过抓包上限");
            }
            if (wilds > GameRules.WILD_PER_REEL_MAX) throw new IllegalArgumentException("WILD 超过抓包上限");
        }
        if (!freeMode && scTotal > GameRules.SC_BOARD_MAX) throw new IllegalArgumentException("整盘 SC 超过抓包上限");
        if (wildTotal > GameRules.WILD_BOARD_MAX) throw new IllegalArgumentException("整盘 WILD 超过抓包上限");
    }

    private static Map<String, String> generatedLineWins(List<String> board) {
        Map<String, String> wins = new LinkedHashMap<>();
        for (int line = 0; line < GameRules.PAYLINES.length; line++) {
            List<String> symbols = List.of(
                    board.get(0 * 5 + GameRules.PAYLINES[line][0]),
                    board.get(1 * 5 + GameRules.PAYLINES[line][1]),
                    board.get(2 * 5 + GameRules.PAYLINES[line][2]));
            String reward = generatedBestReward(symbols);
            if (reward != null) wins.put(String.valueOf(line), reward);
        }
        return wins;
    }

    private static String generatedBestReward(List<String> symbols) {
        if (symbols.stream().anyMatch(s -> "BLANK".equals(s) || "SC".equals(s))) return null;
        String best = null;
        int bestPay = -1;
        for (String candidate : List.of("WILD", "H1", "H2", "H3", "H4", "H5", "H6", "MIX")) {
            if (generatedMatches(candidate, symbols) && GameRules.PAYTABLE.get(candidate) > bestPay) {
                best = candidate;
                bestPay = GameRules.PAYTABLE.get(candidate);
            }
        }
        return best;
    }

    private static boolean generatedMatches(String candidate, List<String> symbols) {
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
            Map<String, Integer> color = Map.of("H1", 0, "H4", 0, "H2", 1, "H5", 1, "H3", 2, "H6", 2);
            for (String symbol : symbols) {
                if ("WILD".equals(symbol)) {
                    wilds++;
                    continue;
                }
                Integer c = color.get(symbol);
                if (c == null || seen[c]) return false;
                seen[c] = true;
            }
            int missing = 0;
            for (boolean value : seen) if (!value) missing++;
            return missing == wilds;
        }
        return false;
    }

    private static BigDecimal generatedPayout(Map<String, String> wmkl, int bl, BigDecimal bs, int rpx) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal bet = GameRules.betAmount(bl, bs);
        for (String reward : wmkl.values()) {
            Integer multiplier = GameRules.PAYTABLE.get(reward);
            if (multiplier == null) throw new IllegalArgumentException("奖表中不存在: " + reward);
            total = total.add(bet.multiply(BigDecimal.valueOf(rpx)).multiply(BigDecimal.valueOf(multiplier)));
        }
        return total.stripTrailingZeros();
    }

    public interface IdentitySource {
        String nextKey();
    }

    public static IdentitySource deterministicIdentitySource(long seed) {
        return new IdentitySource() {
            private long cursor = seed;
            @Override public String nextKey() {
                cursor = cursor * 6364136223846793005L + 1;
                return Long.toUnsignedString(cursor, 16) + Long.toUnsignedString(cursor ^ seed, 16);
            }
        };
    }

    private static final class SecureIdentitySource implements IdentitySource {
        private final SecureRandom random = new SecureRandom();
        @Override public String nextKey() {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            long high = 0, low = 0;
            for (int i = 0; i < 8; i++) high = (high << 8) | (bytes[i] & 0xffL);
            for (int i = 8; i < 16; i++) low = (low << 8) | (bytes[i] & 0xffL);
            return new UUID(high, low).toString().replace("-", "");
        }
    }
}
