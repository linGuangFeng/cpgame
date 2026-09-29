package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立、确定性的牌面反推工具：相同牌面和入参一定得到相同结果，不读取 Redis/Spring，也不生成随机数。
 */
public final class FreedomDayResultUtil {
    public static final int BALL = 1;
    public static final int SCATTER = 12;
    public static final int WILD = 13;
    private static final Map<Integer, int[]> PAY_TABLE = new HashMap<>();

    static {
        PAY_TABLE.put(1,  new int[]{30, 40, 50, 80});
        PAY_TABLE.put(2,  new int[]{20, 25, 30, 50});
        PAY_TABLE.put(3,  new int[]{10, 25, 30, 40});
        PAY_TABLE.put(4,  new int[]{8, 15, 20, 30});
        PAY_TABLE.put(5,  new int[]{6, 10, 12, 15});
        PAY_TABLE.put(6,  new int[]{6, 10, 12, 15});
        PAY_TABLE.put(7,  new int[]{4, 6, 8, 10});
        PAY_TABLE.put(8,  new int[]{4, 6, 8, 10});
        PAY_TABLE.put(9,  new int[]{1, 2, 3, 4});
        PAY_TABLE.put(10, new int[]{1, 2, 3, 4});
        PAY_TABLE.put(11, new int[]{1, 2, 3, 4});
    }

    private FreedomDayResultUtil() { }

    /**
     * @param unitBet 客户端 bet_gold * level（不是 20 ways 后的总下注）
     * @param baseMultiplier 普通局未收集 Ball 时用 1 表示基础倍率，玛丽局从 2 开始
     * @param ballIncrement 客户端中每个 Ball 都显示 x2，普通局和玛丽局均传 2
     */
    public static FreedomDayEvaluation evaluate(FreedomDayBoard board, BigDecimal unitBet,
                                                 int baseMultiplier, int ballIncrement) {
        return evaluate(board, unitBet, baseMultiplier, ballIncrement, countVisibleSymbol(board, BALL));
    }

    /**
     * {@code newBallCount} 只计本页新出现的可见倍率球（叠组算 1，trl 各算 1）。
     * 客户端 isOddActive 会随重力保留，幸存球不再飞一次，也不能再加一次倍率。
     */
    public static FreedomDayEvaluation evaluate(FreedomDayBoard board, BigDecimal unitBet,
                                                 int baseMultiplier, int ballIncrement, int newBallCount) {
        if (board == null || unitBet == null || unitBet.signum() < 0) throw new IllegalArgumentException();
        if (newBallCount < 0) throw new IllegalArgumentException("newBallCount");
        int ballCount = newBallCount;
        List<Integer> winningSymbols = new ArrayList<>();
        List<Integer> winningReelCounts = new ArrayList<>();
        List<Integer> winningWays = new ArrayList<>();
        List<List<List<Integer>>> winningMainPositions = new ArrayList<>();
        List<List<Integer>> winningTopPositions = new ArrayList<>();

        for (int symbol = 1; symbol <= 11; symbol++) {
            int reelCount = 0;
            int ways = 1;
            boolean hasNatural = false;
            List<List<Integer>> main = new ArrayList<>();
            List<Integer> top = new ArrayList<>();
            for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
                int matches = 0;
                for (FreedomDayBoard.Position position : board.positionsOnReel(reel)) {
                    if (position.getSymbol() == symbol || position.getSymbol() == WILD) {
                        matches++;
                        hasNatural |= position.getSymbol() == symbol;
                        if (position.isTop()) {
                            top.add(position.getIndex());
                        } else {
                            // 核心内部保留完整占位格，供重力和框体变形使用；出站 p 再投影为牌块锚点。
                            main.add(position.getIndices());
                        }
                    }
                }
                if (matches == 0) break;
                ways *= matches;
                reelCount++;
            }
            if (reelCount < 3 || !hasNatural) continue;
            winningSymbols.add(symbol);
            winningReelCounts.add(reelCount);
            winningWays.add(ways);
            winningMainPositions.add(main);
            winningTopPositions.add(top);
        }

        // 只给本页新出现的可见球加倍率。幸存球随重力下落，客户端不再播放 x2。
        // 普通局的 1 是无球占位，第一个新球从 0 累加到 x2，而不是 x3。
        int multiplier = Math.max(1, baseMultiplier);
        if (ballCount > 0) {
            int accumulated = baseMultiplier == 1 && ballIncrement == 2 ? 0 : baseMultiplier;
            multiplier = accumulated + ballCount * ballIncrement;
        }

        List<FreedomDayWin> wins = new ArrayList<>();
        BigDecimal totalMultiplier = BigDecimal.ZERO;
        for (int i = 0; i < winningSymbols.size(); i++) {
            int symbol = winningSymbols.get(i);
            int reelCount = winningReelCounts.get(i);
            int ways = winningWays.get(i);
            int pay = PAY_TABLE.get(symbol)[Math.min(reelCount, 6) - 3];
            BigDecimal rawMultiplier = BigDecimal.valueOf((long) pay * ways * multiplier);
            BigDecimal winMoney = unitBet.multiply(rawMultiplier).setScale(2, RoundingMode.HALF_UP);
            wins.add(new FreedomDayWin(symbol, reelCount, ways, BigDecimal.valueOf(pay), multiplier,
                    winMoney, winningMainPositions.get(i), winningTopPositions.get(i)));
            totalMultiplier = totalMultiplier.add(rawMultiplier);
        }

        int scatterCount = countVisibleSymbol(board, SCATTER);
        int freeSpins = scatterCount >= 4 ? 10 + (scatterCount - 4) * 2 : 0;
        return new FreedomDayEvaluation(wins, totalMultiplier,
                unitBet.multiply(totalMultiplier).setScale(2, RoundingMode.HALF_UP),
                scatterCount, freeSpins, multiplier);
    }

    /** 主盘+trl 可见符号数；叠组算 1。Scatter 免费次数与倍率球均按可见符号计。 */
    public static int countVisibleSymbol(FreedomDayBoard board, int symbol) {
        int count = 0;
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            for (FreedomDayBoard.Position position : board.positionsOnReel(reel)) {
                if (position.getSymbol() == symbol) count++;
            }
        }
        return count;
    }

    public static Map<Integer, int[]> copyPayTable() {
        Map<Integer, int[]> copy = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : PAY_TABLE.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
        return copy;
    }
}
