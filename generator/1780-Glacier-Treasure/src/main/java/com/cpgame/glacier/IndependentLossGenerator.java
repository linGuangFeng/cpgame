package com.cpgame.glacier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.cpgame.glacier.GameRuleCore.SCATTER;
import static com.cpgame.glacier.GameRuleCore.WILD;
import static com.cpgame.glacier.GameRuleCore.Symbol;
import static com.cpgame.glacier.GameRuleCore.Board;

/**
 * Independent 0-pay constructor. Ways require a left-to-right run of 3 reels, so this
 * replaces reel-3 (0-based reel 2) symbols that would complete a match, and caps Scatter
 * below the free trigger. ResultUtil must still report 0.
 */
public final class IndependentLossGenerator {
    private final Random random;
    private final BoardFactory boards;
    private final GenerationModel model;
    private final GameRuleCore core = new GameRuleCore();
    private final ResultUtil oracle = new ResultUtil();
    private final int attempts;

    public IndependentLossGenerator(Random random) {
        this(random, new GenerationModel(), 200);
    }

    public IndependentLossGenerator(Random random, GenerationModel model, int attempts) {
        this.random = random;
        this.boards = new BoardFactory(random, model); this.model=model;
        this.attempts = 5;
    }

    private static final java.util.Map<Integer,java.util.Map<Integer,Integer>> PAYTABLE = ResultUtil.paytable();
    private static final List<Board> DEFAULT_LOSSES = createDefaults();
    private static List<Board> createDefaults() {
        IndependentLossGenerator generator = new IndependentLossGenerator(new java.security.SecureRandom());
        var result = new ArrayList<Board>(10);
        for (int i=0;i<10;i++) {
            Board board = generator.generateCandidate();
            if (!generator.isLoss(board)) throw new ExceptionInInitializerError("invalid loss default");
            result.add(board);
        }
        return List.copyOf(result);
    }
    public Board generateCandidate() { return mutate(new BoardFactory(random,model).lossSeed()); }
    private boolean isLoss(Board candidate) {
        if (!boards.legal(candidate, false) || core.initialFreeAward(core.scatterSymbolCount(candidate)) > 0) return false;
        var eval = core.evaluate(candidate, BigDecimal.ONE, 1);
        oracle.verify(eval, oracle.evaluate(candidate, BigDecimal.ONE, 1, PAYTABLE));
        return eval.terminal() && eval.total().signum() == 0;
    }
    public Board generate() {
        return generateWithCandidates(() -> generateCandidate());
    }

    Board generateWithCandidates(java.util.function.Supplier<Board> proposals) {
        for (int attempt=0;attempt<5;attempt++) {
            try {
                Board candidate = proposals.get();
                if (candidate != null && isLoss(candidate)) return candidate;
            } catch (CompleteRoundFactory.RoundRejectedException rejected) { }
        }
        return DEFAULT_LOSSES.get(random.nextInt(10));
    }

    private Board mutate(Board board) {
        var cols = new ArrayList<List<Symbol>>();
        for (var col : board.columns()) cols.add(new ArrayList<>(col));
        var hs = new ArrayList<>(board.horizontal());
        replaceWildsOnFirstTwo(cols, hs);
        limitScatter(cols, hs);
        boolean[] first = matching(cols, hs, 0);
        boolean[] second = matching(cols, hs, 1);
        boolean[] forbidden = new boolean[12];
        boolean any = false;
        for (int p = 1; p <= 11; p++) {
            forbidden[p] = first[p] && second[p];
            any |= forbidden[p];
        }
        for (int i = 0; i < cols.get(2).size(); i++) {
            Symbol s = cols.get(2).get(i);
            cols.get(2).set(i, safe(s, forbidden, any));
        }
        hs.set(1, safe(hs.get(1), forbidden, any));
        return new Board(cols, hs);
    }

    private void replaceWildsOnFirstTwo(List<List<Symbol>> cols, List<Symbol> hs) {
        for (int c = 0; c <= 1; c++) {
            for (int i = 0; i < cols.get(c).size(); i++) {
                Symbol s = cols.get(c).get(i);
                if (s.prop() == WILD) cols.get(c).set(i, new Symbol(s.id(), ordinary(), s.grid(), 0));
            }
        }
        if (hs.get(0).prop() == WILD) hs.set(0, new Symbol(hs.get(0).id(), ordinary(), 1, 0));
    }

    private void limitScatter(List<List<Symbol>> cols, List<Symbol> hs) {
        int kept = 0;
        for (int c = 0; c < 6; c++) {
            for (int i = 0; i < cols.get(c).size(); i++) {
                Symbol s = cols.get(c).get(i);
                if (s.prop() != SCATTER) continue;
                if (++kept > 3) cols.get(c).set(i, new Symbol(s.id(), ordinary(), s.grid(), 0));
            }
        }
        for (int i = 0; i < hs.size(); i++) {
            Symbol s = hs.get(i);
            if (s.prop() != SCATTER) continue;
            if (++kept > 3) hs.set(i, new Symbol(s.id(), ordinary(), 1, 0));
        }
    }

    private boolean[] matching(List<List<Symbol>> cols, List<Symbol> hs, int reel) {
        boolean[] m = new boolean[12];
        boolean wild = false;
        for (Symbol s : cols.get(reel)) {
            if (s.prop() == WILD) wild = true;
            else if (s.prop() >= 1 && s.prop() <= 11) m[s.prop()] = true;
        }
        if (reel >= 1 && reel <= 4) {
            Symbol top = hs.get(reel - 1);
            if (top.prop() == WILD) wild = true;
            else if (top.prop() >= 1 && top.prop() <= 11) m[top.prop()] = true;
        }
        if (wild) for (int p = 1; p <= 11; p++) m[p] = true;
        return m;
    }

    private Symbol safe(Symbol s, boolean[] forbidden, boolean any) {
        boolean hit = (s.prop() >= 1 && s.prop() <= 11 && forbidden[s.prop()]) || (s.prop() == WILD && any);
        if (!hit) return s;
        return new Symbol(s.id(), allowed(forbidden), s.grid() >= 2 ? s.grid() : 1, 0);
    }

    private int allowed(boolean[] forbidden) {
        int n = 0;
        for (int p = 1; p <= 11; p++) if (!forbidden[p]) n++;
        if (n == 0) return 2;
        int pick = random.nextInt(n);
        for (int p = 1; p <= 11; p++) {
            if (forbidden[p]) continue;
            if (pick-- == 0) return p;
        }
        return 2;
    }

    private int ordinary() {
        int p;
        do { p = 2 + random.nextInt(10); } while (p == SCATTER || p == WILD);
        return p;
    }
}
