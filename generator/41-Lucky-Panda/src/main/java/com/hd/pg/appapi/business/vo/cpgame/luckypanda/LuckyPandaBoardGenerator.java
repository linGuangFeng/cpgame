package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Random;
import java.util.Set;

/**
 * Board sampling and cascade refill only. No award math. Four weight scenes are distinct.
 * Inner-main stack heights are per-entry from training holdout-excluded captures, not a
 * single mixed table and not independent per-cell draws on refill.
 * <p>
 * Adjacent same-symbol stacks stay separate RLE blocks (capture has {@code 2K,2K}).
 * Cascade refill must not merge into surviving framed stacks, or the long frame grows up.
 * <p>
 * Help long-frame: winning silver becomes a random gold-framed paying symbol; winning
 * gold becomes Wild (gold Pan stays Pan — 5/5 capture exceptions). Non-winning frames
 * ride survivors. Help Wild only on reels 2-5; never on the inner top overlay.
 * <p>
 * Special-entry Scatter *10 applies only to the first trigger token in each column;
 * later tokens in that column use the ordinary weight.
 */
public final class LuckyPandaBoardGenerator {
    public static final int COLUMN_FIRST_TRIGGER_BOOST = 10;
    private static final int SAMPLE_ATTEMPTS = 80;
    private static final int FRAME_NONE = 0;
    private static final int FRAME_SILVER = 1;
    private static final int FRAME_GOLD = 2;
    private static final int SCAT_INDEX = LuckyPandaSymbol.SCAT.ordinal();

    private final Random random;
    private final Map<WeightScene, int[]> weights;
    private final Map<WeightScene, int[]> heights;
    private final boolean boostFirstColumnScatter;

    public record CascadeResult(LuckyPandaBoard board, LuckyPandaFrameAssigner.Frames frames) { }

    public LuckyPandaBoardGenerator(Random random, Map<WeightScene, int[]> weights) {
        this(random, weights, false);
    }

    public LuckyPandaBoardGenerator(Random random, Map<WeightScene, int[]> weights,
                                    boolean boostFirstColumnScatter) {
        if (random == null) throw new IllegalArgumentException("random is required");
        if (weights == null) throw new IllegalArgumentException("weights are required");
        this.random = random;
        this.boostFirstColumnScatter = boostFirstColumnScatter;
        EnumMap<WeightScene, int[]> copy = new EnumMap<>(WeightScene.class);
        EnumMap<WeightScene, int[]> heightCopy = new EnumMap<>(WeightScene.class);
        for (WeightScene scene : WeightScene.values()) {
            int[] table = weights.get(scene);
            if (table == null || table.length != LuckyPandaSymbol.values().length) {
                throw new IllegalArgumentException("missing weights for " + scene);
            }
            int total = 0;
            for (int value : table) {
                if (value < 0) throw new IllegalArgumentException(scene + " weight must be >= 0");
                total = Math.addExact(total, value);
            }
            if (total <= 0) throw new IllegalArgumentException(scene + " weight total must be > 0");
            copy.put(scene, table.clone());
            heightCopy.put(scene, LuckyPandaHeightModel.innerMainHeights(scene));
        }
        this.weights = Map.copyOf(copy);
        this.heights = Map.copyOf(heightCopy);
    }

    LuckyPandaBoardGenerator independentCopy() {
        return new LuckyPandaBoardGenerator(new java.security.SecureRandom(), weights, boostFirstColumnScatter);
    }

    String lossConfigurationKey() {
        StringBuilder key = new StringBuilder();
        key.append(boostFirstColumnScatter);
        for (WeightScene scene : WeightScene.values()) key.append(scene).append(java.util.Arrays.toString(weights.get(scene)));
        return key.toString();
    }

    LuckyPandaBoard lossSeed(WeightScene scene) {
        LuckyPandaBoard board = sampleBoard(scene);
        List<String> rskl = new ArrayList<>();
        for (List<LuckyPandaToken> reel : board.reels()) {
            for (LuckyPandaToken token : reel) {
                LuckyPandaSymbol symbol = token.symbol().paying()
                        ? token.symbol() : nextNonScatterNonWild(scene);
                rskl.add(token.height() + symbol.wireName());
            }
        }
        return LuckyPandaBoard.fromRskl(rskl);
    }

