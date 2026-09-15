package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single Java rules core for Loader and server-api, based only on authorized raw-gid-16 evidence. */
public final class GameRuleCore {
    public static final int RAW_GAME_ID = 16;
    public static final String GAME_NAME = "Jungle Fruit";
    public static final int COLUMNS = 6;
    public static final int ROWS = 6;
    public static final int CELLS = 36;
    public static final int WIN_COUNT_THRESHOLD = 8;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
        new BigDecimal("0.05"), new BigDecimal("0.5"), new BigDecimal("2.5"));
    public static final String RULES_VERSION = "jf16-empirical-2026-09-08-v3";
    public static final String RULES_HASH = "164088c0440871c9bc33b631a1b6bfa5e00d3012db3b0e3694bb59ac683e1743";
    public static final List<String> PAYING_SYMBOLS = List.of("A", "H1", "H2", "H3", "H4", "H5", "J", "K", "Q", "T");
    public static final List<String> MULTIPLIER_SYMBOLS = List.of("X2", "X3", "X4", "X5", "X7", "X15");
    public static final Set<String> ALL_SYMBOLS = Set.of(
        "A", "H1", "H2", "H3", "H4", "H5", "J", "K", "Q", "T", "Scat",
        "X2", "X3", "X4", "X5", "X7", "X15");

    private static final Map<String, int[]> COMPACT_PAYTABLE = compactPaytable();
    private static final Map<String, Map<Integer, Integer>> PUBLIC_PAYTABLE = publicPaytable();

    private GameRuleCore() { }

    /** Full immutable Config paytable for server Config JSON. */
    public static Map<String, Map<Integer, Integer>> symbolPayTable() { return PUBLIC_PAYTABLE; }

    public static void validateBet(BigDecimal betSize, int betLevel) {
        if (betSize == null || BET_SIZES.stream().noneMatch(value -> value.compareTo(betSize) == 0)) {
            throw new IllegalArgumentException("bet_size must be one of 0.05, 0.5, 2.5");
        }
        if (!BET_LEVELS.contains(betLevel)) throw new IllegalArgumentException("bet_level must be 1..10");
    }

    /** Config paytable units × bet_size × bet_level. */
    public static BigDecimal pay(String symbol, int symbolCount, BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        int[] values = COMPACT_PAYTABLE.get(symbol);
        if (values == null || symbolCount < WIN_COUNT_THRESHOLD) return BigDecimal.ZERO;
        int units = values[Math.min(symbolCount, 15) - WIN_COUNT_THRESHOLD];
        return betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(units))
            .stripTrailingZeros();
    }

    /** Compatibility overload for bet_level=1. */
    public static BigDecimal pay(String symbol, int symbolCount, BigDecimal betSize) {
        return pay(symbol, symbolCount, betSize, 1);
    }

    /** A paying symbol wins when its total count anywhere on the 6x6 board is at least eight. */
    public static BoardResult evaluateBoard(List<String> board, BigDecimal betSize, int betLevel) {
        requireBoard(board);
        validateBet(betSize, betLevel);
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (String symbol : PAYING_SYMBOLS) positions.put(symbol, new ArrayList<>());
        for (int index = 0; index < board.size(); index++) {
            List<Integer> symbolPositions = positions.get(board.get(index));
            if (symbolPositions != null) symbolPositions.add(protocolCoordinate(index));
        }
        List<WinMatch> matches = new ArrayList<>();
        positions.forEach((symbol, coordinates) -> {
            if (coordinates.size() >= WIN_COUNT_THRESHOLD) {
                coordinates.sort(Integer::compareTo);
                matches.add(new WinMatch(symbol, coordinates,
                    pay(symbol, coordinates.size(), betSize, betLevel)));
            }
        });
        matches.sort(Comparator.comparing(WinMatch::symbolKey));
        BigDecimal win = matches.stream().map(WinMatch::winAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        return new BoardResult(matches, win);
    }

    public static BoardResult evaluateBoard(List<String> board, BigDecimal betSize) {
        return evaluateBoard(board, betSize, 1);
    }

    /** Rebuild all derived response fields from minimal Step facts. */
    public static CompleteRound materialize(BigDecimal paidBet, BigDecimal betSize, int betLevel,
                                            List<Step> facts) {
        validateBet(betSize, betLevel);
        if (paidBet.signum() <= 0) throw new IllegalArgumentException("paid bet must be positive");
        List<Step> steps = new ArrayList<>(facts.size());
        BigDecimal phaseWin = BigDecimal.ZERO;
        BigDecimal roundPayout = BigDecimal.ZERO;
        for (int index = 0; index < facts.size(); index++) {
            Step fact = facts.get(index);
            if (fact.deliveryIndex() != index) throw new IllegalArgumentException("deliveryIndex must be contiguous");
            BoardResult board = evaluateBoard(fact.symbols(), betSize, betLevel);
            validateStateFact(facts, index, fact, board, paidBet);
            phaseWin = phaseWin.add(board.winAmount());
            List<String> xKeys = fact.symbols().stream().filter(GameRuleCore::isMultiplier).toList();
            BigDecimal stepRoundWin = phaseWin;
            if (fact.spinStatus() == 1) {
                int xSum = xKeys.stream().mapToInt(GameRuleCore::multiplierValue).sum();
                if (xSum > 0 && phaseWin.signum() > 0) stepRoundWin = phaseWin.multiply(BigDecimal.valueOf(xSum));
                roundPayout = roundPayout.add(stepRoundWin);
                phaseWin = BigDecimal.ZERO;
            }
            steps.add(new Step(index, fact.betAmount(), betSize, betLevel, fact.symbols(),
                fact.spinStatus(), fact.freeSpinNum(), fact.nowFreeSpinCount(), fact.smallGameType(),
                stepRoundWin, board.winAmount(), board.matches(), xKeys));
        }
        if (steps.isEmpty() || steps.getLast().spinStatus() != 1
            || steps.getLast().freeSpinNum() != steps.getLast().nowFreeSpinCount()) {
            throw new IllegalArgumentException("complete Round must end at spin_status=1");
        }
        RoundMode mode = classify(steps, roundPayout);
        BigDecimal multiplier = roundPayout.divide(paidBet, 8, RoundingMode.HALF_UP).stripTrailingZeros();
        return new CompleteRound(RAW_GAME_ID, mode, paidBet, betSize, betLevel, steps,
            roundPayout.stripTrailingZeros(), multiplier);
    }

    private static void validateStateFact(List<Step> facts, int index, Step fact,
                                          BoardResult board, BigDecimal paidBet) {
        int expectedStatus = board.winAmount().signum() > 0 ? 0 : 1;
        if (fact.spinStatus() != expectedStatus)
            throw new IllegalArgumentException("spin_status must agree with evidenced winning/terminal state");
        if (fact.betAmount().compareTo(index == 0 ? paidBet : BigDecimal.ZERO) != 0)
            throw new IllegalArgumentException("only initial Delivery charges the paid bet");
        int scatters = (int) fact.symbols().stream().filter("Scat"::equals).count();
        if (index == 0) {
            int grant = scatters >= 3 && scatters <= 5 ? 10 + (scatters - 3) * 2 : 0;
            if (fact.freeSpinNum() != grant || fact.nowFreeSpinCount() != 0
                || fact.smallGameType() != (grant > 0 ? 2 : 0))
                throw new IllegalArgumentException("initial state does not match Scatter trigger");
        } else {
            Step previous = facts.get(index - 1);
            if (previous.freeSpinNum() > 0) {
                int now = previous.nowFreeSpinCount() + (previous.spinStatus() == 1 ? 1 : 0);
                int grant = previous.freeSpinNum() + (fact.spinStatus() == 1 && scatters == 2 ? 5 : 0);
                if (previous.spinStatus() == 1 && previous.nowFreeSpinCount() >= previous.freeSpinNum())
                    throw new IllegalArgumentException("Delivery follows complete Free Round");
                if (fact.smallGameType() != 2 || fact.nowFreeSpinCount() != now || fact.freeSpinNum() != grant)
                    throw new IllegalArgumentException("Free ordinal/retrigger transition does not match board");
            } else if (previous.spinStatus() != 0 || fact.smallGameType() != 1
                || fact.freeSpinNum() != 0 || fact.nowFreeSpinCount() != 0) {
                throw new IllegalArgumentException("base cascade transition does not match previous Delivery");
            }
        }
    }

    /** Round payout is the sum of round_win_amount where spin_status==1. */
    public static BigDecimal evaluateRound(CompleteRound round) {
        return round.steps().stream().filter(step -> step.spinStatus() == 1)
            .map(Step::roundWinAmount).reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
    }

    public static RoundMode classify(List<Step> steps, BigDecimal payout) {
        if (steps.stream().anyMatch(step -> step.smallGameType() == 2 || step.freeSpinNum() > 0)) return RoundMode.FREE;
        if (steps.stream().anyMatch(step -> step.smallGameType() == 1)) return RoundMode.MARY;
        return payout.signum() == 0 ? RoundMode.LOSS : RoundMode.WIN;
    }

    /** Board list is column-major; wire position is column*10+row. */
    public static int protocolCoordinate(int boardIndex) {
        if (boardIndex < 0 || boardIndex >= CELLS) throw new IllegalArgumentException("board index outside 6x6");
        return (boardIndex / ROWS) * 10 + boardIndex % ROWS;
    }

    public static int boardIndex(int protocolCoordinate) {
        int column = protocolCoordinate / 10;
        int row = protocolCoordinate % 10;
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("wire position outside 6x6: " + protocolCoordinate);
        }
        return column * ROWS + row;
    }

    public static boolean isMultiplier(String symbol) { return MULTIPLIER_SYMBOLS.contains(symbol); }
    public static int multiplierValue(String symbol) {
        if (!isMultiplier(symbol)) throw new IllegalArgumentException("not an X multiplier: " + symbol);
        return Integer.parseInt(symbol.substring(1));
    }

    private static void requireBoard(List<String> board) {
        if (board == null || board.size() != CELLS) throw new IllegalArgumentException("board must contain 36 symbols");
        for (String symbol : board) if (!ALL_SYMBOLS.contains(symbol)) {
            throw new IllegalArgumentException("unknown Jungle Fruit symbol: " + symbol);
        }
    }

    private static Map<String, int[]> compactPaytable() {
        Map<String, int[]> map = new LinkedHashMap<>();
        map.put("A",  new int[]{10, 15, 20, 30, 40, 40, 50, 80});
        map.put("H1", new int[]{40, 60, 80, 100, 150, 150, 200, 300});
        map.put("H2", new int[]{30, 50, 60, 80, 100, 100, 150, 250});
        map.put("H3", new int[]{25, 40, 50, 60, 80, 80, 100, 200});
        map.put("H4", new int[]{20, 30, 40, 50, 60, 60, 80, 150});
        map.put("H5", new int[]{20, 25, 30, 40, 50, 50, 60, 100});
        map.put("J",  new int[]{6, 8, 9, 10, 15, 15, 25, 40});
        map.put("K",  new int[]{9, 10, 15, 20, 30, 30, 40, 60});
        map.put("Q",  new int[]{8, 9, 10, 15, 20, 20, 30, 50});
        map.put("T",  new int[]{5, 6, 8, 9, 10, 10, 20, 30});
        return Map.copyOf(map);
    }

    private static Map<String, Map<Integer, Integer>> publicPaytable() {
        Map<String, Map<Integer, Integer>> result = new LinkedHashMap<>();
        COMPACT_PAYTABLE.forEach((symbol, compact) -> {
            Map<Integer, Integer> sizes = new LinkedHashMap<>();
            for (int count = 8; count <= 36; count++) sizes.put(count, compact[Math.min(count, 15) - 8]);
            result.put(symbol, Map.copyOf(sizes));
        });
        Map<Integer, Integer> scatter = new LinkedHashMap<>();
        for (int count = 8; count <= 36; count++) scatter.put(count, 0);
        result.put("Scat", Map.copyOf(scatter));
        return Map.copyOf(result);
    }

    public record BoardResult(List<WinMatch> matches, BigDecimal winAmount) {
        public BoardResult {
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
        }
    }
}
