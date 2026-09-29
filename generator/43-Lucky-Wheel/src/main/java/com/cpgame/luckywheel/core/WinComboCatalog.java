package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 中奖形不含空白。15 只存 {@code 1,5}；出牌时把 H0 随机塞进剩余格子，顺序不变。
 * {@code 空白 1 5} / {@code 1 空白 5} / {@code 1 5 空白} 都是同一条形。
 */
public final class WinComboCatalog {
    private static final String[] DIGITS = {"H1", "H3", "H4", "H5"};
    private static final List<List<String>> PAT2 = enumerate(2);
    private static final List<List<String>> PAT3 = enumerate(3);

    private WinComboCatalog() { }

    public static List<List<String>> patterns(int maxLen) {
        if (maxLen == 2) return PAT2;
        if (maxLen == 3) return PAT3;
        throw new IllegalArgumentException("maxLen");
    }

    /** 把中奖形按原顺序放进 {@code cells} 格，其余格填 H0。 */
    public static List<String> place(List<String> pattern, int cells, RandomGenerator random) {
        int k = pattern.size();
        if (k == 0 || k > cells) {
            throw new IllegalArgumentException("pattern length " + k + " cannot fill " + cells + " cells");
        }
        if (k == cells) return List.copyOf(pattern);
        int[] slots = choose(cells, k, random);
        String[] board = new String[cells];
        Arrays.fill(board, "H0");
        for (int i = 0; i < k; i++) board[slots[i]] = pattern.get(i);
        return List.of(board);
    }

    private static int[] choose(int cells, int k, RandomGenerator random) {
        List<int[]> options = new ArrayList<>();
        int[] cur = new int[k];
        walkSlots(0, 0, cells, k, cur, options);
        return options.get(random.nextInt(options.size()));
    }

    private static void walkSlots(int start, int filled, int cells, int k, int[] cur, List<int[]> out) {
        if (filled == k) {
            out.add(cur.clone());
            return;
        }
        for (int i = start; i <= cells - (k - filled); i++) {
            cur[filled] = i;
            walkSlots(i + 1, filled + 1, cells, k, cur, out);
        }
    }

    private static List<List<String>> enumerate(int maxLen) {
        List<List<String>> out = new ArrayList<>();
        String[] cur = new String[maxLen];
        walk(cur, 0, maxLen, out);
        if (out.isEmpty()) throw new IllegalStateException("empty win patterns");
        return List.copyOf(out);
    }

    private static void walk(String[] cur, int n, int maxLen, List<List<String>> out) {
        if (n > 0) {
            List<String> pattern = List.of(Arrays.copyOf(cur, n));
            if (ResultUtil.independentScore(pattern).compareTo(BigDecimal.ZERO) > 0) out.add(pattern);
        }
        if (n == maxLen) return;
        for (String symbol : DIGITS) {
            cur[n] = symbol;
            walk(cur, n + 1, maxLen, out);
        }
    }
}
