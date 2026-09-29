package com.cpgame.luckynightmarket;

import java.util.Properties;

public final class SymbolWeightScheduleTest {
    public static void main(String[] args) {
        double[] sampleWeights = {2, 5, 7, 11, 13, 17, 19};
        Properties properties = new Properties();
        properties.setProperty("symbol.weight.WILD", "2");
        properties.setProperty("symbol.weight.H1", "5");
        SymbolWeightSchedule schedule = new SymbolWeightSchedule(properties, sampleWeights);

        schedule.useBatch(0);
        require(schedule.boostedSymbol() == -1, "第一批必须使用全部基础权重");
        require(schedule.weight(0) == 2.0d && schedule.weight(1) == 5.0d && schedule.weight(2) == 7.0d,
                "第一批必须直接使用配置中的逐牌基础权重");

        schedule.useBatch(1);
        require(schedule.boostedSymbol() == 0, "第二批必须只强化 WILD");
        require(schedule.weight(0) == 6.0d && schedule.weight(1) == 5.0d,
                "默认强化必须是该牌基础权重乘 3");

        schedule.useBatch(2);
        require(schedule.boostedSymbol() == 1, "第三批必须只强化 H1");
        require(schedule.weight(0) == 2.0d && schedule.weight(1) == 15.0d,
                "强化牌必须保留自己的基础权重");

        schedule.useBatch(7);
        require(schedule.boostedSymbol() == 6, "第八批必须强化 L4");
        schedule.useBatch(8);
        require(schedule.boostedSymbol() == -1, "全牌轮询后必须回到全部基础权重");
        schedule.useBatch(9);
        require(schedule.boostedSymbol() == 0, "新一轮必须再次从 WILD 开始");

        properties.setProperty("symbol.weight.WILD_boost_mul", "4.5");
        SymbolWeightSchedule custom = new SymbolWeightSchedule(properties, sampleWeights);
        custom.useBatch(1);
        require(custom.weight(0) == 9.0d, "每张牌的自定义强化倍数必须乘在该牌基础权重上");

        properties.setProperty("symbol.weight.H2", "14");
        SymbolWeightSchedule adjustedBase = new SymbolWeightSchedule(properties, sampleWeights);
        adjustedBase.useBatch(0);
        require(adjustedBase.weight(2) == 14.0d, "配置基础权重必须直接用于牌位随机");

        reject("0", "symbol.weight.L4", sampleWeights);
        reject("NaN", "symbol.weight.L4_boost_mul", sampleWeights);
        System.out.println("SymbolWeightScheduleTest PASS");
    }

    private static void reject(String value, String key, double[] sampleWeights) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        try { new SymbolWeightSchedule(properties, sampleWeights); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("非法配置未被拒绝: " + key);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
