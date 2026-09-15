package com.cpgame.curupira.api;

import com.cpgame.curupira.model.CompleteRoundFact.Kind;

/**
 * 20 局付费轮询，前几把就给出选关、整列 Expanding Wild 和大奖。
 * 购买仍走 type=3。
 */
public final class DemoCatalog {
    public record PaidSlot(Kind kind, int minMultiplier, int maxMultiplier) {
        static PaidSlot of(Kind kind) {
            return new PaidSlot(kind, 0, Integer.MAX_VALUE);
        }
        static PaidSlot win(int min, int max) {
            return new PaidSlot(Kind.WIN, min, max);
        }
    }

    private DemoCatalog() { }

    public static PaidSlot slot(int paidIndex) {
        return switch (Math.floorMod(paidIndex, 20)) {
            case 0 -> PaidSlot.of(Kind.LOSS);
            case 1 -> PaidSlot.of(Kind.TRIGGER);
            case 2 -> PaidSlot.of(Kind.EXPANDING_WILD);
            case 3 -> PaidSlot.win(50, Integer.MAX_VALUE);
            case 4 -> PaidSlot.win(30, 49);
            case 5 -> PaidSlot.win(20, 29);
            case 6 -> PaidSlot.win(10, 19);
            case 7 -> PaidSlot.win(1, 9);
            case 8 -> PaidSlot.of(Kind.TRIGGER);
            case 9 -> PaidSlot.of(Kind.EXPANDING_WILD);
            case 10 -> PaidSlot.win(50, Integer.MAX_VALUE);
            case 11 -> PaidSlot.of(Kind.LOSS);
            case 12 -> PaidSlot.of(Kind.TRIGGER);
            case 13 -> PaidSlot.of(Kind.EXPANDING_WILD);
            case 14 -> PaidSlot.win(20, Integer.MAX_VALUE);
            case 15 -> PaidSlot.of(Kind.LOSS);
            case 16 -> PaidSlot.win(1, 9);
            case 17 -> PaidSlot.of(Kind.TRIGGER);
            case 18 -> PaidSlot.of(Kind.EXPANDING_WILD);
            default -> PaidSlot.win(10, Integer.MAX_VALUE);
        };
    }

    public static Kind paidKind(int paidIndex) {
        return slot(paidIndex).kind();
    }
}
