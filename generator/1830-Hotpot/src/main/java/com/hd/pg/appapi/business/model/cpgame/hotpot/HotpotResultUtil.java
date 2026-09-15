package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent, deterministic board evaluator for Hotpot 1830.
 * Same board always yields the same wins, odds and Scatter/multiplier counts.
 * Does not read Redis, fixtures, or generate random numbers.
 */
public final class HotpotResultUtil {
    public static final int SCATTER = 11;
    public static final int MIN_PAY_SYMBOL = 1;
    public static final int MAX_PAY_SYMBOL = 10;
    public static final int MIN_MULTIPLIER_ID = 12;
    public static final int MAX_MULTIPLIER_ID = 23;
    public static final int MIN_MATCH = 8;
    public static final int PAID_SCATTER_TRIGGER = 3;
    public static final int PAID_FREE_BASE = 10;
    public static final int FREE_SCATTER_RETRIGGER = 2;
    public static final int FREE_RETRIGGER_BASE = 5;
    public static final int EXTRA_PER_SCATTER = 2;
    /**
     * Help 只写 3+ 触发、每多 1 个 +2，没有数字上限。
     * 1392 局 REAL_PROVIDER 完整局：同列从未出现 2 个 Scatter；付费页最多 4 个；免费起始页最多 3 个。
     * 加权加速可以，但不能生成抓包从未支持过的个数。
     */
    public static final int MAX_SCATTER_PER_COLUMN = 1;
    public static final int MAX_SCATTER_PAID_PAGE = 4;
    public static final int MAX_SCATTER_FREE_START_PAGE = 3;

    /** MultipleTypeList: symbolId 12..23 → x2..x50. */
    public static final int[] MULTIPLIER_VALUES = {2, 3, 4, 5, 7, 10, 15, 20, 25, 30, 40, 50};

    private static final int[][] PAY_BY_COUNT = new int[11][37];

    static {
        fillPay(1, 40, 60, 80, 100, 150, 200, 200, 300);
        fillPay(2, 30, 50, 60, 80, 100, 150, 150, 250);
        fillPay(3, 25, 40, 50, 60, 80, 100, 100, 200);
        fillPay(4, 20, 30, 40, 50, 60, 80, 80, 150);
        fillPay(5, 20, 25, 30, 40, 50, 60, 60, 100);
        fillPay(6, 10, 15, 20, 30, 40, 50, 50, 80);
        fillPay(7, 9, 10, 15, 20, 30, 40, 40, 60);
        fillPay(8, 8, 9, 10, 15, 20, 30, 30, 50);
        fillPay(9, 6, 8, 9, 10, 15, 25, 25, 40);
        fillPay(10, 5, 6, 8, 9, 10, 20, 20, 30);
    }

    private HotpotResultUtil() { }

    private static void fillPay(int symbol, int n8, int n9, int n10, int n11, int n12, int n13, int n14, int rest) {
        PAY_BY_COUNT[symbol][8] = n8;
        PAY_BY_COUNT[symbol][9] = n9;
        PAY_BY_COUNT[symbol][10] = n10;
        PAY_BY_COUNT[symbol][11] = n11;
        PAY_BY_COUNT[symbol][12] = n12;
        PAY_BY_COUNT[symbol][13] = n13;
        PAY_BY_COUNT[symbol][14] = n14;
        for (int count = 15; count <= 36; count++) PAY_BY_COUNT[symbol][count] = rest;
    }

