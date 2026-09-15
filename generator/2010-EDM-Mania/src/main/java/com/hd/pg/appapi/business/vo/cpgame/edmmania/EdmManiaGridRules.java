package com.hd.pg.appapi.business.vo.cpgame.edmmania;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 前端 grids/gf/sl 的独立结构门禁；非法高度、跨列或空框不得进入投影。 */
public final class EdmManiaGridRules {
    private EdmManiaGridRules() { }

    public static void validate(EdmManiaBoard board) {
        Set<Integer> occupied = new HashSet<>();
        for (List<Integer> group : board.getGrids()) {
            if (group.size() < 2 || group.size() > 4) {
                throw new IllegalArgumentException("merged grid height must be 2..4");
            }
            int reel = group.get(0) / EdmManiaBoard.ROW_COUNT;
            if (reel == 0 || reel == EdmManiaBoard.REEL_COUNT - 1) {
                throw new IllegalArgumentException("outer reels only support height-1 symbols");
            }
            int symbol = board.getProp()[group.get(0)];
            for (int i = 0; i < group.size(); i++) {
                int index = group.get(i);
                if (index < 0 || index >= EdmManiaBoard.MAIN_SIZE
                        || index / EdmManiaBoard.ROW_COUNT != reel
                        || index != group.get(0) + i) {
                    throw new IllegalArgumentException("merged grid must be a contiguous single-reel group");
                }
                if (!occupied.add(index)) throw new IllegalArgumentException("merged grids overlap");
                if (board.getProp()[index] != symbol) {
                    throw new IllegalArgumentException("all occupied cells of a merged grid must share one symbol");
                }
            }
        }
        validateFrames("gf", board.getGoldFrames(), board.getGrids());
        validateFrames("sl", board.getSilverFrames(), board.getGrids());
        Set<List<Integer>> gold = new HashSet<>(board.getGoldFrames());
        for (List<Integer> silver : board.getSilverFrames()) {
            if (gold.contains(silver)) throw new IllegalArgumentException("one grid cannot be both gold and silver");
        }
    }

    private static void validateFrames(String name, List<List<Integer>> frames, List<List<Integer>> grids) {
        Set<List<Integer>> seen = new HashSet<>();
        for (List<Integer> frame : frames) {
            if (!grids.contains(frame)) throw new IllegalArgumentException(name + " must reference a complete grids group");
            if (!seen.add(frame)) throw new IllegalArgumentException(name + " contains duplicate groups");
        }
    }

    public static List<Integer> groupAt(List<List<Integer>> groups, int index) {
        for (List<Integer> group : groups) if (group.contains(index)) return group;
        return null;
    }

    /** Reconstruct stacked occupancy from a prop array. Frames are not inferred. */
    public static List<List<Integer>> inferMergedGroups(int[] prop) {
        if (prop == null || prop.length != EdmManiaBoard.MAIN_SIZE) {
            throw new IllegalArgumentException("prop length must be 30");
        }
        List<List<Integer>> grids = new ArrayList<>();
        for (int reel = 1; reel <= 4; reel++) {
            int start = reel * EdmManiaBoard.ROW_COUNT;
            int row = 0;
            while (row < EdmManiaBoard.ROW_COUNT) {
                int symbol = prop[start + row];
                int run = 1;
                while (row + run < EdmManiaBoard.ROW_COUNT && prop[start + row + run] == symbol) run++;
                if (mergeable(symbol) && run >= 2) {
                    int height = Math.min(run, 4);
                    List<Integer> group = new ArrayList<>(height);
                    for (int offset = 0; offset < height; offset++) group.add(start + row + offset);
                    grids.add(List.copyOf(group));
                    row += height;
                } else {
                    row++;
                }
            }
        }
        return List.copyOf(grids);
    }

    public static boolean mergeable(int symbol) {
        // 原站完整免费局：Ball 与 Scatter 均可在内轴叠成 2–4 高（abc223 round-003 四连球、round-001 叠 Scatter）。
        return symbol >= 1 && symbol <= 13;
    }

    /** 下一页相对上一页新出现的倍率球数量；幸存球随重力下落，不重复计数。 */
    public static int countNewBalls(EdmManiaBoard previous, EdmManiaEvaluation previousEval, EdmManiaBoard next) {
        if (previous == null) return EdmManiaResultUtil.countVisibleMainBalls(next);
        Set<Integer> winMain = new HashSet<>();
        for (EdmManiaWin win : previousEval.getWins()) {
            winMain.addAll(win.getMainPositions());
        }
        Set<Integer> transformed = new HashSet<>();
        for (List<Integer> frame : previous.getGoldFrames()) {
            if (frame.stream().anyMatch(winMain::contains)) transformed.addAll(frame);
        }
        for (List<Integer> frame : previous.getSilverFrames()) {
            if (frame.stream().anyMatch(winMain::contains)) transformed.addAll(frame);
        }
        Set<Integer> removedMain = new HashSet<>(winMain);
        for (List<Integer> group : previous.getGrids()) {
            if (group.stream().anyMatch(winMain::contains) && !transformed.contains(group.get(0))) {
                removedMain.addAll(group);
            }
        }
        removedMain.removeAll(transformed);

        Set<Integer> mappedMainBalls = new HashSet<>();
        int[] oldProp = previous.getProp();
        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            List<Integer> survivors = new ArrayList<>();
            for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                int index = reel * EdmManiaBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivors.add(index);
            }
            int start = reel * EdmManiaBoard.ROW_COUNT + (EdmManiaBoard.ROW_COUNT - survivors.size());
            for (int i = 0; i < survivors.size(); i++) {
                if (oldProp[survivors.get(i)] == EdmManiaResultUtil.BALL) mappedMainBalls.add(start + i);
            }
        }
        int fresh = 0;
        int[] nextProp = next.getProp();
        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            for (EdmManiaBoard.Position position : next.positionsOnReel(reel)) {
                if (position.isTop() || position.getSymbol() != EdmManiaResultUtil.BALL) continue;
                boolean anyNew = false;
                for (int index : position.getIndices()) {
                    if (nextProp[index] == EdmManiaResultUtil.BALL && !mappedMainBalls.contains(index)) {
                        anyNew = true;
                        break;
                    }
                }
                if (anyNew) fresh++;
            }
        }
        return fresh;
    }
}
