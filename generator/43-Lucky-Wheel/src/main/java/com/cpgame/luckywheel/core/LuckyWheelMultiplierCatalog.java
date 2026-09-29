package com.cpgame.luckywheel.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * 初始配置（类加载填一次）：
 * {@code ODDS}   = Map&lt;押注档, List&lt;倍数&gt;&gt;
 * {@code COMBOS} = Map&lt;押注档, Map&lt;倍数, List&lt;中奖形&gt;&gt;&gt;
 * 中奖形不含空白；出牌 get/floorKey 后随机填 H0。
 */
public final class LuckyWheelMultiplierCatalog {
    public record WinCombo(int md, List<String> base, int multiplier, List<String> respin, int wheel) {
        public WinCombo {
            base = List.copyOf(base);
            respin = List.copyOf(respin);
        }

        int award() {
            int baseScore = ResultUtil.independentScore(base).intValueExact();
            return switch (md) {
                case 0 -> baseScore;
                case 1 -> baseScore * multiplier;
                case 2 -> baseScore + ResultUtil.independentScore(respin).intValueExact();
                case 3 -> baseScore + wheel;
                default -> throw new IllegalStateException("md=" + md);
            };
        }

        RoundFacts materialize(int betMode, RandomGenerator random) {
            int cells = BetUnlock.cells(betMode);
            int profile = BetUnlock.profile(betMode);
            List<String> board = WinComboCatalog.place(base, cells, random);
            return switch (md) {
                case 0 -> RoundFacts.ordinary(profile, board);
                case 1 -> RoundFacts.multiplier(profile, board, multiplier);
                case 2 -> RoundFacts.respin(profile, board, WinComboCatalog.place(respin, cells, random));
                case 3 -> RoundFacts.luckyWheel(board, wheel);
                default -> throw new IllegalStateException("md=" + md);
            };
        }
    }

    private static final int[] MODE_LEVELS = {1, 5, 10, 50};

    /** 押注档 -> 倍数列表。 */
    private static final Map<Integer, List<Integer>> ODDS;

    /** 押注档 -> (倍数 -> 中奖形列表)。 */
    private static final Map<Integer, NavigableMap<Integer, List<WinCombo>>> COMBOS;

    static {
        Map<Integer, List<Integer>> odds = new LinkedHashMap<>();
        Map<Integer, NavigableMap<Integer, List<WinCombo>>> combos = new LinkedHashMap<>();
        for (int mode : MODE_LEVELS) {
            NavigableMap<Integer, List<WinCombo>> byOdd = buildMode(mode);
            combos.put(mode, Collections.unmodifiableNavigableMap(byOdd));
            odds.put(mode, List.copyOf(byOdd.navigableKeySet()));
        }
        ODDS = Collections.unmodifiableMap(odds);
        COMBOS = Collections.unmodifiableMap(combos);
        if (floorOdd(5, 15) != 15 || floorOdd(5, LuckyWheelStake.MAX_ODD + 1) != LuckyWheelStake.MAX_ODD) {
            throw new IllegalStateException("Lucky Wheel mode 5 must contain 15 and cap at 1000");
        }
        if (mode(100) != 50 || mode(50) != 50 || mode(49) != 10) {
            throw new IllegalStateException("Lucky Wheel bet mode mapping is 1/5/10/50");
        }
    }

    private LuckyWheelMultiplierCatalog() { }

    public static int mode(int betLevel) {
        if (betLevel < 5) return 1;
        if (betLevel < 10) return 5;
        if (betLevel < 50) return 10;
        return 50;
    }

    public static Map<Integer, List<Integer>> oddsByMode() {
        return ODDS;
    }

    public static Map<Integer, NavigableMap<Integer, List<WinCombo>>> combosByMode() {
        return COMBOS;
    }

    public static List<Integer> oddsList(int betLevel) {
        return ODDS.get(mode(betLevel));
    }

    public static int floorOdd(int betLevel, int requested) {
        if (requested <= 0) return 0;
        Integer floored = COMBOS.get(mode(betLevel)).floorKey(requested);
        return floored == null ? 0 : floored;
    }

    public static List<WinCombo> combos(int betLevel, int flooredOdd) {
        if (flooredOdd <= 0) return List.of();
        List<WinCombo> list = COMBOS.get(mode(betLevel)).get(flooredOdd);
        return list == null ? List.of() : list;
    }

    public static RoundFacts pick(int betLevel, int requested, RandomGenerator random) {
        int mode = mode(betLevel);
        int floored = floorOdd(betLevel, requested);
        List<WinCombo> list = COMBOS.get(mode).get(floored);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "Lucky Wheel mode " + mode
                            + " has no combo for floored ratio " + floored
                            + " (requested " + requested + ")");
        }
        return list.get(random.nextInt(list.size())).materialize(mode, random);
    }

    public static int sample(int betLevel, RandomGenerator random) {
        if (random.nextInt(100) < 40) return 0;
        List<Integer> wins = ODDS.get(mode(betLevel));
        if (wins.isEmpty()) return 0;
        return wins.get(random.nextInt(wins.size()));
    }

    public static RoundFacts pickAnyWin(int betLevel, RandomGenerator random) {
        List<Integer> wins = ODDS.get(mode(betLevel));
        if (wins == null || wins.isEmpty()) {
            throw new IllegalStateException("Lucky Wheel mode " + mode(betLevel) + " has no win odds");
        }
        return pick(betLevel, wins.get(random.nextInt(wins.size())), random);
    }

    private static NavigableMap<Integer, List<WinCombo>> buildMode(int mode) {
        NavigableMap<Integer, List<WinCombo>> byOdd = new TreeMap<>();
        List<List<String>> patterns = WinComboCatalog.patterns(BetUnlock.cells(mode));
        for (List<String> board : patterns) {
            put(byOdd, new WinCombo(0, board, 1, List.of(), 0));
            for (int mul : BetUnlock.multipliers(mode)) {
                put(byOdd, new WinCombo(1, board, mul, List.of(), 0));
            }
            for (List<String> respin : patterns) {
                put(byOdd, new WinCombo(2, board, 1, respin, 0));
            }
            if (BetUnlock.scatter(mode)) {
                for (int wheel : BetUnlock.wheelAwards(mode)) {
                    put(byOdd, new WinCombo(3, board, 1, List.of(), wheel));
                }
            }
        }
        NavigableMap<Integer, List<WinCombo>> frozen = new TreeMap<>();
        byOdd.forEach((odd, list) -> frozen.put(odd, List.copyOf(list)));
        return frozen;
    }

    private static void put(NavigableMap<Integer, List<WinCombo>> byOdd, WinCombo combo) {
        int award = combo.award();
        if (award <= 0 || award > LuckyWheelStake.MAX_ODD) return;
        byOdd.computeIfAbsent(award, ignored -> new ArrayList<>()).add(combo);
    }
}