    public LuckyPandaBoard generate(WeightScene scene) {
        return generate(scene, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS, true);
    }

    public LuckyPandaBoard generate(WeightScene scene, int maxScatterTokens, boolean allowWild) {
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            LuckyPandaBoard board = sampleBoard(scene);
            if (acceptable(board, maxScatterTokens, allowWild)) return board;
        }
        throw new IllegalStateException("unable to sample a board within captured symbol caps");
    }

    public LuckyPandaBoard cascade(LuckyPandaBoard board, LuckyPandaEvaluation evaluation, WeightScene refill) {
        return cascade(board, evaluation, refill, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS, true,
                List.of(), List.of()).board();
    }

    public LuckyPandaBoard cascade(LuckyPandaBoard board, LuckyPandaEvaluation evaluation, WeightScene refill,
                                   int maxScatterTokens, boolean allowWild) {
        return cascade(board, evaluation, refill, maxScatterTokens, allowWild, List.of(), List.of()).board();
    }

    public CascadeResult cascade(LuckyPandaBoard board, LuckyPandaEvaluation evaluation, WeightScene refill,
                                 int maxScatterTokens, boolean allowWild,
                                 List<Integer> gfl, List<Integer> sfl) {
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            try {
                CascadeResult next = buildCascade(board, evaluation, refill, gfl, sfl);
                if (acceptable(next.board(), maxScatterTokens, allowWild)) return next;
            } catch (IllegalArgumentException ignored) {
                // rebuilt long-frame coords failed validation; resample refill
            }
        }
        throw new IllegalStateException("cascade refill exceeded captured symbol caps");
    }

    private static boolean acceptable(LuckyPandaBoard board, int maxScatterTokens, boolean allowWild) {
        if (!GameRuleCore.withinCapturedCaps(board)) return false;
        if (board.scatterTokens() > maxScatterTokens) return false;
        if (!allowWild && board.tokens(LuckyPandaSymbol.WILD) > 0) return false;
        return !hasWildOnOuterOrTop(board);
    }

    private static boolean hasWildOnOuterOrTop(LuckyPandaBoard board) {
        for (LuckyPandaToken token : board.reel(0)) {
            if (token.symbol() == LuckyPandaSymbol.WILD) return true;
        }
        for (LuckyPandaToken token : board.reel(5)) {
            if (token.symbol() == LuckyPandaSymbol.WILD) return true;
        }
        for (int reel = 1; reel <= 4; reel++) {
            LuckyPandaToken top = board.reel(reel).get(0);
            if (top.top() && top.symbol() == LuckyPandaSymbol.WILD) return true;
        }
        return false;
    }

    private CascadeResult buildCascade(LuckyPandaBoard board, LuckyPandaEvaluation evaluation,
                                       WeightScene refill, List<Integer> prevGfl, List<Integer> prevSfl) {
        Set<Integer> winning = winningCoords(evaluation);
        Set<Integer> silver = new HashSet<>(prevSfl == null ? List.of() : prevSfl);
        Set<Integer> gold = new HashSet<>(prevGfl == null ? List.of() : prevGfl);
        List<List<FramedToken>> next = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<FramedToken> transformed = transformReel(board.reel(reel), winning, silver, gold, refill);
            next.add(fallFramed(reel, transformed, refill));
        }
        List<String> rskl = new ArrayList<>();
        for (List<FramedToken> column : next) {
            for (FramedToken token : column) rskl.add(token.height() + token.symbol().wireName());
        }
        LuckyPandaBoard rebuilt = LuckyPandaBoard.fromRskl(rskl);
        List<Integer> persistGold = new ArrayList<>();
        List<Integer> persistSilver = new ArrayList<>();
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<FramedToken> column = next.get(reel);
            List<LuckyPandaToken> rebuiltCol = rebuilt.reel(reel);
            if (column.size() != rebuiltCol.size()) {
                throw new IllegalStateException("cascade token count mismatch on reel " + reel);
            }
            for (int i = 0; i < column.size(); i++) {
                LuckyPandaToken token = rebuiltCol.get(i);
                int frame = column.get(i).frame();
                if (token.top() || token.height() < 2 || token.reel() < 1 || token.reel() > 4) continue;
                if (frame == FRAME_GOLD) persistGold.add(token.coord());
                else if (frame == FRAME_SILVER) persistSilver.add(token.coord());
            }
        }
        LuckyPandaFrameAssigner.Frames frames = LuckyPandaFrameAssigner.persist(
                rebuilt, persistGold, persistSilver);
        return new CascadeResult(rebuilt, frames);
    }

    private List<FramedToken> transformReel(List<LuckyPandaToken> tokens, Set<Integer> winning,
                                            Set<Integer> silver, Set<Integer> gold, WeightScene refill) {
        List<FramedToken> kept = new ArrayList<>();
        for (LuckyPandaToken token : tokens) {
            int frame = gold.contains(token.coord()) ? FRAME_GOLD
                    : silver.contains(token.coord()) ? FRAME_SILVER : FRAME_NONE;
            boolean win = winning.contains(token.coord());
            if (win && frame == FRAME_SILVER) {
                LuckyPandaSymbol symbol = nextNonScatterNonWild(refill);
                kept.add(new FramedToken(symbol, token.height(), FRAME_GOLD, token.top()));
            } else if (win && frame == FRAME_GOLD) {
                // 178/183 gold wins became same-height Wild. 5/5 remaining were gold Pan and stayed Pan.
                boolean keepPan = token.symbol() == LuckyPandaSymbol.PAN;
                LuckyPandaSymbol symbol = keepPan ? LuckyPandaSymbol.PAN : LuckyPandaSymbol.WILD;
                int outFrame = keepPan ? FRAME_GOLD : FRAME_NONE;
                kept.add(new FramedToken(symbol, token.height(), outFrame, token.top()));
            } else if (!win) {
                kept.add(new FramedToken(token.symbol(), token.height(), frame, token.top()));
            }
        }
        return kept;
    }

    private List<FramedToken> fallFramed(int reel, List<FramedToken> kept, WeightScene refill) {
        if (reel == 0 || reel == 5) {
            int used = 0;
            for (FramedToken token : kept) used += token.height();
            int holes = LuckyPandaBoard.ROW_COUNTS[reel] - used;
            List<FramedToken> column = new ArrayList<>(kept.size() + holes);
            for (int i = 0; i < holes; i++) {
                column.add(new FramedToken(nextSymbol(refill, reel, false, false), 1, FRAME_NONE, false));
            }
            column.addAll(kept);
            return column;
        }
        FramedToken top;
        List<FramedToken> main = new ArrayList<>();
        if (!kept.isEmpty() && kept.get(0).top()) {
            top = kept.get(0);
            main.addAll(kept.subList(1, kept.size()));
        } else {
            top = new FramedToken(nextSymbol(refill, reel, true, false), 1, FRAME_NONE, true);
            main.addAll(kept);
        }
        int used = 0;
        for (FramedToken token : main) used += token.height();
        int holes = 5 - used;
        List<FramedToken> column = new ArrayList<>();
        column.add(top);
        column.addAll(fillInnerHoles(holes, refill));
        column.addAll(main);
        return column;
    }

    private List<FramedToken> fillInnerHoles(int holes, WeightScene scene) {
        List<FramedToken> filled = new ArrayList<>();
        int placed = 0;
        while (placed < holes) {
            int height = nextInnerHeight(holes - placed, scene);
            LuckyPandaSymbol symbol = nextSymbol(scene);
            filled.add(new FramedToken(symbol, height, FRAME_NONE, false));
            placed += height;
        }
        return filled;
    }

    private LuckyPandaBoard sampleBoard(WeightScene scene) {
        List<String> rskl = new ArrayList<>();
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            boolean seenTrigger = false;
            if (reel == 0 || reel == 5) {
                for (int row = 0; row < LuckyPandaBoard.ROW_COUNTS[reel]; row++) {
                    LuckyPandaSymbol symbol = nextSymbol(scene, reel, false, seenTrigger);
                    if (symbol == LuckyPandaSymbol.SCAT) seenTrigger = true;
                    rskl.add("1" + symbol.wireName());
                }
            } else {
                LuckyPandaSymbol top = nextSymbol(scene, reel, true, seenTrigger);
                if (top == LuckyPandaSymbol.SCAT) seenTrigger = true;
                rskl.add("1" + top.wireName());
                int filled = 1;
                while (filled < LuckyPandaBoard.ROW_COUNTS[reel]) {
                    int remaining = LuckyPandaBoard.ROW_COUNTS[reel] - filled;
                    int height = nextInnerHeight(remaining, scene);
                    LuckyPandaSymbol symbol = nextSymbol(scene, reel, false, seenTrigger);
                    if (symbol == LuckyPandaSymbol.SCAT) seenTrigger = true;
                    rskl.add(height + symbol.wireName());
                    filled += height;
                }
            }
        }
        return LuckyPandaBoard.fromRskl(rskl);
    }

    private int nextInnerHeight(int remaining, WeightScene scene) {
        if (remaining <= 1) return 1;
        int[] table = heights.get(scene);
        int usable = Math.min(4, remaining);
        int total = 0;
        for (int i = 0; i < usable; i++) total += table[i];
        int pick = random.nextInt(total);
        for (int i = 0; i < usable; i++) {
            pick -= table[i];
            if (pick < 0) return i + 1;
        }
        return 1;
    }

    public LuckyPandaSymbol nextSymbol(WeightScene scene) {
        return pickSymbol(scene, false);
    }

    LuckyPandaSymbol nextSymbol(WeightScene scene, int reel, boolean innerTop, boolean columnHasTrigger) {
        boolean banWild = innerTop || reel == 0 || reel == 5;
        if (!banWild) return pickSymbol(scene, columnHasTrigger);
        for (int i = 0; i < 40; i++) {
            LuckyPandaSymbol symbol = pickSymbol(scene, columnHasTrigger);
            if (symbol != LuckyPandaSymbol.WILD) return symbol;
        }
        return nextNonScatterNonWild(scene);
    }

    private LuckyPandaSymbol pickSymbol(WeightScene scene, boolean columnHasTrigger) {
        int[] table = weights.get(scene);
        int total = 0;
        for (int i = 0; i < table.length; i++) total += scatterWeight(scene, table[i], i, columnHasTrigger);
        int pick = random.nextInt(total);
        LuckyPandaSymbol[] values = LuckyPandaSymbol.values();
        for (int i = 0; i < values.length; i++) {
            pick -= scatterWeight(scene, table[i], i, columnHasTrigger);
            if (pick < 0) return values[i];
        }
        return LuckyPandaSymbol.T;
    }

    private int scatterWeight(WeightScene scene, int weight, int index, boolean columnHasTrigger) {
        if (index != SCAT_INDEX) return weight;
        if (!boostFirstColumnScatter || scene != WeightScene.PAID_START || columnHasTrigger) return weight;
        return Math.multiplyExact(weight, COLUMN_FIRST_TRIGGER_BOOST);
    }

    public LuckyPandaSymbol nextNonScatterNonWild(WeightScene scene) {
        LuckyPandaSymbol symbol;
        do {
            symbol = nextSymbol(scene);
        } while (symbol == LuckyPandaSymbol.SCAT || symbol == LuckyPandaSymbol.WILD);
        return symbol;
    }

    private static Set<Integer> winningCoords(LuckyPandaEvaluation evaluation) {
        HashSet<Integer> coords = new HashSet<>();
        for (LuckyPandaWin win : evaluation.wins()) {
            for (List<Integer> column : win.wmkl()) coords.addAll(column);
        }
        return coords;
    }

    private record FramedToken(LuckyPandaSymbol symbol, int height, int frame, boolean top) { }
}
