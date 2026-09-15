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
        return symbol == 13 || (symbol >= 2 && symbol <= 11);
    }
}
