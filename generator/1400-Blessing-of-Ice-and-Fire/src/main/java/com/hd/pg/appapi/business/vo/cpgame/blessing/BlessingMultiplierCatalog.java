package com.hd.pg.appapi.business.vo.cpgame.blessing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Page-odd middle patterns, plus the bet-mode odd lists and fire/ice combo map.
 * Requested odds are floored to the mode list; combos are looked up; fillers stay realtime.
 */
public final class BlessingMultiplierCatalog {
    public static final int[] PAGE_ODDS = {0, 5, 25, 50, 100};

    private static final Map<Integer, List<int[]>> BY_ODD = new LinkedHashMap<>();
    private static final List<Integer> SINGLE_LIST;
    private static final List<Integer> BOTH_LIST;
    private static final Map<Integer, List<int[]>> SINGLE_FIRE_COMBOS = new LinkedHashMap<>();
    private static final Map<Integer, List<int[]>> SINGLE_ICE_COMBOS = new LinkedHashMap<>();
    private static final Map<Integer, List<int[]>> BOTH_COMBOS = new LinkedHashMap<>();

    static {
        List<int[]> loss = new ArrayList<>();
        List<int[]> any = new ArrayList<>();
        for (int a = 0; a <= 3; a++) {
            for (int b = 0; b <= 3; b++) {
                for (int c = 0; c <= 3; c++) {
                    int[] mid = {a, b, c};
                    int odd = BlessingResultUtil.evaluateOdd(boardFromMiddle(mid));
                    if (odd == 0) loss.add(mid);
                    else if (odd == BlessingResultUtil.ODD_ANY) any.add(mid);
                }
            }
        }
        BY_ODD.put(0, List.copyOf(loss));
        BY_ODD.put(BlessingResultUtil.ODD_ANY, List.copyOf(any));
        BY_ODD.put(BlessingResultUtil.ODD_LOW, List.of(new int[] {3, 3, 3}));
        BY_ODD.put(BlessingResultUtil.ODD_MID, List.of(new int[] {2, 2, 2}));
        BY_ODD.put(BlessingResultUtil.ODD_HIGH, List.of(new int[] {1, 1, 1}));

        for (int odd : PAGE_ODDS) {
            SINGLE_FIRE_COMBOS.put(odd, List.of(new int[] {odd, 0}));
            SINGLE_ICE_COMBOS.put(odd, List.of(new int[] {0, odd}));
        }
        SINGLE_LIST = List.copyOf(SINGLE_FIRE_COMBOS.keySet());

        Map<Integer, List<int[]>> both = new LinkedHashMap<>();
        for (int top : PAGE_ODDS) {
            for (int bottom : PAGE_ODDS) {
                both.computeIfAbsent(top + bottom, key -> new ArrayList<>()).add(new int[] {top, bottom});
            }
        }
        TreeSet<Integer> bothKeys = new TreeSet<>(both.keySet());
        BOTH_LIST = List.copyOf(bothKeys);
        for (int key : BOTH_LIST) {
            BOTH_COMBOS.put(key, List.copyOf(both.get(key)));
        }
    }

    private BlessingMultiplierCatalog() { }

    public static Map<Integer, List<int[]>> singleReelOdds() {
        return Collections.unmodifiableMap(BY_ODD);
    }

    public static List<int[]> middlesForOdd(int odd) {
        List<int[]> list = BY_ODD.get(odd);
        if (list == null || list.isEmpty()) throw new IllegalArgumentException("unsupported odd " + odd);
        return list;
    }

    public static int[] boardFromMiddle(int[] mid) {
        if (mid == null || mid.length != 3) throw new IllegalArgumentException("middle must be 3");
        int[] p = new int[9];
        p[1] = mid[0];
        p[4] = mid[1];
        p[7] = mid[2];
        return p;
    }

    public static List<Integer> oddsList(int betType) {
        return betType == BlessingRoundFactory.TYPE_BOTH ? BOTH_LIST : SINGLE_LIST;
    }

    public static Map<Integer, List<int[]>> comboMap(int betType) {
        return switch (betType) {
            case BlessingRoundFactory.TYPE_FIRE -> Collections.unmodifiableMap(SINGLE_FIRE_COMBOS);
            case BlessingRoundFactory.TYPE_ICE -> Collections.unmodifiableMap(SINGLE_ICE_COMBOS);
            case BlessingRoundFactory.TYPE_BOTH -> Collections.unmodifiableMap(BOTH_COMBOS);
            default -> throw new IllegalArgumentException("bet_type 1|2|3");
        };
    }

    /** Greatest list value &lt;= requested. Values below the list floor become 0. */
    public static int floorOdd(int betType, int requested) {
        List<Integer> list = oddsList(betType);
        int best = list.get(0);
        for (int value : list) {
            if (value <= requested) best = value;
            else break;
        }
        return best;
    }

    public static List<int[]> combos(int betType, int flooredOdd) {
        List<int[]> list = comboMap(betType).get(flooredOdd);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("no combo for bet_type " + betType + " odd " + flooredOdd);
        }
        return list;
    }

    public static int comboCount(int betType) {
        int n = 0;
        for (List<int[]> list : comboMap(betType).values()) n += list.size();
        return n;
    }

    /** Displayed odds vs charged stake for bet_type 3 when only one reel wins. */
    public static int type3SingleDisplayed(int pageOdd) {
        return pageOdd / 2;
    }

    /** Displayed odds vs charged stake for bet_type 3 when both reels win (already x2). */
    public static int type3BothDisplayed(int oddTop, int oddBottom) {
        return oddTop + oddBottom;
    }
}
