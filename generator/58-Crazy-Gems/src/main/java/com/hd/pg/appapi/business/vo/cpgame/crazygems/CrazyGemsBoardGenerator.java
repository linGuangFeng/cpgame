package com.hd.pg.appapi.business.vo.cpgame.crazygems;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Deals visible Crazy Gems state only. Does not judge wins.
 * Weights are first-page cell counts from 1160 captured paid rounds (10440 cells),
 * not vendor RTP.
 */
public final class CrazyGemsBoardGenerator {
    /**
     * Order: WILD, H1, H2, H3, H4, H5, H6, H7.
     * Captured cells: 1182, 1390, 1437, 1420, 1302, 1277, 1210, 1222. Denominator 10440.
     */
    public static final int[] DEFAULT_SYMBOL_WEIGHTS = {1182, 1390, 1437, 1420, 1302, 1277, 1210, 1222};
    /** rpx 1,2,3,5,10,15 from 1160 paid first pages: 177,293,229,186,136,139. */
    public static final int[] DEFAULT_RPX_WEIGHTS = {177, 293, 229, 186, 136, 139};

    private final Random random;
    private final int[] symbolWeights;
    private final int[] rpxWeights;

    public CrazyGemsBoardGenerator() { this(new SecureRandom()); }

    public CrazyGemsBoardGenerator(Random random) {
        this(random, DEFAULT_SYMBOL_WEIGHTS, DEFAULT_RPX_WEIGHTS);
    }

    public CrazyGemsBoardGenerator(Random random, int[] symbolWeights, int[] rpxWeights) {
        if (random == null) throw new IllegalArgumentException("random is required");
        this.random = random;
        this.symbolWeights = validated(symbolWeights, CrazyGemsBoard.SYMBOLS.size(), "symbol");
        this.rpxWeights = validated(rpxWeights, CrazyGemsBoard.RPX_VALUES.length, "rpx");
    }

    public static int[] defaultSymbolWeights() { return DEFAULT_SYMBOL_WEIGHTS.clone(); }
    public static int[] defaultRpxWeights() { return DEFAULT_RPX_WEIGHTS.clone(); }

    /** Special entry: only the opening page, minecart rpx>1 weights x10. This game has no cascade or free. */
    public static int[] specialEntryRpxWeights(int[] ordinary) {
        int[] boosted = validated(ordinary, CrazyGemsBoard.RPX_VALUES.length, "rpx");
        for (int i = 0; i < CrazyGemsBoard.RPX_VALUES.length; i++) {
            if (CrazyGemsBoard.RPX_VALUES[i] > 1) {
                boosted[i] = Math.multiplyExact(boosted[i], 10);
            }
        }
        return boosted;
    }

    public CrazyGemsBoard generate() {
        String[] rskl = new String[CrazyGemsBoard.CELLS];
        for (int i = 0; i < rskl.length; i++) rskl[i] = nextSymbol();
        return new CrazyGemsBoard(rskl, nextRpx());
    }

    public CrazyGemsBoard generateWithRpx(int rpx) {
        String[] rskl = new String[CrazyGemsBoard.CELLS];
        for (int i = 0; i < rskl.length; i++) rskl[i] = nextSymbol();
        return new CrazyGemsBoard(rskl, rpx);
    }

    /**
     * Payline loss constructor: keep a natural-looking weighted board, then break every
     * winning line by changing the third reel so the line has two distinct naturals.
     */
    public CrazyGemsBoard generateIndependentLossCandidate() {
        CrazyGemsBoard board = generate();
        String[] rskl = board.rskl();
        for (int attempt = 0; attempt < 8; attempt++) {
            CrazyGemsBoard candidate = new CrazyGemsBoard(rskl, board.rpx());
            if (CrazyGemsResultUtil.evaluate(candidate).loss()) return candidate;
            breakWinningLines(rskl);
        }
        return new CrazyGemsBoard(rskl, board.rpx());
    }

    private void breakWinningLines(String[] rskl) {
        for (int[] rows : CrazyGemsBoard.PAYLINES) {
            CrazyGemsBoard current = new CrazyGemsBoard(rskl, 1);
            if (CrazyGemsResultUtil.lineSymbol(current, rows) == null) continue;
            String first = nextNatural();
            String third = nextNaturalOtherThan(first);
            rskl[rows[0]] = first;
            rskl[2 * CrazyGemsBoard.ROWS + rows[2]] = third;
        }
    }

    private String nextNatural() {
        for (int i = 0; i < 16; i++) {
            String symbol = nextSymbol();
            if (!CrazyGemsBoard.WILD.equals(symbol)) return symbol;
        }
        return "H1";
    }

    private String nextNaturalOtherThan(String forbidden) {
        for (int i = 0; i < 24; i++) {
            String symbol = nextNatural();
            if (!symbol.equals(forbidden)) return symbol;
        }
        return "H1".equals(forbidden) ? "H7" : "H1";
    }

    String nextSymbol() {
        return CrazyGemsBoard.SYMBOLS.get(pick(symbolWeights));
    }

    public int nextRpx() {
        return CrazyGemsBoard.RPX_VALUES[pick(rpxWeights)];
    }

    private int pick(int[] weights) {
        long total = 0;
        for (int weight : weights) total += weight;
        long roll = nextLong(total);
        long cursor = 0;
        for (int i = 0; i < weights.length; i++) {
            cursor += weights[i];
            if (roll < cursor) return i;
        }
        return weights.length - 1;
    }

    private long nextLong(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("weight total must be positive");
        if (random instanceof SecureRandom secure) {
            return Math.floorMod(secure.nextLong(), bound);
        }
        return random.nextLong(bound);
    }

    private static int[] validated(int[] weights, int expected, String label) {
        if (weights == null || weights.length != expected) {
            throw new IllegalArgumentException(label + " weights must contain " + expected + " entries");
        }
        int[] copy = weights.clone();
        long total = 0;
        for (int weight : copy) {
            if (weight < 0) throw new IllegalArgumentException(label + " weights must be >= 0");
            total += weight;
        }
        if (total <= 0) throw new IllegalArgumentException(label + " weight total must be positive");
        return copy;
    }
}
