package com.cpgame.crazypiggy.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader-only exhaustive coverage of ordinary 8^9 winning boards.
 * Full-screen H2–H7 boards trigger the wheel and are enumerated separately.
 */
final class OrdinaryBoardCatalog {
    private static final int ALPHABET = 8;
    private static final int BOARD_COUNT = 134_217_728;
    private static final int KEEP_PER_PATTERN = 512;
    private static final OrdinaryBoardCatalog INSTANCE = new OrdinaryBoardCatalog();

    private final int[] ratios;
    private final int[][][] boards;
    final int storedBoards;
    final int patternCount;

    static OrdinaryBoardCatalog get() {
        return INSTANCE;
    }

    private OrdinaryBoardCatalog() {
        int[] pay = new int[ALPHABET];
        for (int i = 0; i < ALPHABET; i++) pay[i] = GameRules.PAYTABLE.get(GameRules.SYMBOLS.get(i));
        @SuppressWarnings("unchecked")
        Map<Long, Ints>[] bins = new Map[5 * 200 + 1];
        int[] d = new int[9];
        int stored = 0;
        int patterns = 0;
        for (int code = 0; code < BOARD_COUNT; code++) {
            int n = code;
            for (int i = 0; i < 9; i++) {
                d[i] = n % ALPHABET;
                n /= ALPHABET;
            }
            boolean same = true;
            for (int i = 1; i < 9; i++) if (d[i] != d[0]) { same = false; break; }
            if (same && d[0] >= 2) continue;
            int payout = 0;
            if (d[0] == d[3] && d[3] == d[6]) payout += pay[d[0]];
            if (d[1] == d[4] && d[4] == d[7]) payout += pay[d[1]];
            if (d[2] == d[5] && d[5] == d[8]) payout += pay[d[2]];
            if (d[0] == d[4] && d[4] == d[8]) payout += pay[d[0]];
            if (d[2] == d[4] && d[4] == d[6]) payout += pay[d[2]];
            if (payout == 0) continue;
            long signature = signature(d);
            Map<Long, Ints> bin = bins[payout];
            if (bin == null) {
                bin = new HashMap<>();
                bins[payout] = bin;
            }
            Ints list = bin.get(signature);
            if (list == null) {
                list = new Ints();
                bin.put(signature, list);
                patterns++;
            }
            if (list.size < KEEP_PER_PATTERN) {
                list.add(code);
                stored++;
            }
        }
        storedBoards = stored;
        patternCount = patterns;
        List<Integer> ratioList = new ArrayList<>();
        List<int[][]> bucketBoards = new ArrayList<>();
        for (int ratio = 1; ratio < bins.length; ratio++) {
            Map<Long, Ints> bin = bins[ratio];
            if (bin == null || bin.isEmpty()) continue;
            ratioList.add(ratio);
            int[][] grouped = new int[bin.size()][];
            int p = 0;
            for (Ints list : bin.values()) grouped[p++] = java.util.Arrays.copyOf(list.values, list.size);
            bucketBoards.add(grouped);
        }
        ratios = ratioList.stream().mapToInt(Integer::intValue).toArray();
        boards = bucketBoards.toArray(int[][][]::new);
    }

    int bucketCount() { return ratios.length; }
    int ratio(int bucket) { return ratios[bucket]; }
    int patternCount(int bucket) { return boards[bucket].length; }
    int size(int bucket, int pattern) { return boards[bucket][pattern].length; }

    List<String> board(int bucket, int pattern, int index) {
        int code = boards[bucket][pattern][index];
        List<String> symbols = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            symbols.add(GameRules.SYMBOLS.get(code % ALPHABET));
            code /= ALPHABET;
        }
        return List.copyOf(symbols);
    }

    private static long signature(int[] d) {
        return pack(d, 0, 3, 6)
                | pack(d, 1, 4, 7) << 4
                | pack(d, 2, 5, 8) << 8
                | pack(d, 0, 4, 8) << 12
                | pack(d, 2, 4, 6) << 16;
    }

    private static long pack(int[] d, int a, int b, int c) {
        return d[a] == d[b] && d[b] == d[c] ? d[a] + 1L : 0L;
    }

    private static final class Ints {
        int[] values = new int[8];
        int size;
        void add(int v) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = v;
        }
    }
}
