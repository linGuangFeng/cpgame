package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * Requested odds floor into the legal multiplier list; combos are looked up in one shot.
 * Same generate path as Jungle Kings / Blessing: no Redis, no retry.
 */
public final class LuckyDragonMultiplierCatalog {
    public record Combo(List<String> symbols, int reelMultiplier) {
        public Combo {
            symbols = List.copyOf(symbols);
        }
    }

    private static final List<String> SYMBOLS = List.of("H0", "H1", "H2", "H3", "WILD");
    private static final Map<String, Integer> OBSERVED_TOTAL_MAX = Map.of(
            "H0", 3, "H1", 3, "H2", 2, "H3", 2, "H4", 0, "WILD", 2);

    private static final List<Integer> ODDS_LIST;
    private static final Map<Integer, List<Combo>> COMBOS;
    private static final Map<Integer, Boolean> SPECIAL;

    static {
        GameRuleCore rules = new GameRuleCore();
        RoundRequest probe = new RoundRequest(BigDecimal.ONE, 1);
        Map<Integer, List<Combo>> byOdd = new TreeMap<>();
        for (String left : SYMBOLS) {
            for (String center : SYMBOLS) {
                for (String right : SYMBOLS) {
                    List<String> symbols = List.of(left, center, right);
                    if (!withinObservedLimits(symbols)) continue;
                    int[] rpxValues = "WILD".equals(center) ? new int[] {3, 5, 9} : new int[] {0};
                    for (int rpx : rpxValues) {
                        SpinResult result = rules.evaluate(probe, symbols, rpx);
                        int odd = result.payout().signum() == 0 ? 0 : result.payout().intValueExact();
                        byOdd.computeIfAbsent(odd, key -> new ArrayList<>()).add(new Combo(symbols, rpx));
                    }
                }
            }
        }
        if (!byOdd.containsKey(0) || byOdd.get(0).isEmpty()) {
            throw new IllegalStateException("catalog missing loss support");
        }
        Map<Integer, List<Combo>> frozen = new LinkedHashMap<>();
        Map<Integer, Boolean> special = new LinkedHashMap<>();
        List<Integer> odds = new ArrayList<>();
        for (Map.Entry<Integer, List<Combo>> entry : byOdd.entrySet()) {
            int odd = entry.getKey();
            List<Combo> list = List.copyOf(entry.getValue());
            frozen.put(odd, list);
            odds.add(odd);
            boolean firstSpecial = odd > 0 && list.get(0).reelMultiplier() != 0;
            for (Combo combo : list) {
                boolean comboSpecial = odd > 0 && combo.reelMultiplier() != 0;
                if (comboSpecial != firstSpecial) {
                    throw new IllegalStateException("mixed special/normal combos for odd " + odd);
                }
            }
            special.put(odd, firstSpecial);
        }
        ODDS_LIST = List.copyOf(odds);
        COMBOS = Collections.unmodifiableMap(frozen);
        SPECIAL = Collections.unmodifiableMap(special);
    }

    private LuckyDragonMultiplierCatalog() { }

    public static List<Integer> oddsList() {
        return ODDS_LIST;
    }

    public static Map<Integer, List<Combo>> comboMap() {
        return COMBOS;
    }

    public static int comboCount() {
        int n = 0;
        for (List<Combo> list : COMBOS.values()) n += list.size();
        return n;
    }

    public static List<Combo> combos(int flooredOdd) {
        List<Combo> list = COMBOS.get(flooredOdd);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("no combo for odd " + flooredOdd);
        }
        return list;
    }

    public static Combo pickCombo(int flooredOdd, RandomGenerator random) {
        List<Combo> list = combos(flooredOdd);
        return list.get(random.nextInt(list.size()));
    }

    /** Greatest list value &lt;= requested. Values below the list floor become 0. */
    public static int floorOdd(int requested) {
        int best = ODDS_LIST.get(0);
        for (int value : ODDS_LIST) {
            if (value <= requested) best = value;
            else break;
        }
        return best;
    }

    public static boolean special(int odd) {
        Boolean value = SPECIAL.get(odd);
        if (value == null) throw new IllegalArgumentException("unsupported odd " + odd);
        return value;
    }

    /** Demo sampling when the caller does not pass an odd: 28% a positive list value, else 0. */
    public static int sampleRequestedOdd(RandomGenerator random) {
        if (random.nextInt(100) >= 28) return 0;
        return samplePositiveOdd(random);
    }

    public static int samplePositiveOdd(RandomGenerator random) {
        List<Integer> wins = new ArrayList<>();
        for (int odd : ODDS_LIST) {
            if (odd > 0) wins.add(odd);
        }
        return wins.get(random.nextInt(wins.size()));
    }

    static boolean withinObservedLimits(List<String> symbols) {
        for (String symbol : symbols) {
            Integer max = OBSERVED_TOTAL_MAX.get(symbol);
            if (max == null || max == 0) return false;
        }
        for (Map.Entry<String, Integer> limit : OBSERVED_TOTAL_MAX.entrySet()) {
            long count = symbols.stream().filter(limit.getKey()::equals).count();
            if (count > limit.getValue()) return false;
        }
        return true;
    }
}
