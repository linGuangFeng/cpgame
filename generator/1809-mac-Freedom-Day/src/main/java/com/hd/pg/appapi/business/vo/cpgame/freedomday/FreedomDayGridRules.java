package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 前端 grids/gf/sl 的独立结构门禁；非法高度、跨列或空框不得进入投影。 */
public final class FreedomDayGridRules {
    private FreedomDayGridRules() { }

    public static void validate(FreedomDayBoard board) {
        Set<Integer> occupied = new HashSet<>();
        for (List<Integer> group : board.getGrids()) {
            if (group.size() < 2 || group.size() > 4) {
                throw new IllegalArgumentException("merged grid height must be 2..4");
            }
            int reel = group.get(0) / FreedomDayBoard.ROW_COUNT;
            if (reel == 0 || reel == FreedomDayBoard.REEL_COUNT - 1) {
                throw new IllegalArgumentException("outer reels only support height-1 symbols");
            }
            int symbol = board.getProp()[group.get(0)];
            for (int i = 0; i < group.size(); i++) {
                int index = group.get(i);
                if (index < 0 || index >= FreedomDayBoard.MAIN_SIZE
                        || index / FreedomDayBoard.ROW_COUNT != reel
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
        if (prop == null || prop.length != FreedomDayBoard.MAIN_SIZE) {
            throw new IllegalArgumentException("prop length must be 30");
        }
        List<List<Integer>> grids = new ArrayList<>();
        for (int reel = 1; reel <= 4; reel++) {
            int start = reel * FreedomDayBoard.ROW_COUNT;
            int row = 0;
            while (row < FreedomDayBoard.ROW_COUNT) {
                int symbol = prop[start + row];
                int run = 1;
                while (row + run < FreedomDayBoard.ROW_COUNT && prop[start + row + run] == symbol) run++;
                if (mergeable(symbol) && run >= 2) {
                    int height = Math.min(run, maxStackedHeight(symbol));
                    List<Integer> group = new ArrayList<>(height);
                    for (int offset = 0; offset < height; offset++) group.add(start + row + offset);
                    grids.add(new ArrayList<>(group));
                    row += height;
                } else {
                    row++;
                }
            }
        }
        return List.copyOf(grids);
    }

    public static boolean mergeable(int symbol) {
        // 原厂内轴 Ball/Wild 可叠 2–4 高；Scatter 抓包只有 2 高，loop3/loop4 动画不存在。
        return symbol >= 1 && symbol <= 13;
    }

    public static int maxStackedHeight(int symbol) {
        return symbol == FreedomDayResultUtil.SCATTER ? 2 : 4;
    }

    /** 下一页相对上一页新出现的可见倍率球；幸存球随重力下落，不重复收集。主盘叠组算 1，trl 各算 1。 */
    public static int countNewBalls(FreedomDayBoard previous, FreedomDayEvaluation previousEval, FreedomDayBoard next) {
        if (previous == null || previousEval == null) {
            return FreedomDayResultUtil.countVisibleSymbol(next, FreedomDayResultUtil.BALL);
        }
        Set<Integer> winMain = new HashSet<>();
        Set<Integer> removedTop = new HashSet<>();
        for (FreedomDayWin win : previousEval.getWins()) {
            winMain.addAll(win.getMainPositions());
            removedTop.addAll(win.getTopPositions());
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
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            List<Integer> survivors = new ArrayList<>();
            for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                int index = reel * FreedomDayBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivors.add(index);
            }
            int start = reel * FreedomDayBoard.ROW_COUNT + (FreedomDayBoard.ROW_COUNT - survivors.size());
            for (int i = 0; i < survivors.size(); i++) {
                if (oldProp[survivors.get(i)] == FreedomDayResultUtil.BALL) mappedMainBalls.add(start + i);
            }
        }
        int fresh = 0;
        int[] nextProp = next.getProp();
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            for (FreedomDayBoard.Position position : next.positionsOnReel(reel)) {
                if (position.isTop() || position.getSymbol() != FreedomDayResultUtil.BALL) continue;
                boolean anyNew = false;
                for (int index : position.getIndices()) {
                    if (nextProp[index] == FreedomDayResultUtil.BALL && !mappedMainBalls.contains(index)) {
                        anyNew = true;
                        break;
                    }
                }
                if (anyNew) fresh++;
            }
        }
        Set<Integer> mappedTopBalls = new HashSet<>();
        int[] oldTop = previous.getTrl();
        int mappedPos = 0;
        for (int i = 0; i < oldTop.length; i++) {
            if (removedTop.contains(i)) continue;
            if (oldTop[i] == FreedomDayResultUtil.BALL) mappedTopBalls.add(mappedPos);
            mappedPos++;
        }
        int[] nextTop = next.getTrl();
        for (int i = 0; i < nextTop.length; i++) {
            if (nextTop[i] == FreedomDayResultUtil.BALL && !mappedTopBalls.contains(i)) fresh++;
        }
        return fresh;
    }
}
