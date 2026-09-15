package com.hd.cpgame.jungleparty;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The only result-generation core for raw gid 33. */
public final class GameRuleCore {
    public static final int RAW_GAME_ID = 33;
    public static final int REELS = 5;
    public static final int ROWS = 3;
    public static final int PAYLINES = 25;
    public static final String RULES_HASH = GenerationModel.MODEL_HASH;

    public enum Symbol { N9, A, H1, H2, H3, H4, H5, J, K, Q, T, Wild, Scat }
    public enum Scenario { RANDOM, ORDINARY_LOSS, ORDINARY_WIN, SCATTER_FREE_ROUNDS }

    public static final int[][] LINE_ROWS = {
        {1,1,1,1,1},{0,0,0,0,0},{2,2,2,2,2},{0,1,2,1,0},{2,1,0,1,2},
        {1,0,0,0,1},{1,2,2,2,1},{0,0,1,2,2},{2,2,1,0,0},{1,2,1,0,1},
        {1,0,1,2,1},{0,1,1,1,0},{2,1,1,1,2},{0,1,0,1,0},{2,1,2,1,2},
        {1,1,0,1,1},{1,1,2,1,1},{0,0,2,0,0},{2,2,0,2,2},{0,2,2,2,0},
        {2,0,0,0,2},{1,2,0,2,1},{1,0,2,0,1},{0,2,0,2,0},{2,0,2,0,2}
    };
    private static final Map<Symbol, Map<Integer,Integer>> PAYTABLE = paytable();

    private GameRuleCore() {}

    public static Round generate(SecureRandom random, Scenario requested, int betLevel, BigDecimal betSize) {
        validateBet(betLevel, betSize);
        GenerationModel.Plan plan = GenerationModel.sample(random, requested);
        Scenario scenario = plan.scenario();
        BigDecimal paidBet = money(betSize.multiply(BigDecimal.valueOf(betLevel * PAYLINES)));
        List<Delivery> deliveries = new ArrayList<>();
        if (scenario == Scenario.SCATTER_FREE_ROUNDS) {
            BigDecimal cumulative = BigDecimal.ZERO;
            Board trigger = plan.boards().get(0);
            Evaluation triggerEvaluation = evaluate(trigger, betLevel, betSize, plan.rpx());
            cumulative = cumulative.add(triggerEvaluation.award());
            int liveFsn = plan.initialFsn();
            deliveries.add(new Delivery(0, paidBet, 1, 2, liveFsn, 0, plan.rpx(),
                trigger, triggerEvaluation.wins(), triggerEvaluation.award(), money(cumulative), false));
            for (int nfsc = 1; nfsc <= plan.finalFsn(); nfsc++) {
                Board board = plan.boards().get(nfsc);
                if (scatterCount(board) >= 3) liveFsn += 8;
                Evaluation evaluation = evaluate(board, betLevel, betSize, plan.rpx());
                cumulative = cumulative.add(evaluation.award());
                deliveries.add(new Delivery(deliveries.size(), BigDecimal.ZERO, 2, 2, liveFsn, nfsc,
                    plan.rpx(), board, evaluation.wins(), evaluation.award(), money(cumulative), nfsc == plan.finalFsn()));
            }
        } else {
            Board board = plan.boards().get(0);
            Evaluation evaluation = evaluate(board, betLevel, betSize, 0);
            deliveries.add(new Delivery(0, paidBet, 1, 0, 0, 0, 0, board, evaluation.wins(),
                evaluation.award(), evaluation.award(), true));
        }
        Round round = new Round(RAW_GAME_ID, scenario, betLevel, betSize, paidBet,
            deliveries.get(deliveries.size() - 1).cumulativeAward(), List.copyOf(deliveries));
        IndependentVerifier.Verification verification = IndependentVerifier.verify(round);
        if (!verification.pass()) throw new IllegalStateException("generated Round failed verification: " + verification.errors());
        return round;
    }

    /** Re-evaluates a cached complete Round at the requested legal bet without changing any sampled state. */
    public static Round reprice(Round cached, int betLevel, BigDecimal betSize) {
        validateBet(betLevel, betSize);
        BigDecimal paidBet = money(betSize.multiply(BigDecimal.valueOf(betLevel * PAYLINES)));
        BigDecimal cumulative = BigDecimal.ZERO;
        List<Delivery> deliveries = new ArrayList<>();
        for (Delivery source : cached.deliveries()) {
            Evaluation evaluation = evaluate(source.board(), betLevel, betSize, source.rpx());
            cumulative = cumulative.add(evaluation.award());
            BigDecimal deliveryBet = source.index() == 0 ? paidBet : BigDecimal.ZERO;
            deliveries.add(new Delivery(source.index(), deliveryBet, source.gameType(), source.smallGameType(),
                source.fsn(), source.nfsc(), source.rpx(), source.board(), evaluation.wins(),
                evaluation.award(), money(cumulative), source.terminal()));
        }
        Round repriced = new Round(RAW_GAME_ID, cached.scenario(), betLevel, betSize, paidBet,
            money(cumulative), List.copyOf(deliveries));
        IndependentVerifier.Verification verification = IndependentVerifier.verify(repriced);
        if (!verification.pass()) throw new IllegalStateException("repriced Round failed verification: " + verification.errors());
        return repriced;
    }

