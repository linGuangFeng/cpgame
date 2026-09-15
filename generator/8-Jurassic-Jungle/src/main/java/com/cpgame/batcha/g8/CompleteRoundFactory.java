package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fresh complete Rounds from measured dealing entries and the unique settlement core. */
public final class CompleteRoundFactory {
    private final ZeroLossSupport<List<String>> lossBoards;

    private final int maximumSteps;
    private final EmpiricalColumnModel model = EmpiricalColumnModel.instance();

    public CompleteRoundFactory(int maximumSteps) {
        this.maximumSteps = Math.min(GameRuleCore.MAX_STEPS_OBSERVED, Math.max(1, maximumSteps));
        SecureRandom defaultsRandom=new SecureRandom();
        lossBoards=new ZeroLossSupport<>(()->lossBoardCandidate(defaultsRandom),this::validLossBoard,List::copyOf);
    }

    public CompleteRound generate(RoundMode requested, SecureRandom random, BigDecimal betSize, int betLevel) {
        Objects.requireNonNull(random);
        GameRuleCore.validateBet(betSize, betLevel);
        if(requested==RoundMode.LOSS){
            List<String> board=lossBoards.generate(()->lossBoardCandidate(random),random::nextInt);
            BigDecimal paid=GameRuleCore.paidBet(betSize,betLevel);
            return GameRuleCore.materialize(paid,betSize,betLevel,List.of(Step.fact(0,paid,betSize,betLevel,board,List.of(),1,0,0)));
        }
        for (int attempt = 0; attempt < 40000; attempt++) {
            try {
                CompleteRound round = candidate(random, betSize, betLevel);
                if (round.mode() == requested) return round;
            } catch (Rejected ignored) {
                /* Discard complete candidate, never patch its outcome. */
            }
        }
        throw new IllegalStateException("Empirical complete-Round candidate limit exhausted for " + requested);
    }

    private CompleteRound candidate(SecureRandom random, BigDecimal betSize, int betLevel) {
        BigDecimal paid = GameRuleCore.paidBet(betSize, betLevel);
        List<String> board = initial(random);
        List<Step> facts = new ArrayList<>();
        int rs = 0;
        boolean earth = false, water = false, fire = false, giant = false;
        List<ExtraCell> pendingExtra = List.of();
        for (int guard = 0; guard < maximumSteps; guard++) {
            GameRuleCore.BoardResult result = GameRuleCore.evaluateBoard(board, betSize, betLevel);
            boolean wins = result.winAmount().signum() > 0;
            int collectedPreview = previewCollected(facts, result);
            boolean pending = nextDragon(collectedPreview, earth, water, fire, giant) != 0;
            int st = (!wins && !pending) ? 1 : 0;
            int sg = facts.isEmpty() ? 0 : 1;
            facts.add(Step.fact(facts.size(), facts.isEmpty() ? paid : BigDecimal.ZERO, betSize, betLevel,
                List.copyOf(board), pendingExtra, st, sg, rs));
            pendingExtra = List.of();
            if (st == 1) break;
            if (wins) {
                List<Integer> removed = GameRuleCore.uniqueWinningCells(result.matches());
                List<String> holes = GameRuleCore.cascadeRetainAndHoles(board, removed);
                String refillEntry = switch (rs) {
                    case 1 -> "EARTH_CASCADE_REFILL";
                    case 2 -> "WATER_CASCADE_REFILL";
                    case 3 -> "FIRE_CASCADE_REFILL";
                    case 4 -> "GIANT_REFILL";
                    default -> "BASE_REFILL";
                };
                board = fillHoles(holes, refillEntry, random);
                if (rs == 4) {
                    Transformed transformed = transformLows(board, random);
                    board = transformed.board();
                    pendingExtra = transformed.extra();
                }
            } else {
                int next = nextDragon(collectedPreview, earth, water, fire, giant);
                if (next == 1) {
                    earth = true;
                    List<Integer> lows = new ArrayList<>();
                    for (int i = 0; i < GameRuleCore.CELLS; i++) {
                        if (GameRuleCore.isLow(board.get(i))) lows.add(i);
                    }
                    List<String> holes = GameRuleCore.cascadeRetainAndHoles(board, lows);
                    board = fillHoles(holes, "EARTH_REFILL", random);
                    rs = 1;
                } else if (next == 2) {
                    water = true;
                    board = GameRuleCore.applyWaterWilds(board);
                    rs = 2;
                } else if (next == 3) {
                    fire = true;
                    board = GameRuleCore.applyFireChecker(board, model.fireSymbol(random));
                    rs = 3;
                } else if (next == 4) {
                    giant = true;
                    Transformed transformed = transformLows(board, random);
                    board = transformed.board();
                    pendingExtra = transformed.extra();
                    rs = 4;
                } else {
                    throw new Rejected();
                }
            }
            if (!GameRuleCore.wildsWithinCap(board, false)) throw new Rejected();
        }
        if (facts.isEmpty() || facts.getLast().spinStatus() != 1) throw new Rejected();
        CompleteRound round = GameRuleCore.materialize(paid, betSize, betLevel, facts);
        if (round.steps().size() > maximumSteps) throw new Rejected();
        return round;
    }

