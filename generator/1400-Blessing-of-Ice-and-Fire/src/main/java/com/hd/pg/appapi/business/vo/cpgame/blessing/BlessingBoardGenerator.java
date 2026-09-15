package com.hd.pg.appapi.business.vo.cpgame.blessing;

import java.security.SecureRandom;
import java.util.List;

/**
 * Fills a 3x3 board for a chosen middle-row odd.
 * Origin empty cells sit only in a losing middle row. Top/bottom fillers are always 1/2/3,
 * so a win is a full aligned 3x3 and a column never has a single symbol.
 */
public final class BlessingBoardGenerator {
    static final int[] FILLER_INDEX = {0, 2, 3, 5, 6, 8};
    static final int[] MIDDLE_INDEX = {1, 4, 7};
    /** Origin top/bottom mix: symbols 1/2/3 only. Never empty. */
    private static final int[] FILLER = {1, 1, 2, 2, 3, 3, 1, 2, 3};

    private BlessingBoardGenerator() { }

    public static int[] generate(SecureRandom random, int odd) {
        List<int[]> middles = BlessingMultiplierCatalog.middlesForOdd(odd);
        int[] mid = middles.get(random.nextInt(middles.size()));
        int[] p = BlessingMultiplierCatalog.boardFromMiddle(mid);
        for (int i : FILLER_INDEX) {
            p[i] = FILLER[random.nextInt(FILLER.length)];
        }
        int got = BlessingResultUtil.evaluateOdd(p);
        if (got != odd) throw new IllegalStateException("generated odd " + got + " != " + odd);
        requireOriginShape(p, odd);
        return p;
    }

    public static int[] idleLoss(SecureRandom random) {
        return generate(random, 0);
    }

    /** Origin: fillers never 0. Wins are a full 3x3. Losses have at least one middle 0. */
    public static void requireOriginShape(int[] p, int odd) {
        BlessingResultUtil.requireBoard(p);
        for (int i : FILLER_INDEX) {
            if (p[i] == BlessingResultUtil.EMPTY) {
                throw new IllegalStateException("filler empty at " + i);
            }
        }
        if (odd > 0) {
            for (int v : p) {
                if (v == BlessingResultUtil.EMPTY) {
                    throw new IllegalStateException("win board has empty");
                }
            }
            return;
        }
        if (p[1] != BlessingResultUtil.EMPTY && p[4] != BlessingResultUtil.EMPTY
                && p[7] != BlessingResultUtil.EMPTY) {
            throw new IllegalStateException("loss middle has no empty");
        }
    }
}
