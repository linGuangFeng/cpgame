package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 单个符号的一条 ways 中奖，字段与前端 win_arr 一一对应。 */
public final class FreedomDayWin {
    private final int symbol;
    private final int reelCount;
    private final int ways;
    private final BigDecimal payOdd;
    private final int multiplier;
    private final BigDecimal winMoney;
    private final List<Integer> mainPositions;
    private final List<List<Integer>> mainPositionGroups;
    private final List<Integer> topPositions;

    public FreedomDayWin(int symbol, int reelCount, int ways, BigDecimal payOdd, int multiplier,
                         BigDecimal winMoney, List<List<Integer>> mainPositionGroups, List<Integer> topPositions) {
        this.symbol = symbol; this.reelCount = reelCount; this.ways = ways; this.payOdd = payOdd;
        this.multiplier = multiplier; this.winMoney = winMoney;
        List<List<Integer>> copied = new ArrayList<>();
        for (List<Integer> group : mainPositionGroups) {
            copied.add(group == null ? new ArrayList<>() : new ArrayList<>(group));
        }
        this.mainPositionGroups = copied;
        this.mainPositions = this.mainPositionGroups.stream().flatMap(List::stream).distinct().toList();
        this.topPositions = topPositions == null ? new ArrayList<>() : new ArrayList<>(topPositions);
    }
    public int getSymbol() { return symbol; }
    public int getReelCount() { return reelCount; }
    public int getWays() { return ways; }
    public BigDecimal getPayOdd() { return payOdd; }
    public int getMultiplier() { return multiplier; }
    public BigDecimal getWinMoney() { return winMoney; }
    public List<Integer> getMainPositions() { return mainPositions; }
    public List<List<Integer>> getMainPositionGroups() { return mainPositionGroups; }
    public List<Integer> getTopPositions() { return topPositions; }
}
