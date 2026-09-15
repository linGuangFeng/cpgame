package com.cpgame.christmasgift.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sole game-specific rules implementation shared by loader and controller. */
public final class GameRuleCore {
    public static final String RULES_VERSION = "1670-v40-validated-provider-joint-1";
    public static final String RULES_HASH = "58d9172c4942e29e629038fb88877ce11564375b1bd69059e5b9a38ab38df14d";
    public static final int WILD = 7;
    public static final int[][] PAYLINES = {
        {2, 5, 8}, {1, 4, 7}, {3, 6, 9}, {1, 5, 9}, {3, 5, 7}
    };
    private static final Map<Integer, Integer> PAYTABLE = Map.of(
        1, 3, 2, 5, 3, 8, 4, 10, 5, 25, 6, 100, 7, 250
    );

    public enum Mode { ORDINARY, CHRISTMAS_GIFT_FEATURE }
    public enum Outcome { LOSS, WIN, FULL_SCREEN }

    public record Deal(Map<Integer, Integer> newlyDealt) {
        public Deal {
            newlyDealt = Collections.unmodifiableMap(new LinkedHashMap<>(newlyDealt));
        }
    }

    public record CompleteRound(Mode mode, int targetSymbol, List<Deal> steps) {
        public CompleteRound {
            steps = List.copyOf(steps);
        }
    }

    public record Win(int prop, int roll, int winOdd, int multiple) {}
    public record StepEvaluation(Map<Integer, Integer> board, List<Win> wins) {}
    public record Evaluation(Mode mode, int targetSymbol, List<StepEvaluation> steps,
                             int multiplier, Outcome outcome, boolean bigWin) {}

    public Evaluation evaluate(CompleteRound round) {
        if (round == null || round.steps().isEmpty()) {
            throw new IllegalArgumentException("round/steps required");
        }
        if (round.mode() == Mode.ORDINARY) {
            return evaluateOrdinary(round);
        }
        return evaluateFeature(round);
    }

    private Evaluation evaluateOrdinary(CompleteRound round) {
        if (round.targetSymbol() != 0 || round.steps().size() != 1) {
            throw new IllegalArgumentException("ordinary requires target=0 and one step");
        }
        Map<Integer, Integer> board = applyDeal(new LinkedHashMap<>(), round.steps().get(0), false);
        requireCompleteBoard(board);
        List<Win> wins = paylines(board);
        int multiplier = wins.stream().mapToInt(Win::winOdd).sum();
        Outcome outcome = multiplier == 0 ? Outcome.LOSS : Outcome.WIN;
        return new Evaluation(round.mode(), 0,
            List.of(new StepEvaluation(immutable(board), wins)), multiplier, outcome, false);
    }

