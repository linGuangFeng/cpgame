package com.cpgame.glacier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.cpgame.glacier.GameRuleCore.SCATTER;
import static com.cpgame.glacier.GameRuleCore.WILD;
import static com.cpgame.glacier.GameRuleCore.Symbol;
import static com.cpgame.glacier.GameRuleCore.Board;
import static com.cpgame.glacier.GameRuleCore.Evaluation;

/** Samples boards and cascade refills from the 1780 empirical model. */
public final class BoardFactory {
    private final Random random;
    private final GenerationModel model;
    private int nextId = 1;

    public BoardFactory(Random random) {
        this(random, new GenerationModel());
    }

    public BoardFactory(Random random, GenerationModel model) {
        this.random = random;
        this.model = model;
    }

    public void resetIds() { nextId = 1; }

    public Board initial(boolean free, boolean specialOpening) {
        int[] ordinary = free ? model.freeInitial : model.paidInitial;
        int[] first = specialOpening && !free ? model.boostScatter(ordinary) : ordinary;
        for (int attempt = 0; attempt < 80; attempt++) {
            resetIds();
            Board board = build(first, ordinary, free);
            if (legal(board, free)) return board;
        }
        throw new CompleteRoundFactory.RoundRejectedException("cannot sample initial board inside captured caps");
    }

    Board lossSeed() { resetIds(); return build(model.paidInitial, model.paidInitial, false); }

    public Board featureTrigger() {
        for (int attempt = 0; attempt < 200; attempt++) {
            Board board = initial(false, true);
            if (new GameRuleCore().scatterSymbolCount(board) >= 4 && legal(board, false)) return board;
        }
        Board base = initial(false, false);
        return forceScatterSymbols(base, 4);
    }

    public Board cascade(Board board, Evaluation evaluation, boolean free) {
        Map<Integer, Symbol> transformed = new HashMap<>();
        for (Symbol s : board.symbols()) {
            if (!evaluation.winningIds().contains(s.id())) continue;
            if (s.frame() == 1) transformed.put(s.id(), s.asGold(model.goldProp(random)));
            else if (s.frame() == 2) transformed.put(s.id(), s.asWild());
        }
        GameRuleCore core = new GameRuleCore();
        var cols = new ArrayList<List<Symbol>>();
        List<Symbol> hs = core.survivors(board.horizontal(), evaluation.winningIds(), transformed);
        int[] weights = free ? model.freeRefill : model.paidRefill;
        for (int c = 0; c < 6; c++) {
            List<Symbol> survivors = core.survivors(board.columns().get(c), evaluation.winningIds(), transformed);
            int used = survivors.stream().mapToInt(Symbol::grid).sum();
            int fill = GameRuleCore.HEIGHT - used;
            List<GenerationModel.Segment> segs = model.refillSegments(fill);
            var col = new ArrayList<>(survivors);
            for (GenerationModel.Segment seg : segs) {
                col.add(refillSymbol(c, weights, free, col, cols, hs));
            }
            cols.add(col);
        }
        while (hs.size() < 4) {
            hs.add(horizontalSymbol(weights, free, cols, hs));
        }
        Board packed = new Board(cols, hs);
        return core.continueIds(board, packed, evaluation.winningIds());
    }

    public Board cascadeLegal(Board board, Evaluation evaluation, boolean free) {
        for (int i = 0; i < 80; i++) {
            Board next = cascade(board, evaluation, free);
            if (legal(next, free)) return next;
        }
        throw new CompleteRoundFactory.RoundRejectedException("cannot refill inside captured caps");
    }

    private Board build(int[] firstWeights, int[] restWeights, boolean free) {
        var cols = new ArrayList<List<Symbol>>();
        for (int c = 0; c < 6; c++) {
            List<GenerationModel.Segment> segs = (c == 0 || c == 5)
                ? List.of(new GenerationModel.Segment(1, false), new GenerationModel.Segment(1, false),
                    new GenerationModel.Segment(1, false), new GenerationModel.Segment(1, false),
                    new GenerationModel.Segment(1, false))
                : model.innerStructure(random);
            var col = new ArrayList<Symbol>();
            var others = new ArrayList<List<Symbol>>(cols);
            boolean seenTrigger = false;
            for (GenerationModel.Segment seg : segs) {
                int[] weights = seenTrigger ? restWeights : firstWeights;
                Symbol symbol = newSymbol(c, seg, weights, free, col, others);
                if (symbol.prop() == SCATTER) seenTrigger = true;
                col.add(symbol);
            }
            cols.add(col);
        }
        var hs = new ArrayList<Symbol>();
        for (int i = 0; i < 4; i++) {
            int reel = i + 1;
            boolean seen = false;
            for (Symbol symbol : cols.get(reel)) if (symbol.prop() == SCATTER) seen = true;
            hs.add(horizontalSymbol(seen ? restWeights : firstWeights, free, cols, hs));
        }
        return new Board(cols, hs);
    }

