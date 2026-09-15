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
 * Help long-frame: winning silver becomes a random gold-framed paying symbol; winning
 * gold becomes Wild (gold Pan stays Pan — 5/5 capture exceptions). Non-winning frames
 * ride survivors. Help Wild only on reels 2-5; never on the inner top overlay.
 */
public final class LuckyPandaBoardGenerator {
    private static final int SAMPLE_ATTEMPTS = 80;
    private static final int FRAME_NONE = 0;
    private static final int FRAME_SILVER = 1;
    private static final int FRAME_GOLD = 2;

    private final Random random;
    private final Map<WeightScene, int[]> weights;
    private final Map<WeightScene, int[]> heights;

    public record CascadeResult(LuckyPandaBoard board, LuckyPandaFrameAssigner.Frames frames) { }

    public LuckyPandaBoardGenerator(Random random, Map<WeightScene, int[]> weights) {
        if (random == null) throw new IllegalArgumentException("random is required");
        if (weights == null) throw new IllegalArgumentException("weights are required");
        this.random = random;
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

    LuckyPandaBoardGenerator independentCopy() { return new LuckyPandaBoardGenerator(new java.security.SecureRandom(), weights); }
    String lossConfigurationKey() {
        StringBuilder key=new StringBuilder();for(WeightScene scene:WeightScene.values())key.append(scene).append(java.util.Arrays.toString(weights.get(scene)));
        return key.toString();
    }

    LuckyPandaBoard lossSeed(WeightScene scene) {
        List<List<LuckyPandaSymbol>> cells = sampleCells(scene);
        for (List<LuckyPandaSymbol> column : cells) for (int i=0;i<column.size();i++)
            if (!column.get(i).paying()) column.set(i,nextNonScatterNonWild(scene));
        return LuckyPandaBoard.fromCells(cells);
    }

    public LuckyPandaBoard generate(WeightScene scene) {
        return generate(scene, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS, true);
    }

    public LuckyPandaBoard generate(WeightScene scene, int maxScatterTokens, boolean allowWild) {
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            LuckyPandaBoard board = LuckyPandaBoard.fromCells(sampleCells(scene));
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
        List<List<FramedCell>> next = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<FramedCell> transformed = transformReel(board.reel(reel), winning, silver, gold, refill);
            next.add(fallFramed(reel, transformed, refill));
        }
        List<List<LuckyPandaSymbol>> cells = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        int[][] cellFrame = new int[LuckyPandaBoard.REEL_COUNT][];
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<FramedCell> column = next.get(reel);
            cellFrame[reel] = new int[column.size()];
            List<LuckyPandaSymbol> symbols = new ArrayList<>(column.size());
            for (int i = 0; i < column.size(); i++) {
                symbols.add(column.get(i).symbol);
                cellFrame[reel][i] = column.get(i).frame;
            }
            cells.add(symbols);
        }
        LuckyPandaBoard rebuilt = LuckyPandaBoard.fromCells(cells);
        List<Integer> persistGold = new ArrayList<>();
        List<Integer> persistSilver = new ArrayList<>();
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            int row = 0;
            for (LuckyPandaToken token : rebuilt.reel(reel)) {
                int frame = FRAME_NONE;
                for (int i = 0; i < token.height(); i++) {
                    frame = Math.max(frame, cellFrame[reel][row + i]);
                }
                row += token.height();
                if (token.top() || token.height() < 2 || token.reel() < 1 || token.reel() > 4) continue;
                if (frame == FRAME_GOLD) persistGold.add(token.coord());
                else if (frame == FRAME_SILVER) persistSilver.add(token.coord());
            }
        }
        LuckyPandaFrameAssigner.Frames frames = LuckyPandaFrameAssigner.complete(
                rebuilt, persistGold, persistSilver, random);
        return new CascadeResult(rebuilt, frames);
    }

    private List<FramedCell> transformReel(List<LuckyPandaToken> tokens, Set<Integer> winning,
                                           Set<Integer> silver, Set<Integer> gold, WeightScene refill) {
        List<FramedCell> cells = new ArrayList<>();
        for (LuckyPandaToken token : tokens) {
            int frame = gold.contains(token.coord()) ? FRAME_GOLD
                    : silver.contains(token.coord()) ? FRAME_SILVER : FRAME_NONE;
            boolean win = winning.contains(token.coord());
            if (win && frame == FRAME_SILVER) {
                LuckyPandaSymbol symbol = nextNonScatterNonWild(refill);
                for (int i = 0; i < token.height(); i++) cells.add(new FramedCell(symbol, FRAME_GOLD));
            } else if (win && frame == FRAME_GOLD) {
                // 178/183 gold wins became same-height Wild. 5/5 remaining were gold Pan and stayed Pan.
                boolean keepPan = token.symbol() == LuckyPandaSymbol.PAN;
                LuckyPandaSymbol symbol = keepPan ? LuckyPandaSymbol.PAN : LuckyPandaSymbol.WILD;
                int outFrame = keepPan ? FRAME_GOLD : FRAME_NONE;
                for (int i = 0; i < token.height(); i++) cells.add(new FramedCell(symbol, outFrame));
            } else if (win) {
                for (int i = 0; i < token.height(); i++) cells.add(null);
            } else {
                for (int i = 0; i < token.height(); i++) cells.add(new FramedCell(token.symbol(), frame));
            }
        }
        return cells;
    }

    private List<FramedCell> fallFramed(int reel, List<FramedCell> cells, WeightScene refill) {
        if (reel == 0 || reel == 5) {
            List<FramedCell> survivors = new ArrayList<>();
            for (FramedCell cell : cells) {
                if (cell != null) survivors.add(cell);
            }
            List<FramedCell> column = new ArrayList<>(cells.size());
            int holes = cells.size() - survivors.size();
            for (int i = 0; i < holes; i++) column.add(new FramedCell(nextSymbol(refill, reel, false), FRAME_NONE));
            column.addAll(survivors);
            return column;
        }
        FramedCell top = cells.get(0) == null
                ? new FramedCell(nextSymbol(refill, reel, true), FRAME_NONE)
                : cells.get(0);
        List<FramedCell> main = new ArrayList<>();
        for (int i = 1; i < cells.size(); i++) {
            if (cells.get(i) != null) main.add(cells.get(i));
        }
        int holes = (cells.size() - 1) - main.size();
        List<FramedCell> column = new ArrayList<>(cells.size());
        column.add(top);
        column.addAll(fillInnerHoles(holes, refill));
        column.addAll(main);
        return column;
    }

    private List<FramedCell> fillInnerHoles(int holes, WeightScene scene) {
        List<FramedCell> filled = new ArrayList<>(holes);
        int placed = 0;
        while (placed < holes) {
            int height = nextInnerHeight(holes - placed, scene);
            LuckyPandaSymbol symbol = nextSymbol(scene);
            for (int i = 0; i < height; i++) filled.add(new FramedCell(symbol, FRAME_NONE));
            placed += height;
        }
        return filled;
    }

    private List<List<LuckyPandaSymbol>> sampleCells(WeightScene scene) {
        List<List<LuckyPandaSymbol>> cells = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<LuckyPandaSymbol> column = new ArrayList<>(LuckyPandaBoard.ROW_COUNTS[reel]);
            if (reel == 0 || reel == 5) {
                for (int row = 0; row < LuckyPandaBoard.ROW_COUNTS[reel]; row++) {
                    column.add(nextSymbol(scene, reel, false));
                }
            } else {
                column.add(nextSymbol(scene, reel, true));
                int filled = 1;
                while (filled < LuckyPandaBoard.ROW_COUNTS[reel]) {
                    int remaining = LuckyPandaBoard.ROW_COUNTS[reel] - filled;
                    int height = nextInnerHeight(remaining, scene);
                    LuckyPandaSymbol symbol = nextSymbol(scene);
                    for (int i = 0; i < height; i++) column.add(symbol);
                    filled += height;
                }
            }
            cells.add(column);
        }
        return cells;
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
        int[] table = weights.get(scene);
        int total = 0;
        for (int value : table) total += value;
        int pick = random.nextInt(total);
        LuckyPandaSymbol[] values = LuckyPandaSymbol.values();
        for (int i = 0; i < values.length; i++) {
            pick -= table[i];
            if (pick < 0) return values[i];
        }
        return LuckyPandaSymbol.T;
    }

    LuckyPandaSymbol nextSymbol(WeightScene scene, int reel, boolean innerTop) {
        boolean banWild = innerTop || reel == 0 || reel == 5;
        if (!banWild) return nextSymbol(scene);
        for (int i = 0; i < 40; i++) {
            LuckyPandaSymbol symbol = nextSymbol(scene);
            if (symbol != LuckyPandaSymbol.WILD) return symbol;
        }
        return nextNonScatterNonWild(scene);
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

    private record FramedCell(LuckyPandaSymbol symbol, int frame) { }
}
