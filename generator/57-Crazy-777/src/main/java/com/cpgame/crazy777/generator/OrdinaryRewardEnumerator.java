package com.cpgame.crazy777.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.random.RandomGenerator;

/**
 * 普通奖励穷举：枚举全部合法可见卷轴结构（不含三轴可见 SC 的玛丽触发）。
 * 倍率范围和每档条数上限仍生效；loss-count / win-count 不参与。
 */
public final class OrdinaryRewardEnumerator {
    private static final String[] NON_BLANK = {"H1", "H2", "H3", "H4", "H5", "H6", "WILD", "SC"};
    private static final String HIDDEN_LEFT = "H6";
    private static final String HIDDEN_RIGHT = "H1";

    private OrdinaryRewardEnumerator() {}

    public record Board(int ratio, List<String> cells) {}

    public static List<Board> enumerate(LoaderLimits limits, int cap, RandomGenerator random) {
        if (limits == null || random == null) throw new IllegalArgumentException("ordinary enumeration requires limits and a random source");
        if (cap < 1) throw new IllegalArgumentException("ordinary cap must be positive");
        Reel[] reels = reelCatalog();
        Map<Integer, LinkedHashMap<String, Family>> plans = new TreeMap<>();
        for (Reel a : reels) {
            for (Reel b : reels) {
                for (Reel c : reels) {
                    if (a.wilds + b.wilds + c.wilds > GameRules.WILD_BOARD_MAX) continue;
                    if (a.sc + b.sc + c.sc > GameRules.SC_BOARD_MAX) continue;
                    if (a.visibleSc && b.visibleSc && c.visibleSc) continue;
                    List<String> board = assemble(a, b, c);
                    Map<String, String> wins;
                    try {
                        wins = ResultUtil.evaluateRegularLines(board);
                    } catch (IllegalArgumentException illegal) {
                        continue;
                    }
                    int ratio = 0;
                    for (String reward : wins.values()) ratio += GameRules.PAYTABLE.get(reward);
                    if (!limits.accepts(false, ratio)) continue;
                    plans.computeIfAbsent(ratio, key -> new LinkedHashMap<>())
                            .computeIfAbsent(wins.toString(), key -> new Family())
                            .offer(board, cap, random);
                }
            }
        }
        return select(plans, cap, random);
    }

    static int reelCatalogSize() {
        return reelCatalog().length;
    }

    private static List<Board> select(Map<Integer, LinkedHashMap<String, Family>> plans, int cap, RandomGenerator random) {
        Random shuffle = new Random(random.nextLong());
        List<Board> selected = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashMap<String, Family>> bucket : plans.entrySet()) {
            int ratio = bucket.getKey();
            List<Family> families = new ArrayList<>(bucket.getValue().values());
            Collections.shuffle(families, shuffle);
            for (Family family : families) Collections.shuffle(family.boards, shuffle);
            int[] cursor = new int[families.size()];
            Set<String> seen = new HashSet<>();
            boolean progress = true;
            while (seen.size() < cap && progress) {
                progress = false;
                for (int i = 0; i < families.size() && seen.size() < cap; i++) {
                    Family family = families.get(i);
                    while (cursor[i] < family.boards.size()) {
                        List<String> board = family.boards.get(cursor[i]++);
                        if (!seen.add(String.join(",", board))) continue;
                        selected.add(new Board(ratio, board));
                        progress = true;
                        break;
                    }
                }
            }
        }
        return selected;
    }

    private static Reel[] reelCatalog() {
        List<Reel> reels = new ArrayList<>(71);
        for (String left : NON_BLANK) {
            for (String right : NON_BLANK) {
                if ("SC".equals(left) && "SC".equals(right)) continue;
                reels.add(reel(new String[]{"BLANK", left, "BLANK", right, "BLANK"}));
            }
        }
        for (String mid : NON_BLANK) {
            reels.add(reel(new String[]{HIDDEN_LEFT, "BLANK", mid, "BLANK", HIDDEN_RIGHT}));
        }
        return reels.toArray(Reel[]::new);
    }

    private static Reel reel(String[] cells) {
        int wilds = 0;
        int sc = 0;
        boolean visibleSc = false;
        for (int i = 0; i < 5; i++) {
            if ("WILD".equals(cells[i])) wilds++;
            if ("SC".equals(cells[i])) {
                sc++;
                if (i >= 1 && i <= 3) visibleSc = true;
            }
        }
        return new Reel(cells, wilds, sc, visibleSc);
    }

    private static List<String> assemble(Reel a, Reel b, Reel c) {
        String[] cells = new String[GameRules.BOARD_SIZE];
        System.arraycopy(a.cells, 0, cells, 0, 5);
        System.arraycopy(b.cells, 0, cells, 5, 5);
        System.arraycopy(c.cells, 0, cells, 10, 5);
        return List.of(cells);
    }

    private static final class Family {
        private final List<List<String>> boards = new ArrayList<>();
        private int seen;

        void offer(List<String> board, int cap, RandomGenerator random) {
            seen++;
            if (boards.size() < cap) {
                boards.add(board);
                return;
            }
            int index = random.nextInt(seen);
            if (index < cap) boards.set(index, board);
        }
    }

    private record Reel(String[] cells, int wilds, int sc, boolean visibleSc) {}
}