    private Symbol newSymbol(int col, GenerationModel.Segment seg, int[] weights, boolean free,
                             List<Symbol> builtCol, List<List<Symbol>> builtCols) {
        boolean edge = col == 0 || col == 5;
        for (int i = 0; i < 30; i++) {
            boolean allowScatter = canPlaceScatter(col, seg.height(), free, builtCol, builtCols, List.of());
            boolean allowWild = !edge && canPlaceWild(col, builtCol, builtCols, List.of());
            int prop = model.nextProp(random, weights, edge, allowScatter, allowWild);
            if (prop == SCATTER && seg.height() > 2) continue;
            int height = edge ? 1 : (prop == SCATTER ? Math.min(seg.height(), 2) : seg.height());
            int frame = 0;
            if (!edge && height >= 2 && prop <= 11 && seg.silver()) frame = 1;
            if (prop >= 12) frame = 0;
            if (prop == WILD && edge) continue;
            Symbol s = new Symbol(nextId++, prop, height, frame);
            if (wouldBreakCaps(col, s, free, builtCol, builtCols, List.of())) continue;
            return s;
        }
        return new Symbol(nextId++, 2, 1, 0);
    }

    /** New cascade symbols are always 1-cell unframed. */
    private Symbol refillSymbol(int col, int[] weights, boolean free,
                                List<Symbol> builtCol, List<List<Symbol>> cols, List<Symbol> hs) {
        boolean edge = col == 0 || col == 5;
        for (int i = 0; i < 30; i++) {
            boolean allowScatter = canPlaceScatter(col, 1, free, builtCol, cols, hs);
            boolean allowWild = !edge && canPlaceWild(col, builtCol, cols, hs);
            int prop = model.nextProp(random, weights, edge, allowScatter, allowWild);
            if (prop == WILD && edge) continue;
            Symbol s = new Symbol(nextId++, prop, 1, 0);
            if (wouldBreakCaps(col, s, free, builtCol, cols, hs)) continue;
            return s;
        }
        return new Symbol(nextId++, 2, 1, 0);
    }

    private Symbol horizontalSymbol(int[] weights, boolean free, List<List<Symbol>> cols, List<Symbol> hs) {
        int col = hs.size() + 1;
        for (int i = 0; i < 30; i++) {
            boolean allowScatter = canPlaceScatter(col, 1, free, List.of(), cols, hs);
            boolean allowWild = canPlaceWild(col, List.of(), cols, hs);
            int prop = model.nextProp(random, weights, false, allowScatter, allowWild);
            Symbol s = new Symbol(nextId++, prop, 1, 0);
            if (wouldBreakCaps(col, s, free, List.of(), cols, hs)) continue;
            return s;
        }
        return new Symbol(nextId++, 2, 1, 0);
    }

    private boolean canPlaceScatter(int col, int height, boolean free, List<Symbol> builtCol,
                                    List<List<Symbol>> cols, List<Symbol> hs) {
        int units = height;
        int symbols = 1;
        for (Symbol s : all(builtCol, cols, hs)) {
            if (s.prop() != SCATTER) continue;
            units += s.grid();
            symbols++;
        }
        if (units > model.maxScatterUnitsBoard) return false;
        if (free && symbols >= model.maxScatterSymbolsFree) return false;
        int colSymbols = 1;
        int reelUnits = height;
        for (Symbol s : builtCol) {
            if (s.prop() != SCATTER) continue;
            colSymbols++;
            reelUnits += s.grid();
        }
        if (colSymbols > model.maxScatterSymbolsColumn) return false;
        if (col > 0 && col < 5) {
            int hi = col - 1;
            if (hi < hs.size() && hs.get(hi).prop() == SCATTER) reelUnits += hs.get(hi).grid();
        }
        return reelUnits <= model.maxScatterUnitsReel;
    }

