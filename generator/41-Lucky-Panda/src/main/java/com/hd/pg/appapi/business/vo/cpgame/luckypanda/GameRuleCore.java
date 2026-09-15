package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single GameRuleCore shared by the Redis Loader and server-api. Projection and
 * validation only; Demo must not deal boards from this class at request time.
 */
public final class GameRuleCore {
    public static final int GAME_ID = 41;
    public static final String GAME_NAME = "Lucky Panda";
    public static final String RULES_VERSION = "gid41-protocol-20260904";
    public static final String RULES_HASH = "5f8142ce67bb887905edb625ebfb90128fa872cc0de8e9af0f8017debc2dc50c";
    public static final int[] ROW_COUNTS = LuckyPandaBoard.ROW_COUNTS;
    public static final int STAKE_FACTOR = 20;
    public static final BigDecimal MIN_BET_SIZE = new BigDecimal("0.02");
    public static final int MIN_BET_LEVEL = 1;
    public static final int FREE_SPINS = LuckyPandaResultUtil.FREE_SPINS_AWARDED;
    /**
     * Scat is the scatter-free trigger symbol. Help/paytable has no numeric cap.
     * Caps are the maxima observed on 3383 unique deliveries (1333 complete Rounds).
     * Evidence: protocol/41-Lucky-Panda/result-engine-symbol-stats.json scatterCaps.
     * 3 blocks on one reel appeared once; 5 blocks on one page appeared once; 4 cells
     * on one reel and 9 cells on one page are the cell maxima. Unseen counts are illegal.
     */
    public static final int SCAT_COLUMN_MAX_BLOCKS = 3;
    public static final int SCAT_TOTAL_MAX_BLOCKS = 5;
    public static final int SCAT_COLUMN_MAX_CELLS = 4;
    public static final int SCAT_TOTAL_MAX_CELLS = 9;
    /**
     * Wild is not a trigger symbol and is not in the paying paytable. Help has no numeric
     * Wild cap. Captured maxima on 3382 original-http pages: 3 RLE blocks / 6 cells on one
     * page, 2 blocks / 5 cells on one reel. Paid-start cell rate 185/45322 ≈ 0.408%.
     * Wild substitutes paying symbols only and never substitutes Scat.
     */
    public static final int WILD_COLUMN_MAX_BLOCKS = 2;
    public static final int WILD_TOTAL_MAX_BLOCKS = 3;
    public static final int WILD_COLUMN_MAX_CELLS = 5;
    public static final int WILD_TOTAL_MAX_CELLS = 6;
    /**
     * Help: "Free spins can be retriggered." Same 4-block threshold and the same
     * 10+2*(blocks-4) award. Capture had 0 retriggers in 38 free Rounds (rare, not
     * forbidden). Free pages use the same Scat caps as paid pages.
     */
    public static final int FREE_SCATTER_MAX_BLOCKS = SCAT_TOTAL_MAX_BLOCKS;

    private GameRuleCore() { }

    public static LuckyPandaEvaluation evaluate(LuckyPandaBoard board, BigDecimal betSize, int betLevel, int rpx) {
        return LuckyPandaResultUtil.evaluate(board, betSize, betLevel, rpx);
    }

    public static RoundClass classify(boolean scatterFree, boolean hasPositiveAward) {
        if (scatterFree && hasPositiveAward) return RoundClass.SCATTER_FREE;
        if (scatterFree) return RoundClass.SCATTER_FREE;
        if (hasPositiveAward) return RoundClass.ORDINARY_WIN;
        if (!scatterFree && !hasPositiveAward) return RoundClass.ORDINARY_LOSS;
        throw new IllegalStateException("unclassified Lucky Panda round");
    }

    public static RoundClass classifyRound(int fsn, BigDecimal terminalRwa) {
        boolean scatterFree = fsn >= FREE_SPINS;
        boolean hasPositiveAward = terminalRwa != null && terminalRwa.signum() > 0;
        if (fsn != 0 && (fsn < FREE_SPINS || fsn % 2 != 0)) {
            throw new IllegalStateException("fsn must be 0 or even >= 10, got " + fsn);
        }
        return classify(scatterFree, hasPositiveAward);
    }

    public static boolean isSpecialPool(RoundClass roundClass) {
        return switch (roundClass) {
            case SCATTER_FREE -> true;
            case ORDINARY_LOSS, ORDINARY_WIN -> false;
        };
    }

    public static BigDecimal stakeAmount(BigDecimal betSize, int betLevel) {
        return betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(STAKE_FACTOR));
    }

    public static List<LuckyPandaSymbol> payingSymbols() {
        return List.of(
                LuckyPandaSymbol.PAN, LuckyPandaSymbol.H1, LuckyPandaSymbol.H2, LuckyPandaSymbol.H3,
                LuckyPandaSymbol.H4, LuckyPandaSymbol.H5, LuckyPandaSymbol.A, LuckyPandaSymbol.K,
                LuckyPandaSymbol.Q, LuckyPandaSymbol.J, LuckyPandaSymbol.T);
    }

    public static boolean withinScatterCaps(LuckyPandaBoard board) {
        if (board == null) throw new IllegalArgumentException("board is required");
        if (board.scatterTokens() > SCAT_TOTAL_MAX_BLOCKS) return false;
        if (board.scatterCells() > SCAT_TOTAL_MAX_CELLS) return false;
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            if (board.scatterTokensOnReel(reel) > SCAT_COLUMN_MAX_BLOCKS) return false;
            if (board.scatterCellsOnReel(reel) > SCAT_COLUMN_MAX_CELLS) return false;
        }
        return true;
    }

    public static boolean withinWildCaps(LuckyPandaBoard board) {
        if (board == null) throw new IllegalArgumentException("board is required");
        if (board.tokens(LuckyPandaSymbol.WILD) > WILD_TOTAL_MAX_BLOCKS) return false;
        if (board.cells(LuckyPandaSymbol.WILD) > WILD_TOTAL_MAX_CELLS) return false;
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            if (board.tokensOnReel(reel, LuckyPandaSymbol.WILD) > WILD_COLUMN_MAX_BLOCKS) return false;
            if (board.cellsOnReel(reel, LuckyPandaSymbol.WILD) > WILD_COLUMN_MAX_CELLS) return false;
        }
        return true;
    }

    public static boolean withinCapturedCaps(LuckyPandaBoard board) {
        return withinScatterCaps(board) && withinWildCaps(board);
    }

    public static boolean withinFreeScatterCap(LuckyPandaBoard board) {
        return board != null && board.scatterTokens() <= FREE_SCATTER_MAX_BLOCKS;
    }
}
