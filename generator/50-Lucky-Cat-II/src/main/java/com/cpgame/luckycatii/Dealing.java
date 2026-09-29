package com.cpgame.luckycatii;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Weighted cell picks, reel-shape fills, and board helpers. */
final class Dealing {
    private Dealing() {}

    static String pick(Map<String, Integer> weights, RandomGenerator random, boolean includeWild) {
        int total = 0;
        for (String symbol : GameRules.SYMBOLS) {
            if (!includeWild && "WILD".equals(symbol)) continue;
            total += weights.get(symbol);
        }
        int ticket = random.nextInt(total);
        for (String symbol : GameRules.SYMBOLS) {
            if (!includeWild && "WILD".equals(symbol)) continue;
            ticket -= weights.get(symbol);
            if (ticket < 0) return symbol;
        }
        return includeWild ? "WILD" : "S6";
    }

    static String pickPay(Map<String, Integer> weights, RandomGenerator random) {
        return pick(weights, random, false);
    }

    static String otherPay(String avoid, Map<String, Integer> weights, RandomGenerator random) {
        return other(Set.of(avoid), weights, random, false);
    }

    static String other(Set<String> avoid, Map<String, Integer> weights, RandomGenerator random, boolean includeWild) {
        for (int n = 0; n < 12; n++) {
            String symbol = pick(weights, random, includeWild);
            if (!avoid.contains(symbol)) return symbol;
        }
        for (String symbol : GameRules.SYMBOLS) {
            if (!includeWild && "WILD".equals(symbol)) continue;
            if (!avoid.contains(symbol)) return symbol;
        }
        return includeWild ? "WILD" : "S6";
    }

    static String[] board(Map<String, Integer> weights, ReelPatterns patterns, RandomGenerator random) {
        String[] cells = new String[9];
        for (int reel = 0; reel < 3; reel++) {
            fillReel(cells, reel, patterns.pick(random), weights, random);
        }
        return cells;
    }

    static void fillReel(String[] cells, int reel, ReelPatterns.Shape pattern,
                         Map<String, Integer> weights, RandomGenerator random) {
        String a = pick(weights, random, true);
        String b = other(Set.of(a), weights, random, true);
        String c = other(Set.of(a, b), weights, random, true);
        String[] col = switch (pattern) {
            case AAA -> new String[]{a, a, a};
            case AAB -> new String[]{a, a, b};
            case BAA -> new String[]{b, a, a};
            case ABC -> new String[]{a, b, c};
        };
        writeReel(cells, reel, col);
    }

    static void completeReel(String[] cells, int reel, boolean[] locked, String[] lockedSymbol,
                             ReelPatterns.Shape pattern, Map<String, Integer> weights, RandomGenerator random) {
        int locks = 0;
        int free = -1;
        for (int row = 0; row < 3; row++) {
            if (locked[row]) locks++;
            else free = row;
        }
        if (locks == 0) {
            fillReel(cells, reel, pattern, weights, random);
            return;
        }
        if (locks == 3) {
            writeReel(cells, reel, lockedSymbol);
            return;
        }
        if (locks == 1) {
            int lockedRow = 0;
            while (!locked[lockedRow]) lockedRow++;
            writeReel(cells, reel, columnForOneLock(lockedRow, lockedSymbol[lockedRow], pattern, weights, random));
            return;
        }
        String x = null, y = null;
        for (int row = 0; row < 3; row++) {
            if (!locked[row]) continue;
            if (x == null) x = lockedSymbol[row];
            else y = lockedSymbol[row];
        }
        String[] col = new String[3];
        for (int row = 0; row < 3; row++) if (locked[row]) col[row] = lockedSymbol[row];
        if (x.equals(y)) {
            col[free] = pattern == ReelPatterns.Shape.AAA ? x : other(Set.of(x), weights, random, true);
        } else {
            col[free] = other(Set.of(x, y), weights, random, true);
        }
        writeReel(cells, reel, col);
    }

    static void fillWheelReel(String[] cells, int reel, ReelPatterns.Shape pattern, String symbol,
                              RandomGenerator random) {
        ReelPatterns.Shape shape = pattern == ReelPatterns.Shape.ABC ? ReelPatterns.Shape.AAA : pattern;
        boolean wildPair = random.nextBoolean();
        String[] col = switch (shape) {
            case AAA -> random.nextInt(8) == 0
                    ? new String[]{"WILD", "WILD", "WILD"}
                    : new String[]{symbol, symbol, symbol};
            case AAB -> wildPair
                    ? new String[]{"WILD", "WILD", symbol}
                    : new String[]{symbol, symbol, "WILD"};
            case BAA -> wildPair
                    ? new String[]{symbol, "WILD", "WILD"}
                    : new String[]{"WILD", symbol, symbol};
            case ABC -> new String[]{symbol, symbol, symbol};
        };
        writeReel(cells, reel, col);
    }

    private static String[] columnForOneLock(int lockedRow, String symbol, ReelPatterns.Shape pattern,
                                             Map<String, Integer> weights, RandomGenerator random) {
        String b = other(Set.of(symbol), weights, random, true);
        String c = other(Set.of(symbol, b), weights, random, true);
        return switch (pattern) {
            case AAA -> new String[]{symbol, symbol, symbol};
            case AAB -> lockedRow == 2
                    ? new String[]{b, b, symbol}
                    : new String[]{symbol, symbol, b};
            case BAA -> lockedRow == 0
                    ? new String[]{symbol, b, b}
                    : new String[]{b, symbol, symbol};
            case ABC -> {
                String[] col = new String[]{b, b, b};
                col[lockedRow] = symbol;
                col[(lockedRow + 1) % 3] = b;
                col[(lockedRow + 2) % 3] = c;
                yield col;
            }
        };
    }

    private static void writeReel(String[] cells, int reel, String[] col) {
        for (int row = 0; row < 3; row++) cells[GameRules.cellIndex(reel, row)] = col[row];
    }

    static void fillStack(String[] cells, int reel, int prefixWilds, String target) {
        int wilds = Math.max(0, Math.min(3, prefixWilds));
        for (int row = 0; row < 3; row++) {
            cells[GameRules.cellIndex(reel, row)] = row < wilds ? "WILD" : target;
        }
    }

    static void setLine(String[] cells, int line, String symbol) {
        int[] rows = GameRules.PAYLINE_ROWS[line];
        for (int reel = 0; reel < 3; reel++) cells[GameRules.cellIndex(reel, rows[reel])] = symbol;
    }

    static List<String> freeze(String[] cells) {
        return List.of(cells[0], cells[1], cells[2], cells[3], cells[4],
                cells[5], cells[6], cells[7], cells[8]);
    }

    static int lineUnits(List<String> board) {
        int units = 0;
        for (String symbol : ResultUtil.evaluatePaylines(board).values()) units += GameRules.PAYTABLE.get(symbol);
        return units;
    }

    static boolean legal(List<String> board) {
        try {
            ResultUtil.enforceWildCaps(board);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