    private boolean canPlaceWild(int col, List<Symbol> builtCol, List<List<Symbol>> cols, List<Symbol> hs) {
        if (col == 0 || col == 5) return false;
        int board = 1;
        for (Symbol s : all(builtCol, cols, hs)) if (s.prop() == WILD) board++;
        if (board > model.maxWildBoard) return false;
        int reel = 1;
        for (Symbol s : builtCol) if (s.prop() == WILD) reel++;
        if (col > 0 && col < 5) {
            int hi = col - 1;
            if (hi < hs.size() && hs.get(hi).prop() == WILD) reel++;
        }
        return reel <= model.maxWildReel;
    }

    private boolean wouldBreakCaps(int col, Symbol s, boolean free, List<Symbol> builtCol,
                                   List<List<Symbol>> cols, List<Symbol> hs) {
        if (s.prop() == SCATTER) return !canPlaceScatter(col, s.grid(), free, builtCol, cols, hs);
        if (s.prop() == WILD) return !canPlaceWild(col, builtCol, cols, hs);
        return false;
    }

    private List<Symbol> all(List<Symbol> builtCol, List<List<Symbol>> cols, List<Symbol> hs) {
        var out = new ArrayList<Symbol>();
        if (builtCol != null) out.addAll(builtCol);
        if (cols != null) for (var c : cols) out.addAll(c);
        if (hs != null) out.addAll(hs);
        return out;
    }

    public boolean legal(Board board, boolean free) {
        if (board.scatterUnits() > model.maxScatterUnitsBoard) return false;
        if (board.wildCount() > model.maxWildBoard) return false;
        if (free && new GameRuleCore().scatterSymbolCount(board) > model.maxScatterSymbolsFree) return false;
        for (int c = 0; c < 6; c++) {
            if (board.scatterUnitsOnReel(c) > model.maxScatterUnitsReel) return false;
            if (board.wildOnReel(c) > model.maxWildReel) return false;
            int colSymbols = 0;
            for (Symbol s : board.columns().get(c)) if (s.prop() == SCATTER) colSymbols++;
            if (colSymbols > model.maxScatterSymbolsColumn) return false;
        }
        return true;
    }

    public Board forceScatterSymbols(Board board, int target) {
        GameRuleCore core = new GameRuleCore();
        int have = core.scatterSymbolCount(board);
        if (have >= target) return board;
        var cols = new ArrayList<List<Symbol>>();
        for (var col : board.columns()) cols.add(new ArrayList<>(col));
        var hs = new ArrayList<>(board.horizontal());
        int need = target - have;
        List<int[]> slots = new ArrayList<>();
        for (int c = 1; c <= 4; c++) {
            boolean colHasScatter = false;
            for (Symbol s : cols.get(c)) if (s.prop() == SCATTER) colHasScatter = true;
            for (int i = 0; i < cols.get(c).size(); i++) {
                Symbol s = cols.get(c).get(i);
                if (!colHasScatter && s.prop() != SCATTER && s.prop() != WILD && s.grid() == 1 && s.frame() == 0
                    && board.scatterUnitsOnReel(c) < model.maxScatterUnitsReel)
                    slots.add(new int[]{c, i, 0});
            }
            Symbol h = hs.get(c - 1);
            if (h.prop() != SCATTER && h.prop() != WILD)
                slots.add(new int[]{c, -1, 1});
        }
        for (int i = slots.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            var t = slots.get(i);
            slots.set(i, slots.get(j));
            slots.set(j, t);
        }
        boolean[] colHas = new boolean[6];
        for (int c = 0; c < 6; c++)
            for (Symbol s : cols.get(c)) if (s.prop() == SCATTER) colHas[c] = true;
        int placed = 0;
        for (int[] slot : slots) {
            if (placed >= need) break;
            int c = slot[0];
            if (slot[2] == 0) {
                if (colHas[c]) continue;
                Symbol old = cols.get(c).get(slot[1]);
                cols.get(c).set(slot[1], new Symbol(old.id(), SCATTER, 1, 0));
                colHas[c] = true;
            } else {
                Symbol old = hs.get(c - 1);
                hs.set(c - 1, new Symbol(old.id(), SCATTER, 1, 0));
            }
            placed++;
        }
        Board next = new Board(cols, hs);
        if (!legal(next, false) || core.scatterSymbolCount(next) < target)
            throw new IllegalStateException("cannot force scatter trigger");
        return next;
    }
}
