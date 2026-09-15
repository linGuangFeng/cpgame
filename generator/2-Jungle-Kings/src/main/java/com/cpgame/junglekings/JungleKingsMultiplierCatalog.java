package com.cpgame.junglekings;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Line-odd reel triples, plus the ckl odd lists and top/bottom combo map.
 * Requested odds are floored to the mode list; combos and boards are looked up in one shot.
 */
public final class JungleKingsMultiplierCatalog {
    /** Line stake multipliers that a single chessboard can land. */
    public static final int[] PAGE_ODDS = {0, 50, 100};

    private static final String[] SYMBOLS = {"S00011", "S00012", "S00013", "S00015"};
    private static final List<Map<Integer, List<List<String>>>> BY_BOARD;
    private static final List<Integer> SINGLE_LIST;
    private static final List<Integer> BOTH_LIST;
    private static final Map<Integer, List<int[]>> SINGLE_COMBOS = new LinkedHashMap<>();
    private static final Map<Integer, List<int[]>> BOTH_COMBOS = new LinkedHashMap<>();

    static {
        List<Map<Integer, List<List<String>>>> boards = new ArrayList<>(2);
        for (int chessboard = 0; chessboard < 2; chessboard++) {
            Map<Integer, List<List<String>>> byOdd = new LinkedHashMap<>();
            for (int odd : PAGE_ODDS) byOdd.put(odd, new ArrayList<>());
            for (String a : SYMBOLS) {
                for (String b : SYMBOLS) {
                    for (String c : SYMBOLS) {
                        List<String> reels = List.of(a, b, c);
                        if (!drawable(chessboard, reels)) continue;
                        int odd;
                        try {
                            odd = pageOddFromReels(reels);
                        } catch (IllegalArgumentException rejected) {
                            continue;
                        }
                        List<List<String>> bucket = byOdd.get(odd);
                        if (bucket == null) {
                            throw new IllegalStateException("unlisted page odd " + odd);
                        }
                        bucket.add(reels);
                    }
                }
            }
            Map<Integer, List<List<String>>> frozen = new LinkedHashMap<>();
            for (int odd : PAGE_ODDS) {
                List<List<String>> bucket = byOdd.get(odd);
                if (bucket == null || bucket.isEmpty()) {
                    throw new IllegalStateException("empty catalog odd " + odd + " chessboard " + chessboard);
                }
                frozen.put(odd, List.copyOf(bucket));
            }
            boards.add(Map.copyOf(frozen));
        }
        BY_BOARD = List.copyOf(boards);

        for (int odd : PAGE_ODDS) {
            SINGLE_COMBOS.put(odd, List.of(new int[] {odd}));
        }
        SINGLE_LIST = List.copyOf(SINGLE_COMBOS.keySet());

        Map<Integer, List<int[]>> both = new LinkedHashMap<>();
        for (int top : PAGE_ODDS) {
            for (int bottom : PAGE_ODDS) {
                int displayed = displayedBoth(top, bottom);
                both.computeIfAbsent(displayed, key -> new ArrayList<>()).add(new int[] {top, bottom});
            }
        }
        TreeSet<Integer> bothKeys = new TreeSet<>(both.keySet());
        BOTH_LIST = List.copyOf(bothKeys);
        for (int key : BOTH_LIST) {
            BOTH_COMBOS.put(key, List.copyOf(both.get(key)));
        }
    }

    private JungleKingsMultiplierCatalog() { }

    public static Map<Integer, List<List<String>>> reelOdds(int chessboardIndex) {
        return Collections.unmodifiableMap(boardMap(chessboardIndex));
    }

    public static List<List<String>> boardsForOdd(int chessboardIndex, int pageOdd) {
        List<List<String>> list = boardMap(chessboardIndex).get(pageOdd);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("unsupported page odd " + pageOdd);
        }
        return list;
    }

    public static int pageOddFromReels(List<String> reels) {
        GameRuleCore.requireConfirmedWinBoundary(reels);
        String first = reels.get(0);
        boolean triple = first.equals(reels.get(1)) && first.equals(reels.get(2));
        if (!triple || GameRuleCore.payMultiplier(first) <= 0) return 0;
        return GameRuleCore.payMultiplier(first);
    }

    public static int pageOdd(List<String> board) {
        return pageOddFromReels(GameRuleCore.logicalReels(board));
    }

    public static List<Integer> oddsList(List<String> chessboards) {
        return both(chessboards) ? BOTH_LIST : SINGLE_LIST;
    }

    public static Map<Integer, List<int[]>> comboMap(List<String> chessboards) {
        return both(chessboards)
                ? Collections.unmodifiableMap(BOTH_COMBOS)
                : Collections.unmodifiableMap(SINGLE_COMBOS);
    }

    /** Greatest list value &lt;= requested. Values below the list floor become 0. */
    public static int floorOdd(List<String> chessboards, int requested) {
        List<Integer> list = oddsList(chessboards);
        int best = list.get(0);
        for (int value : list) {
            if (value <= requested) best = value;
            else break;
        }
        return best;
    }

    public static List<int[]> combos(List<String> chessboards, int flooredOdd) {
        List<int[]> list = comboMap(chessboards).get(flooredOdd);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("no combo for " + GameRuleCore.layoutToken(chessboards)
                    + " odd " + flooredOdd);
        }
        return list;
    }

    public static int[] pickCombo(List<String> chessboards, int flooredOdd, SecureRandom random) {
        List<int[]> list = combos(chessboards, flooredOdd);
        return list.get(random.nextInt(list.size()));
    }

    public static int comboCount(List<String> chessboards) {
        int n = 0;
        for (List<int[]> list : comboMap(chessboards).values()) n += list.size();
        return n;
    }

    /** Demo sampling when the caller does not pass an odd: 28% a positive list value, else 0. */
    public static int sampleRequestedOdd(SecureRandom random, List<String> chessboards) {
        if (random.nextInt(100) >= 28) return 0;
        return samplePositiveOdd(random, chessboards);
    }

    public static int samplePositiveOdd(SecureRandom random, List<String> chessboards) {
        List<Integer> wins = new ArrayList<>();
        for (int odd : oddsList(chessboards)) {
            if (odd > 0) wins.add(odd);
        }
        return wins.get(random.nextInt(wins.size()));
    }

    /** Displayed odds vs charged stake when only one of two lines wins. */
    public static int dualSingleDisplayed(int pageOdd) {
        return pageOdd / 2;
    }

    /** Displayed odds vs charged stake when both lines win (already x2). */
    public static int dualBothDisplayed(int oddTop, int oddBottom) {
        return oddTop + oddBottom;
    }

    static boolean both(List<String> chessboards) {
        return GameRuleCore.parseChessboards(String.join(",", chessboards)).size() == 2;
    }

    static int displayedBoth(int top, int bottom) {
        if (top > 0 && bottom > 0) return dualBothDisplayed(top, bottom);
        return dualSingleDisplayed(top) + dualSingleDisplayed(bottom);
    }

    private static Map<Integer, List<List<String>>> boardMap(int chessboardIndex) {
        if (chessboardIndex < 0 || chessboardIndex >= BY_BOARD.size()) {
            throw new IllegalArgumentException("chessboard index " + chessboardIndex);
        }
        return BY_BOARD.get(chessboardIndex);
    }

    private static boolean drawable(int chessboardIndex, List<String> reels) {
        for (int reel = 0; reel < GameRuleCore.REELS; reel++) {
            boolean found = false;
            for (String stop : GameRuleCore.STRIPS[chessboardIndex][reel]) {
                if (stop.equals(reels.get(reel))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
