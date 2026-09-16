package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Gold/silver long-frame overlay (gfl/sfl). Help: silver may surround height 2-4 symbols
 * on reels 2-5 excluding Wild and treasure. Capture: 4317 framed coords were all
 * inner-main height 2-4; caps gfl 3 / sfl 6; no overlap.
 */
public final class LuckyPandaFrameAssigner {
    public static final int GFL_MAX = 3;
    public static final int SFL_MAX = 6;
    /** none / silver / gold counts by height 2,3,4 from 3382 original-http pages. */
    private static final int[][] HEIGHT_KIND = {
            null,
            null,
            {4988, 2179, 461},
            {2451, 1053, 200},
            {880, 386, 38}
    };

    private LuckyPandaFrameAssigner() { }

    public record Frames(List<Integer> gfl, List<Integer> sfl) {
        public Frames {
            gfl = List.copyOf(gfl);
            sfl = List.copyOf(sfl);
        }
    }

    /** Height 2-4 inner-main paying symbols only. Help excludes Wild and treasure. */
    public static boolean frameable(LuckyPandaToken token) {
        if (token == null || token.top() || token.height() < 2) return false;
        if (token.reel() < 1 || token.reel() > 4) return false;
        return token.symbol().paying();
    }

    public static Frames assign(LuckyPandaBoard board, Random random) {
        return complete(board, List.of(), List.of(), random);
    }

    /**
     * Opening pages only. Cascade must not call this: capture never turns an unframed
     * surviving stack into silver (2265 stay none / 0 gain silver).
     */
    public static Frames complete(LuckyPandaBoard board, List<Integer> persistGold,
                                  List<Integer> persistSilver, Random random) {
        if (board == null) throw new IllegalArgumentException("board is required");
        if (random == null) throw new IllegalArgumentException("random is required");
        List<Integer> gfl = new ArrayList<>(persistGold == null ? List.of() : persistGold);
        List<Integer> sfl = new ArrayList<>(persistSilver == null ? List.of() : persistSilver);
        Set<Integer> taken = new HashSet<>();
        taken.addAll(gfl);
        taken.addAll(sfl);
        for (int reel = 1; reel <= 4; reel++) {
            for (LuckyPandaToken token : board.reel(reel)) {
                if (taken.contains(token.coord()) || !frameable(token)) continue;
                int kind = pick(random, HEIGHT_KIND[token.height()]);
                if (kind == 1 && sfl.size() < SFL_MAX) {
                    sfl.add(token.coord());
                    taken.add(token.coord());
                } else if (kind == 2 && gfl.size() < GFL_MAX) {
                    gfl.add(token.coord());
                    taken.add(token.coord());
                }
            }
        }
        return finish(board, gfl, sfl);
    }

    /** Keep surviving gold/silver only. New refill and unframed survivors stay unframed. */
    public static Frames persist(LuckyPandaBoard board, List<Integer> persistGold, List<Integer> persistSilver) {
        return finish(board,
                persistGold == null ? List.of() : persistGold,
                persistSilver == null ? List.of() : persistSilver);
    }

    private static Frames finish(LuckyPandaBoard board, List<Integer> persistGold, List<Integer> persistSilver) {
        List<Integer> gfl = new ArrayList<>(persistGold);
        List<Integer> sfl = new ArrayList<>(persistSilver);
        if (gfl.size() > GFL_MAX) gfl = new ArrayList<>(gfl.subList(0, GFL_MAX));
        if (sfl.size() > SFL_MAX) sfl = new ArrayList<>(sfl.subList(0, SFL_MAX));
        Collections.sort(gfl);
        Collections.sort(sfl);
        Frames frames = new Frames(gfl, sfl);
        validate(board, frames.gfl(), frames.sfl());
        return frames;
    }

    public static void validate(LuckyPandaBoard board, List<Integer> gfl, List<Integer> sfl) {
        if (board == null) throw new IllegalArgumentException("board is required");
        List<Integer> gold = gfl == null ? List.of() : gfl;
        List<Integer> silver = sfl == null ? List.of() : sfl;
        if (gold.size() > GFL_MAX) throw new IllegalArgumentException("gfl exceeds capture max 3");
        if (silver.size() > SFL_MAX) throw new IllegalArgumentException("sfl exceeds capture max 6");
        Set<Integer> seen = new HashSet<>();
        for (int coord : gold) {
            if (!seen.add(coord)) throw new IllegalArgumentException("duplicate gfl coord " + coord);
            requireStack(board, coord, "gfl");
        }
        for (int coord : silver) {
            if (!seen.add(coord)) throw new IllegalArgumentException("gfl/sfl overlap or duplicate sfl " + coord);
            requireStack(board, coord, "sfl");
        }
    }

    private static void requireStack(LuckyPandaBoard board, int coord, String field) {
        LuckyPandaToken token = board.tokenAt(coord);
        if (token == null) throw new IllegalArgumentException(field + " coord missing: " + coord);
        if (token.top()) throw new IllegalArgumentException(field + " cannot mark inner top overlay");
        if (token.reel() < 1 || token.reel() > 4) {
            throw new IllegalArgumentException(field + " only inner-main reels 1-4");
        }
        if (token.height() < 2) throw new IllegalArgumentException(field + " only long-frame height 2-4");
    }

    private static int pick(Random random, int[] weights) {
        int total = 0;
        for (int value : weights) total += value;
        int pick = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick < 0) return i;
        }
        return 0;
    }
}
