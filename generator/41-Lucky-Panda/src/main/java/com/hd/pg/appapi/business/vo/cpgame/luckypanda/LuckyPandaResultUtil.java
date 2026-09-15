package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Independent, deterministic award reverse-engineering. Same board + rpx + bet always
 * yields the same wmkl/wskl/wa. Does not generate boards or read Redis/fixtures.
 * <p>
 * Evidence: 3383/3383 unique deliveries (original-http + preflight round 1) match when each rskl token (including the
 * inner-reel top token) is one ways position at frontend coord {@code reel*10-style
 * ""+reel+tokenCounter}, Wild substitutes paying symbols only, and a natural of the
 * paying symbol is required.
 */
public final class LuckyPandaResultUtil {
    public static final int MIN_REELS = 3;
    public static final int MAX_REELS = 6;
    public static final int SCATTER_TRIGGER_TOKENS = 4;
    public static final int FREE_SPINS_AWARDED = 10;
    public static final int FREE_SPINS_EXTRA_PER_SCAT = 2;
    private static final Map<LuckyPandaSymbol, int[]> PAY_TABLE = payTable();

    private LuckyPandaResultUtil() { }

    private static Map<LuckyPandaSymbol, int[]> payTable() {
        Map<LuckyPandaSymbol, int[]> table = new EnumMap<>(LuckyPandaSymbol.class);
        table.put(LuckyPandaSymbol.PAN, new int[]{30, 40, 50, 80});
        table.put(LuckyPandaSymbol.H1, new int[]{20, 25, 30, 50});
        table.put(LuckyPandaSymbol.H2, new int[]{10, 25, 30, 40});
        table.put(LuckyPandaSymbol.H3, new int[]{8, 15, 20, 30});
        table.put(LuckyPandaSymbol.H4, new int[]{6, 10, 12, 15});
        table.put(LuckyPandaSymbol.H5, new int[]{6, 10, 12, 15});
        table.put(LuckyPandaSymbol.A, new int[]{4, 6, 8, 10});
        table.put(LuckyPandaSymbol.K, new int[]{4, 6, 8, 10});
        table.put(LuckyPandaSymbol.Q, new int[]{1, 2, 3, 4});
        table.put(LuckyPandaSymbol.J, new int[]{1, 2, 3, 4});
        table.put(LuckyPandaSymbol.T, new int[]{1, 2, 3, 4});
        return Map.copyOf(table);
    }

    public static LuckyPandaEvaluation evaluate(LuckyPandaBoard board, BigDecimal betSize, int betLevel, int rpx) {
        if (board == null || betSize == null || betSize.signum() <= 0 || betLevel < 1) {
            throw new IllegalArgumentException("board and positive bet are required");
        }
        if (rpx < 0) throw new IllegalArgumentException("rpx must be >= 0");
        int rpxFactor = rpx > 0 ? rpx : 1;
        List<LuckyPandaWin> wins = new ArrayList<>();
        BigDecimal wa = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) {
            if (!symbol.paying()) continue;
            List<List<Integer>> positions = new ArrayList<>();
            boolean hasNatural = false;
            for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
                List<Integer> matches = new ArrayList<>();
                for (LuckyPandaToken token : board.reel(reel)) {
                    if (token.symbol() == symbol || token.symbol() == LuckyPandaSymbol.WILD) {
                        matches.add(token.coord());
                        if (token.symbol() == symbol) hasNatural = true;
                    }
                }
                if (matches.isEmpty()) break;
                positions.add(List.copyOf(matches));
            }
            int reelCount = positions.size();
            if (reelCount < MIN_REELS || reelCount > MAX_REELS || !hasNatural) continue;
            int ways = 1;
            for (List<Integer> column : positions) ways = Math.multiplyExact(ways, column.size());
            int pay = PAY_TABLE.get(symbol)[reelCount - MIN_REELS];
            BigDecimal award = BigDecimal.valueOf((long) ways * pay * rpxFactor)
                    .multiply(betSize)
                    .multiply(BigDecimal.valueOf(betLevel))
                    .setScale(2, RoundingMode.HALF_UP);
            wins.add(new LuckyPandaWin(symbol, positions, ways, reelCount, pay, rpxFactor, award));
            wa = wa.add(award);
        }
        return new LuckyPandaEvaluation(wins, wa, rpx, rpxFactor, board.scatterTokens(), wins.isEmpty());
    }

    /**
     * Scatter-free (mali) trigger is the paid tumble terminal: ss=1 so this page itself
     * has wa=0. That is not "the Round must not win": 38/38 captured free Rounds still
     * have positive terminal rwa from earlier paid tumbles and/or the free spins.
     * 3 Scat blocks never triggered. Help: 4 treasures = 10 free spins, each extra +2;
     * free spins can be retriggered.
     */
    public static boolean scatterFreeTrigger(LuckyPandaEvaluation evaluation, int nfsc) {
        return nfsc == 0
                && evaluation.segmentTerminal()
                && evaluation.scatterTokens() >= SCATTER_TRIGGER_TOKENS;
    }

    /**
     * Help: 4 treasures = 10 free spins; each additional treasure +2.
     * Count RLE blocks (stacked treasure is one symbol). 5 blocks → 12.
     */
    public static int scatterFreeAwarded(int scatterBlocks) {
        if (scatterBlocks < SCATTER_TRIGGER_TOKENS) return 0;
        return FREE_SPINS_AWARDED + FREE_SPINS_EXTRA_PER_SCAT * (scatterBlocks - SCATTER_TRIGGER_TOKENS);
    }

    /** Help: "Free spins can be retriggered." Same 4-block threshold on a free tumble terminal. */
    public static boolean scatterRetrigger(LuckyPandaEvaluation evaluation) {
        return evaluation != null
                && evaluation.segmentTerminal()
                && evaluation.scatterTokens() >= SCATTER_TRIGGER_TOKENS;
    }

    public static boolean roundTerminal(int ss, int fsn, int nfsc) {
        if (ss != 1) return false;
        if (fsn == 0) return true;
        return nfsc == fsn;
    }

    public static int integerMultiplier(BigDecimal terminalRwa, BigDecimal betSize, int betLevel) {
        if (terminalRwa == null || betSize == null) throw new IllegalArgumentException("rwa/bet required");
        if (terminalRwa.signum() == 0) return 0;
        BigDecimal unit = betSize.multiply(BigDecimal.valueOf(betLevel));
        BigDecimal ratio = terminalRwa.divide(unit, 0, RoundingMode.UNNECESSARY);
        return ratio.intValueExact();
    }

    public static Map<LuckyPandaSymbol, int[]> copyPayTable() {
        Map<LuckyPandaSymbol, int[]> copy = new EnumMap<>(LuckyPandaSymbol.class);
        for (var entry : PAY_TABLE.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
        return copy;
    }
}
