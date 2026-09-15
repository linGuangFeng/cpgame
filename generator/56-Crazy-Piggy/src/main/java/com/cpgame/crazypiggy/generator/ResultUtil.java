package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.ResultAnalysis;
import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import com.cpgame.crazypiggy.generator.model.WheelDelivery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立结果反推 Util。路线在此独立列出，不读取 RoundFactory 的计算结果。
 */
public final class ResultUtil {
    private static final int[][] ORACLE_LINES = {
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
    };

    private ResultUtil() {}

    public static ResultAnalysis analyze(RoundResult round) {
        return analyze(new RoundFacts(round.roundKey(), round.createdAtEpochSecond(), round.betSize(),
                round.betLevel(), round.symbols(), round.wheelPositions(), round.wheelMultipliers()));
    }

    public static ResultAnalysis analyze(RoundFacts facts) {
        Map<Integer, String> wins = evaluateLines(facts.symbols());
        BigDecimal betAmount = facts.betSize().multiply(BigDecimal.valueOf(facts.betLevel()));
        BigDecimal base = award(wins, facts.betSize(), facts.betLevel());
        boolean wheel = boosterTrigger(facts.symbols());
        List<WheelDelivery> deliveries = List.of();
        BigDecimal wheelAward = BigDecimal.ZERO;
        RoundMode mode;
        int gameMode;
        int smallGameType;

        if (wheel) {
            validateWheelFacts(facts);
            int multiplierSum = facts.wheelMultipliers().stream().mapToInt(Integer::intValue).sum();
            wheelAward = base.multiply(BigDecimal.valueOf(multiplierSum));
            List<WheelDelivery> inferred = new ArrayList<>(facts.wheelPositions().size());
            for (int i = 0; i < facts.wheelPositions().size(); i++) {
                boolean terminal = i == facts.wheelPositions().size() - 1;
                inferred.add(new WheelDelivery(i, facts.wheelPositions().get(i),
                        terminal ? null : facts.wheelMultipliers().get(i), terminal));
            }
            deliveries = List.copyOf(inferred);
            mode = RoundMode.BOOSTER_WHEEL;
            gameMode = 1;
            smallGameType = 2;
        } else {
            if (!facts.wheelPositions().isEmpty() || !facts.wheelMultipliers().isEmpty()) {
                throw new IllegalArgumentException("普通牌面不得携带轮盘事实");
            }
            mode = base.signum() == 0 ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
            gameMode = 0;
            smallGameType = 0;
        }
        return new ResultAnalysis(mode, betAmount, wins, base, wheelAward, base.add(wheelAward),
                gameMode, smallGameType, deliveries);
    }

    public static Map<Integer, String> evaluateLines(List<String> symbols) {
        if (symbols == null || symbols.size() != 9) {
            throw new IllegalArgumentException("rskl 必须恰好包含 9 个按列展开的符号");
        }
        for (String symbol : symbols) {
            if (!GameRules.PAYTABLE.containsKey(symbol)) {
                throw new IllegalArgumentException("当前游戏不允许符号: " + symbol);
            }
        }
        Map<Integer, String> wins = new LinkedHashMap<>();
        for (int line = 0; line < ORACLE_LINES.length; line++) {
            int[] route = ORACLE_LINES[line];
            String symbol = symbols.get(route[0]);
            if (symbol.equals(symbols.get(route[1])) && symbol.equals(symbols.get(route[2]))) {
                wins.put(line, symbol);
            }
        }
        return wins;
    }

    public static BigDecimal lineAward(String symbol, BigDecimal betSize, int betLevel) {
        Integer pay = GameRules.PAYTABLE.get(symbol);
        if (pay == null) throw new IllegalArgumentException("未知符号: " + symbol);
        return betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(pay));
    }

    public static BigDecimal award(Map<Integer, String> wins, BigDecimal betSize, int betLevel) {
        return wins.values().stream()
                .map(symbol -> lineAward(symbol, betSize, betLevel))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 兼容既有调用；实际逐字段核验由独立 Verifier 完成。 */
    public static void verify(RoundResult round) {
        new RoundVerifier().verify(round);
    }

    private static boolean boosterTrigger(List<String> symbols) {
        String first = symbols.get(0);
        return !first.equals("HOT") && !first.equals("SEV") && symbols.stream().allMatch(first::equals);
    }

    private static void validateWheelFacts(RoundFacts facts) {
        int positions = facts.wheelPositions().size();
        if (positions < 1 || positions > 7 || positions != facts.wheelMultipliers().size() + 1) {
            throw new IllegalArgumentException("fwtl 必须为 1..7 项且长度等于 fwxl+1");
        }
        if (facts.wheelPositions().stream().anyMatch(v -> v < 0 || v > 7)) {
            throw new IllegalArgumentException("轮盘落点必须在 0..7");
        }
        if (facts.wheelMultipliers().stream().anyMatch(v -> v != 1 && v != 2)) {
            throw new IllegalArgumentException("仅允许已捕获且获准实现的 fwxl=1/2");
        }
    }
}