    /** Evidence-derived 25-line evaluator; local probability selection is intentionally outside this method. */
    public static Evaluation evaluate(Board board, int betLevel, BigDecimal betSize, int rpx) {
        validateBet(betLevel, betSize);
        List<Win> wins = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int lineIndex = 0; lineIndex < LINE_ROWS.length; lineIndex++) {
            Win best = null;
            for (Symbol candidate : PAYTABLE.keySet()) {
                int count = 0;
                boolean containsWild = false;
                List<Integer> positions = new ArrayList<>();
                for (int reel = 0; reel < REELS; reel++) {
                    int row = LINE_ROWS[lineIndex][reel];
                    Symbol cell = board.at(reel, row);
                    boolean match = candidate == Symbol.Wild ? cell == Symbol.Wild : cell == candidate || cell == Symbol.Wild;
                    if (!match) break;
                    count++;
                    containsWild |= cell == Symbol.Wild;
                    positions.add(reel * ROWS + row);
                }
                Integer pay = PAYTABLE.get(candidate).get(count);
                if (pay == null) continue;
                BigDecimal award = betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(pay))
                    .multiply(BigDecimal.valueOf(Math.max(1, rpx))).multiply(BigDecimal.valueOf(containsWild ? 2 : 1));
                Win win = new Win(lineIndex + 1, candidate, count, List.copyOf(positions), money(award), containsWild);
                if (best == null || win.award().compareTo(best.award()) > 0 ||
                    (win.award().compareTo(best.award()) == 0 && win.symbol().ordinal() < best.symbol().ordinal())) best = win;
            }
            if (best != null) { wins.add(best); total = total.add(best.award()); }
        }
        return new Evaluation(List.copyOf(wins), money(total));
    }

    private static int scatterCount(Board board) { int count=0; for(Symbol symbol:board.cells())if(symbol==Symbol.Scat)count++; return count; }

    public static String externalName(Symbol symbol) { return symbol == Symbol.N9 ? "9" : symbol.name(); }
    public static Symbol parseSymbol(String value) { return "9".equals(value) ? Symbol.N9 : Symbol.valueOf(value); }
    private static void validateBet(int level, BigDecimal size) {
        if (level < 1 || level > 10) throw new IllegalArgumentException("betLevel must be 1..10");
        if (!(size.compareTo(new BigDecimal("0.02")) == 0 || size.compareTo(new BigDecimal("0.12")) == 0 || size.compareTo(new BigDecimal("0.8")) == 0))
            throw new IllegalArgumentException("betSize must be 0.02, 0.12 or 0.8");
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros(); }
    private static Map<Symbol, Map<Integer,Integer>> paytable() {
        Map<Symbol, Map<Integer,Integer>> table = new EnumMap<>(Symbol.class);
        table.put(Symbol.N9, Map.of(3,5,4,25,5,100)); table.put(Symbol.A, Map.of(3,10,4,50,5,150));
        table.put(Symbol.H1, Map.of(2,10,3,50,4,250,5,750)); table.put(Symbol.H2, Map.of(2,5,3,40,4,200,5,500));
        table.put(Symbol.H3, Map.of(3,30,4,150,5,400)); table.put(Symbol.H4, Map.of(3,25,4,100,5,250));
        table.put(Symbol.H5, Map.of(3,25,4,100,5,250)); table.put(Symbol.J, Map.of(3,5,4,25,5,100));
        table.put(Symbol.K, Map.of(3,10,4,50,5,150)); table.put(Symbol.Q, Map.of(3,5,4,25,5,100));
        table.put(Symbol.T, Map.of(3,5,4,25,5,100)); table.put(Symbol.Wild, Map.of(2,25,3,150,4,1000,5,2500));
        return Map.copyOf(table);
    }

    public record Board(Symbol[] cells) implements Serializable {
        public Board { if (cells.length != REELS * ROWS) throw new IllegalArgumentException("board must contain 15 cells"); cells = cells.clone(); }
        @Override public Symbol[] cells() { return cells.clone(); }
        public Symbol at(int reel, int row) { return cells[reel * ROWS + row]; }
        public List<String> externalCells() { return Arrays.stream(cells).map(GameRuleCore::externalName).toList(); }
    }
    public record Win(int line, Symbol symbol, int count, List<Integer> positions, BigDecimal award, boolean containsWild) implements Serializable {}
    public record Evaluation(List<Win> wins, BigDecimal award) implements Serializable {}
    public record Delivery(int index, BigDecimal paidBet, int gameType, int smallGameType, int fsn, int nfsc, int rpx,
                           Board board, List<Win> wins, BigDecimal award, BigDecimal cumulativeAward, boolean terminal) implements Serializable {}
    public record Round(int rawGameId, Scenario scenario, int betLevel, BigDecimal betSize, BigDecimal paidBet,
                        BigDecimal totalAward, List<Delivery> deliveries) implements Serializable {}
}