    private List<String> initial(SecureRandom random) {
        List<String> board = new ArrayList<>(GameRuleCore.CELLS);
        for (int i = 0; i < GameRuleCore.CELLS; i++) board.add(null);
        for (int col = 0; col < GameRuleCore.COLUMNS; col++) {
            List<String> strip = model.draw("PAID_INITIAL", col, 5, random);
            if (strip.size() != 5) throw new Rejected();
            for (int row = 0; row < 5; row++) board.set(row * 5 + col, strip.get(row));
        }
        if (!GameRuleCore.wildsWithinCap(board, true)) throw new Rejected();
        return board;
    }

    private List<String> fillHoles(List<String> holes, String entry, SecureRandom random) {
        List<String> board = new ArrayList<>(holes);
        for (int col = 0; col < 5; col++) {
            List<Integer> empty = new ArrayList<>();
            for (int row = 0; row < 5; row++) {
                int index = row * 5 + col;
                if (board.get(index) == null) empty.add(index);
            }
            if (empty.isEmpty()) continue;
            List<String> fill = model.draw(entry, col, empty.size(), random);
            if (fill.size() != empty.size()) throw new Rejected();
            for (int i = 0; i < empty.size(); i++) board.set(empty.get(i), fill.get(i));
        }
        for (String symbol : board) if (symbol == null) throw new Rejected();
        return board;
    }

    private Transformed transformLows(List<String> board, SecureRandom random) {
        List<String> next = new ArrayList<>(board);
        List<ExtraCell> extra = new ArrayList<>();
        for (int i = 0; i < GameRuleCore.CELLS; i++) {
            String symbol = next.get(i);
            if (!GameRuleCore.isLow(symbol)) continue;
            extra.add(new ExtraCell(GameRuleCore.protocolCoordinate(i), symbol));
            next.set(i, model.transformLow(symbol, random));
        }
        return new Transformed(List.copyOf(next), List.copyOf(extra));
    }

    private static int previewCollected(List<Step> facts, GameRuleCore.BoardResult result) {
        int prior = 0;
        for (Step fact : facts) {
            GameRuleCore.BoardResult priorBoard = GameRuleCore.evaluateBoard(fact.symbols(), fact.betSize(), fact.betLevel());
            prior = Math.min(GameRuleCore.COLLECTOR_CAP,
                prior + GameRuleCore.uniqueWinningCells(priorBoard.matches()).size());
        }
        return Math.min(GameRuleCore.COLLECTOR_CAP, prior + GameRuleCore.uniqueWinningCells(result.matches()).size());
    }

    private static int nextDragon(int collected, boolean earth, boolean water, boolean fire, boolean giant) {
        if (!earth && collected >= GameRuleCore.EARTH_THRESHOLD) return 1;
        if (!water && collected >= GameRuleCore.WATER_THRESHOLD) return 2;
        if (!fire && collected >= GameRuleCore.FIRE_THRESHOLD) return 3;
        if (!giant && collected >= GameRuleCore.GIANT_THRESHOLD) return 4;
        return 0;
    }

    private record Transformed(List<String> board, List<ExtraCell> extra) { }

    private static final class Rejected extends RuntimeException {
        private Rejected() { super(null, null, false, false); }
    }

    public List<String> lossBoardCandidate(SecureRandom random){
        List<String> board=new ArrayList<>(java.util.Collections.nCopies(25,"S2"));
        for(int c=0;c<5;c++){
            List<String> strip=model.draw("PAID_INITIAL",c,5,random);
            for(int r=0;r<5;r++)board.set(r*5+c,strip.get(r));
        }
        // Orthogonal cluster game: each placed natural symbol differs from already placed neighbours.
        for(int r=0;r<5;r++)for(int c=0;c<5;c++){
            int i=r*5+c;String symbol=board.get(i);
            if(symbol.equals(GameRuleCore.WILD)||(r>0&&symbol.equals(board.get(i-5)))||(c>0&&symbol.equals(board.get(i-1)))){
                List<String> allowed=new ArrayList<>();for(String v:GameRuleCore.PAYING_SYMBOLS)
                    if((r==0||!v.equals(board.get(i-5)))&&(c==0||!v.equals(board.get(i-1))))allowed.add(v);
                board.set(i,allowed.get(random.nextInt(allowed.size())));
            }
        }
        return List.copyOf(board);
    }
    private boolean validLossBoard(List<String> b){return GameRuleCore.wildsWithinCap(b,true)
        &&GameRuleCore.evaluateBoard(b,new BigDecimal("0.05"),1).winAmount().signum()==0;}
}
