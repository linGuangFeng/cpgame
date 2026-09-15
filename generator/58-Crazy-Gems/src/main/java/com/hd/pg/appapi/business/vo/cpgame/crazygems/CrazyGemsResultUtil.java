package com.hd.pg.appapi.business.vo.cpgame.crazygems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure payline evaluator. Same board always yields the same wmkl / pay / deci multiplier.
 * No Redis, no RNG, no second rule set.
 */
public final class CrazyGemsResultUtil {
    public static final Map<String, BigDecimal> PAY = Map.of(
            "WILD", new BigDecimal("5"),
            "H1", new BigDecimal("4"),
            "H2", new BigDecimal("3"),
            "H3", new BigDecimal("2.4"),
            "H4", new BigDecimal("2"),
            "H5", new BigDecimal("1.6"),
            "H6", new BigDecimal("1"),
            "H7", new BigDecimal("0.4")
    );
    private static final BigDecimal TEN = BigDecimal.TEN;

    private CrazyGemsResultUtil() { }

    public static CrazyGemsEvaluation evaluate(CrazyGemsBoard board) {
        if (board == null) throw new IllegalArgumentException("board is required");
        Map<String, String> wmkl = new LinkedHashMap<>();
        BigDecimal paySum = BigDecimal.ZERO;
        for (int line = 0; line < CrazyGemsBoard.PAYLINES.length; line++) {
            String paying = lineSymbol(board, CrazyGemsBoard.PAYLINES[line]);
            if (paying == null) continue;
            wmkl.put(Integer.toString(line), paying);
            paySum = paySum.add(PAY.get(paying));
        }
        BigDecimal stakeMult = paySum.multiply(BigDecimal.valueOf(board.rpx()));
        int multiplierDeci;
        try {
            multiplierDeci = stakeMult.multiply(TEN).stripTrailingZeros().intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("non-integer deci multiplier for " + board + " pay=" + paySum, ex);
        }
        return new CrazyGemsEvaluation(wmkl, paySum, board.rpx(), multiplierDeci);
    }

    /**
     * Unify a 3-cell line after WILD substitution. All-wild pays WILD.
     * Two or more distinct natural symbols: no win.
     */
    static String lineSymbol(CrazyGemsBoard board, int[] rows) {
        String natural = null;
        for (int reel = 0; reel < CrazyGemsBoard.REELS; reel++) {
            String symbol = board.symbol(reel, rows[reel]);
            if (CrazyGemsBoard.WILD.equals(symbol)) continue;
            if (natural == null) natural = symbol;
            else if (!natural.equals(symbol)) return null;
        }
        return natural == null ? CrazyGemsBoard.WILD : natural;
    }

    public static BigDecimal winAmount(CrazyGemsEvaluation evaluation, BigDecimal betSize, int betLevel) {
        if (betSize == null || betSize.signum() <= 0 || betLevel < 1) {
            throw new IllegalArgumentException("illegal bet");
        }
        return evaluation.winAmount(betSize, betLevel).setScale(2, RoundingMode.HALF_UP);
    }
}
