package com.cpgame.luckywheel.core;

import java.util.List;

/** 最小完整局事实；betProfile 只记录决定盘面形状的门槛（1 或 5）。 */
public record RoundFacts(int betProfile, int mode, List<String> baseSymbols, int multiplier,
                         List<String> respinSymbols, int luckyWheelAward) {
    public RoundFacts {
        baseSymbols = List.copyOf(baseSymbols);
        respinSymbols = List.copyOf(respinSymbols);
        if (betProfile != 1 && betProfile != 5) throw new IllegalArgumentException("未知下注档案");
        int expectedSize = betProfile == 1 ? 2 : 3;
        if (baseSymbols.size() != expectedSize) throw new IllegalArgumentException("基础符号尺寸与下注档案不一致");
        switch (mode) {
            case 0 -> {
                if (multiplier != 1 || !respinSymbols.isEmpty() || luckyWheelAward != 0) throw new IllegalArgumentException("md=0 最小事实不合法");
            }
            case 1 -> {
                if ((multiplier != 2 && multiplier != 5) || !respinSymbols.isEmpty() || luckyWheelAward != 0) {
                    throw new IllegalArgumentException("md=1 最小事实不合法");
                }
            }
            case 2 -> {
                if (multiplier != 1 || respinSymbols.size() != expectedSize || luckyWheelAward != 0) throw new IllegalArgumentException("md=2 最小事实不合法");
            }
            case 3 -> {
                if (betProfile != 5 || multiplier != 1 || !respinSymbols.isEmpty()
                        || (luckyWheelAward != 50 && luckyWheelAward != 150)) {
                    throw new IllegalArgumentException("md=3 只允许 bet>=5 且使用已捕获的50/150标量奖励");
                }
            }
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + mode);
        }
    }

    public static RoundFacts ordinary(List<String> baseSymbols) {
        return ordinary(1, baseSymbols);
    }
    public static RoundFacts ordinary(int betProfile, List<String> baseSymbols) {
        return new RoundFacts(betProfile, 0, baseSymbols, 1, List.of(), 0);
    }

    public static RoundFacts multiplier(List<String> baseSymbols, int multiplier) {
        return multiplier(1, baseSymbols, multiplier);
    }
    public static RoundFacts multiplier(int betProfile, List<String> baseSymbols, int multiplier) {
        return new RoundFacts(betProfile, 1, baseSymbols, multiplier, List.of(), 0);
    }

    public static RoundFacts respin(List<String> baseSymbols, List<String> respinSymbols) {
        return respin(1, baseSymbols, respinSymbols);
    }
    public static RoundFacts respin(int betProfile, List<String> baseSymbols, List<String> respinSymbols) {
        return new RoundFacts(betProfile, 2, baseSymbols, 1, respinSymbols, 0);
    }

    public static RoundFacts luckyWheel(List<String> baseSymbols, int luckyWheelAward) {
        return new RoundFacts(5, 3, baseSymbols, 1, List.of(), luckyWheelAward);
    }
}