    private Evaluation evaluateFeature(CompleteRound round) {
        if (round.targetSymbol() < 1 || round.targetSymbol() > 6 || round.steps().size() < 4 || round.steps().size() > 8) {
            throw new IllegalArgumentException("feature target/step count outside observed contract");
        }
        Map<Integer, Integer> board = new LinkedHashMap<>();
        List<StepEvaluation> evaluated = new ArrayList<>();
        boolean terminalSentinel = false;
        for (int index = 0; index < round.steps().size(); index++) {
            Deal deal = round.steps().get(index);
            if (deal.newlyDealt().isEmpty()) {
                throw new IllegalArgumentException("empty feature increment");
            }
            boolean last = index == round.steps().size() - 1;
            int positive = 0;
            int zeros = 0;
            for (Map.Entry<Integer, Integer> entry : deal.newlyDealt().entrySet()) {
                int position = entry.getKey();
                int symbol = entry.getValue();
                requirePosition(position);
                if (board.containsKey(position)) {
                    throw new IllegalArgumentException("feature overwrites held position " + position);
                }
                if (symbol == 0) {
                    zeros++;
                    if (!last) throw new IllegalArgumentException("blank sentinel is terminal only");
                } else {
                    positive++;
                    if (symbol != round.targetSymbol() && symbol != WILD) {
                        throw new IllegalArgumentException("feature holds only target or Wild");
                    }
                    board.put(position, symbol);
                }
            }
            if (index == 0 && (positive < 2 || positive > 4)) {
                throw new IllegalArgumentException("initial feature state must hold 2..4 symbols");
            }
            if (index > 0 && positive > 3) {
                throw new IllegalArgumentException("newly dealt positive increment exceeds evidence");
            }
            if (zeros > 0) {
                if (zeros != 1 || positive != 0 || deal.newlyDealt().size() != 1 || board.size() == 9) {
                    throw new IllegalArgumentException("invalid terminal blank sentinel");
                }
                terminalSentinel = true;
            }
            List<Win> wins = paylines(board);
            evaluated.add(new StepEvaluation(immutable(board), wins));
        }
        if (board.size() < 9 && !terminalSentinel) {
            throw new IllegalArgumentException("non-full feature requires terminal sentinel");
        }
        if (board.size() == 9 && terminalSentinel) {
            throw new IllegalArgumentException("full feature cannot have terminal sentinel");
        }
        List<Win> finalWins = new ArrayList<>(paylines(board));
        boolean full = board.size() == 9;
        if (full) {
            List<StepEvaluation> multiplied = new ArrayList<>();
            for (StepEvaluation step : evaluated) {
                List<Win> wins = step.wins().stream()
                    .map(win -> new Win(win.prop(), win.roll(), win.winOdd(), 10)).toList();
                multiplied.add(new StepEvaluation(step.board(), wins));
            }
            evaluated = multiplied;
            finalWins = evaluated.get(evaluated.size() - 1).wins();
        }
        int lineUnits = finalWins.stream().mapToInt(Win::winOdd).sum();
        int multiplier = full ? Math.multiplyExact(lineUnits, 10) : lineUnits;
        return new Evaluation(round.mode(), round.targetSymbol(), List.copyOf(evaluated),
            multiplier, full ? Outcome.FULL_SCREEN : Outcome.WIN, full);
    }

    public static BigDecimal betGold(BigDecimal bet, int level) {
        return bet.multiply(BigDecimal.valueOf(level * 5L)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal totalWin(BigDecimal bet, int level, int multiplier) {
        return bet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(multiplier))
            .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal displayedOdds(int multiplier) {
        return BigDecimal.valueOf(multiplier).divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP)
            .stripTrailingZeros();
    }

    private static Map<Integer, Integer> applyDeal(Map<Integer, Integer> board, Deal deal, boolean allowZero) {
        for (Map.Entry<Integer, Integer> entry : deal.newlyDealt().entrySet()) {
            requirePosition(entry.getKey());
            int symbol = entry.getValue();
            if (symbol < (allowZero ? 0 : 1) || symbol > WILD) {
                throw new IllegalArgumentException("invalid symbol " + symbol);
            }
            if (board.putIfAbsent(entry.getKey(), symbol) != null) {
                throw new IllegalArgumentException("duplicate position " + entry.getKey());
            }
        }
        return board;
    }

    private static List<Win> paylines(Map<Integer, Integer> board) {
        List<Win> wins = new ArrayList<>();
        for (int line = 0; line < PAYLINES.length; line++) {
            int[] positions = PAYLINES[line];
            Integer a = board.get(positions[0]);
            Integer b = board.get(positions[1]);
            Integer c = board.get(positions[2]);
            if (a == null || b == null || c == null) continue;
            int prop = firstNonWild(a, b, c);
            if (prop == 0) prop = WILD;
            if ((a == prop || a == WILD) && (b == prop || b == WILD) && (c == prop || c == WILD)) {
                wins.add(new Win(prop, line + 1, PAYTABLE.get(prop), 1));
            }
        }
        return List.copyOf(wins);
    }

    private static int firstNonWild(int... symbols) {
        for (int symbol : symbols) if (symbol != WILD) return symbol;
        return 0;
    }

    private static void requireCompleteBoard(Map<Integer, Integer> board) {
        if (board.size() != 9) throw new IllegalArgumentException("ordinary board must contain 9 positions");
        for (int position = 1; position <= 9; position++) if (!board.containsKey(position)) {
            throw new IllegalArgumentException("missing position " + position);
        }
    }

    private static void requirePosition(int position) {
        if (position < 1 || position > 9) throw new IllegalArgumentException("invalid position " + position);
    }

    private static Map<Integer, Integer> immutable(Map<Integer, Integer> board) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(board));
    }
}