    public static HotpotEvaluation evaluate(HotpotBoard board) {
        if (board == null) throw new IllegalArgumentException("board is required");
        int[] prop = board.getProp();
        int[] counts = new int[11];
        List<List<Integer>> positions = new ArrayList<>(11);
        for (int i = 0; i <= 10; i++) positions.add(new ArrayList<>());
        int scatter = 0;
        int multiplierSum = 0;
        for (int i = 0; i < prop.length; i++) {
            int symbol = prop[i];
            if (symbol >= MIN_PAY_SYMBOL && symbol <= MAX_PAY_SYMBOL) {
                counts[symbol]++;
                positions.get(symbol).add(i);
                continue;
            }
            if (symbol == SCATTER) {
                scatter++;
                continue;
            }
            if (symbol >= MIN_MULTIPLIER_ID && symbol <= MAX_MULTIPLIER_ID) {
                multiplierSum += MULTIPLIER_VALUES[symbol - MIN_MULTIPLIER_ID];
                continue;
            }
            throw new IllegalArgumentException("unclassified symbol: " + symbol);
        }
        List<HotpotWin> wins = new ArrayList<>();
        int oddSum = 0;
        for (int symbol = MIN_PAY_SYMBOL; symbol <= MAX_PAY_SYMBOL; symbol++) {
            int count = counts[symbol];
            if (count < MIN_MATCH) continue;
            int odd = PAY_BY_COUNT[symbol][count];
            if (odd <= 0) throw new IllegalStateException("missing paytable odd for symbol " + symbol + " count " + count);
            wins.add(new HotpotWin(symbol, count, odd, positions.get(symbol)));
            oddSum += odd;
        }
        return new HotpotEvaluation(wins, oddSum, scatter, multiplierSum, classifyPage(wins.isEmpty()));
    }

    public static int awardedFreeSpins(int scatterCount, HotpotSpinMode mode) {
        if (scatterCount < 0) throw new IllegalArgumentException("scatterCount < 0");
        if (mode == null) throw new IllegalArgumentException("spin mode is required");
        switch (mode) {
            case PAID -> {
                if (scatterCount >= PAID_SCATTER_TRIGGER) {
                    return PAID_FREE_BASE + EXTRA_PER_SCATTER * (scatterCount - PAID_SCATTER_TRIGGER);
                }
                return 0;
            }
            case FREE -> {
                if (scatterCount >= FREE_SCATTER_RETRIGGER) {
                    return FREE_RETRIGGER_BASE + EXTRA_PER_SCATTER * (scatterCount - FREE_SCATTER_RETRIGGER);
                }
                return 0;
            }
        }
        throw new IllegalStateException("unhandled spin mode: " + mode);
    }

    public static int spinIntegerMultiplier(List<HotpotBoard> pages) {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("spin has no pages");
        int oddSum = 0;
        for (int i = 0; i < pages.size(); i++) {
            HotpotEvaluation evaluation = evaluate(pages.get(i));
            boolean last = i == pages.size() - 1;
            switch (evaluation.getPageKind()) {
                case TERMINAL_NO_WIN -> {
                    if (!last) throw new IllegalArgumentException("page after terminal no-win");
                }
                case WIN -> {
                    if (last) throw new IllegalArgumentException("winning spin has no terminal no-win page");
                    oddSum += evaluation.getOddSum();
                }
            }
        }
        int factor = 1;
        if (pages.size() >= 2) {
            int multiplierSum = evaluate(pages.get(pages.size() - 1)).getMultiplierSum();
            if (multiplierSum > 0) factor = multiplierSum;
        }
        return Math.multiplyExact(oddSum, factor);
    }

    public static int multiplierValue(int symbolId) {
        if (symbolId < MIN_MULTIPLIER_ID || symbolId > MAX_MULTIPLIER_ID) {
            throw new IllegalArgumentException("not a multiplier id: " + symbolId);
        }
        return MULTIPLIER_VALUES[symbolId - MIN_MULTIPLIER_ID];
    }

    public static int payOdd(int symbol, int count) {
        if (symbol < MIN_PAY_SYMBOL || symbol > MAX_PAY_SYMBOL || count < MIN_MATCH || count > 36) {
            throw new IllegalArgumentException("pay lookup out of range");
        }
        return PAY_BY_COUNT[symbol][count];
    }

    public static boolean[] eliminatedMask(HotpotBoard board, HotpotEvaluation evaluation) {
        boolean[] removed = new boolean[HotpotBoard.SIZE];
        int[] prop = board.getProp();
        for (HotpotWin win : evaluation.getWins()) {
            for (int i = 0; i < prop.length; i++) {
                if (prop[i] == win.getSymbol()) removed[i] = true;
            }
        }
        return removed;
    }

    private static HotpotPageKind classifyPage(boolean noWin) {
        if (noWin) return HotpotPageKind.TERMINAL_NO_WIN;
        if (!noWin) return HotpotPageKind.WIN;
        throw new IllegalStateException("page kind partition failed");
    }
}
