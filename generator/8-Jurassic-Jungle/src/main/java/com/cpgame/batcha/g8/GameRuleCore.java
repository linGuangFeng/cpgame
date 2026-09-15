package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single Java rules core for Loader and server-api, based only on authorized raw-gid-8 evidence.
 * Board is row-major 5x5; wire coordinate is row*10+column.
 */
public final class GameRuleCore {
    public static final int RAW_GAME_ID = 8;
    public static final String GAME_NAME = "Jurassic Jungle";
    public static final int COLUMNS = 5;
    public static final int ROWS = 5;
    public static final int CELLS = 25;
    public static final int WIN_COUNT_THRESHOLD = 4;
    public static final int COLLECTOR_CAP = 70;
    public static final int EARTH_THRESHOLD = 10;
    public static final int WATER_THRESHOLD = 30;
    public static final int FIRE_THRESHOLD = 50;
    public static final int GIANT_THRESHOLD = 70;
    public static final int PAYLINES = 10;
    public static final int MAX_STEPS_OBSERVED = 47;
    public static final int MAX_WILD_INITIAL = 5;
    public static final int MAX_WILD_INITIAL_COLUMN = 3;
    public static final int MAX_WILD_ANY = 14;
    public static final int MAX_WILD_COLUMN_ANY = 4;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
        new BigDecimal("0.05"), new BigDecimal("0.5"), new BigDecimal("4"));
    public static final String RULES_VERSION = "jj8-empirical-2026-09-09-v1";
    public static final String RULES_HASH = "8dae3e502d7eb6610086d677915adb0b988b25f2b8210b378d3ba6d313518b04";
    public static final String WILD = "S1";
    public static final List<String> PAYING_SYMBOLS = List.of("S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9");
    public static final Set<String> LOW_SYMBOLS = Set.of("S6", "S7", "S8", "S9");
    public static final Set<String> HIGH_SYMBOLS = Set.of("S2", "S3", "S4", "S5");
    public static final Set<String> ALL_SYMBOLS = Set.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9");
    public static final List<Integer> WATER_WILDS = List.of(11, 13, 31, 33);

    private static final Map<String, Map<Integer, Integer>> PAYTABLE = publicPaytable();

    private GameRuleCore() { }

    public static Map<String, Map<Integer, Integer>> symbolPayTable() { return PAYTABLE; }

    public static void validateBet(BigDecimal betSize, int betLevel) {
        if (betSize == null || BET_SIZES.stream().noneMatch(value -> value.compareTo(betSize) == 0)) {
            throw new IllegalArgumentException("bet_size must be one of 0.05, 0.5, 4");
        }
        if (!BET_LEVELS.contains(betLevel)) throw new IllegalArgumentException("bet_level must be 1..10");
    }

    public static BigDecimal paidBet(BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        return betSize.multiply(BigDecimal.valueOf((long) PAYLINES * betLevel)).stripTrailingZeros();
    }

    public static BigDecimal pay(String symbol, int symbolCount, BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        Map<Integer, Integer> table = PAYTABLE.get(symbol);
        if (table == null || symbolCount < WIN_COUNT_THRESHOLD) return BigDecimal.ZERO;
        Integer units = table.get(Math.min(symbolCount, 25));
        if (units == null) return BigDecimal.ZERO;
        return betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(units))
            .stripTrailingZeros();
    }

    public static BoardResult evaluateBoard(List<String> board, BigDecimal betSize, int betLevel) {
        requireBoard(board);
        validateBet(betSize, betLevel);
        boolean[] seen = new boolean[CELLS];
        List<WinMatch> matches = new ArrayList<>();
        for (int start = 0; start < CELLS; start++) {
            if (seen[start]) continue;
            String seed = board.get(start);
            if (WILD.equals(seed)) continue;
            if (!PAYING_SYMBOLS.contains(seed)) continue;
            List<Integer> group = flood(board, start, seed, seen);
            boolean hasReal = false;
            for (int index : group) if (seed.equals(board.get(index))) { hasReal = true; break; }
            if (!hasReal || group.size() < WIN_COUNT_THRESHOLD) continue;
            List<Integer> coords = new ArrayList<>(group.size());
            for (int index : group) coords.add(protocolCoordinate(index));
            coords.sort(Comparator.reverseOrder());
            matches.add(new WinMatch(seed, coords, pay(seed, coords.size(), betSize, betLevel)));
        }
        matches.sort(Comparator
            .comparing((WinMatch match) -> match.indices().getFirst())
            .reversed()
            .thenComparing(WinMatch::symbolKey));
        BigDecimal win = matches.stream().map(WinMatch::winAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        return new BoardResult(matches, win);
    }

    public static CompleteRound materialize(BigDecimal paidBet, BigDecimal betSize, int betLevel,
                                            List<Step> facts) {
        validateBet(betSize, betLevel);
        if (paidBet.compareTo(paidBet(betSize, betLevel)) != 0) {
            throw new IllegalArgumentException("paid bet must equal 10 * bet_size * bet_level");
        }
        List<Step> steps = new ArrayList<>(facts.size());
        BigDecimal sum = BigDecimal.ZERO;
        int collected = 0;
        for (int index = 0; index < facts.size(); index++) {
            Step fact = facts.get(index);
            if (fact.deliveryIndex() != index) throw new IllegalArgumentException("deliveryIndex must be contiguous");
            requireBoard(fact.symbols());
            legalWilds(fact.symbols(), index == 0);
            BoardResult board = evaluateBoard(fact.symbols(), betSize, betLevel);
            int unique = uniqueWinningCells(board.matches()).size();
            collected = Math.min(COLLECTOR_CAP, collected + unique);
            sum = sum.add(board.winAmount());
            int expectedSg = index == 0 ? 0 : 1;
            if (fact.smallGameType() != expectedSg) {
                throw new IllegalArgumentException("small_game_type must be 0 on paid start and 1 on continuation");
            }
            if (fact.betAmount().compareTo(index == 0 ? paidBet : BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("only the paid start charges bet_amount");
            }
            verifyExtra(fact.symbols(), fact.extra());
            steps.add(new Step(index, fact.betAmount(), betSize, betLevel, fact.symbols(), fact.extra(),
                fact.spinStatus(), fact.smallGameType(), fact.removeStatus(), collected,
                board.winAmount(), sum.stripTrailingZeros(), board.matches()));
        }
        if (steps.isEmpty() || steps.getLast().spinStatus() != 1) {
            throw new IllegalArgumentException("complete Round must end at spin_status=1");
        }
        BigDecimal payout = steps.getLast().winAmountSum();
        RoundMode mode = classify(steps, payout);
        BigDecimal multiplier = payout.divide(paidBet, 8, RoundingMode.HALF_UP).stripTrailingZeros();
        int units = payout.multiply(BigDecimal.TEN).divide(paidBet, 0, RoundingMode.UNNECESSARY).intValueExact();
        return new CompleteRound(RAW_GAME_ID, mode, paidBet, betSize, betLevel, steps,
            payout.stripTrailingZeros(), multiplier, units);
    }

    public static RoundMode classify(List<Step> steps, BigDecimal payout) {
        boolean dragon = steps.stream().anyMatch(step -> step.removeStatus() > 0)
            || steps.stream().anyMatch(step -> step.winAmount().signum() == 0
                && step.spinStatus() == 0 && step.removeStatus() == 0 && step.removeNum() >= EARTH_THRESHOLD);
        if (dragon) return RoundMode.DRAGON;
        return payout.signum() == 0 ? RoundMode.LOSS : RoundMode.WIN;
    }

    public static int protocolCoordinate(int boardIndex) {
        if (boardIndex < 0 || boardIndex >= CELLS) throw new IllegalArgumentException("board index outside 5x5");
        return (boardIndex / COLUMNS) * 10 + boardIndex % COLUMNS;
    }

    public static int boardIndex(int protocolCoordinate) {
        int row = protocolCoordinate / 10;
        int column = protocolCoordinate % 10;
        if (row < 0 || row >= ROWS || column < 0 || column >= COLUMNS) {
            throw new IllegalArgumentException("wire position outside 5x5: " + protocolCoordinate);
        }
        return row * COLUMNS + column;
    }

    public static List<Integer> uniqueWinningCells(List<WinMatch> matches) {
        boolean[] seen = new boolean[CELLS];
        List<Integer> cells = new ArrayList<>();
        for (WinMatch match : matches) {
            for (int coord : match.indices()) {
                int index = boardIndex(coord);
                if (!seen[index]) {
                    seen[index] = true;
                    cells.add(index);
                }
            }
        }
        return cells;
    }

    public static List<String> cascadeRetainAndHoles(List<String> previous, List<Integer> removed) {
        requireBoard(previous);
        boolean[] gone = new boolean[CELLS];
        for (int index : removed) gone[index] = true;
        List<String> next = new ArrayList<>(CELLS);
        for (int i = 0; i < CELLS; i++) next.add(null);
        for (int col = 0; col < COLUMNS; col++) {
            List<String> keep = new ArrayList<>();
            for (int row = 0; row < ROWS; row++) {
                int index = row * COLUMNS + col;
                if (!gone[index]) keep.add(previous.get(index));
            }
            int holes = ROWS - keep.size();
            for (int k = 0; k < keep.size(); k++) next.set((holes + k) * COLUMNS + col, keep.get(k));
        }
        return next;
    }

    public static List<String> applyWaterWilds(List<String> board) {
        List<String> next = new ArrayList<>(board);
        for (int coord : WATER_WILDS) next.set(boardIndex(coord), WILD);
        return List.copyOf(next);
    }

    public static List<String> applyFireChecker(List<String> board, String symbol) {
        // Origin 1000-round capture: fire overlay is S1/S2/S3/S4/S5/S6/S7/S8; S9 never chosen.
        if ("S9".equals(symbol) || (!PAYING_SYMBOLS.contains(symbol) && !WILD.equals(symbol))) {
            throw new IllegalArgumentException("fire symbol must be an evidenced overlay symbol excluding S9");
        }
        List<String> next = new ArrayList<>(board);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                if ((row + col) % 2 != 0) continue;
                int index = row * COLUMNS + col;
                if (!WILD.equals(next.get(index))) next.set(index, symbol);
            }
        }
        return List.copyOf(next);
    }

    public static boolean wildsWithinCap(List<String> board, boolean initial) {
        requireBoard(board);
        int total = 0;
        int[] columns = new int[COLUMNS];
        for (int i = 0; i < CELLS; i++) {
            if (WILD.equals(board.get(i))) {
                total++;
                columns[i % COLUMNS]++;
            }
        }
        int colMax = 0;
        for (int count : columns) colMax = Math.max(colMax, count);
        if (initial) return total <= MAX_WILD_INITIAL && colMax <= MAX_WILD_INITIAL_COLUMN;
        return total <= MAX_WILD_ANY && colMax <= MAX_WILD_COLUMN_ANY;
    }

    public static boolean legalWilds(List<String> board, boolean initial) {
        if (!wildsWithinCap(board, initial)) throw new IllegalArgumentException("wild cap exceeded");
        return true;
    }

    public static boolean isLow(String symbol) { return LOW_SYMBOLS.contains(symbol); }
    public static boolean isHigh(String symbol) { return HIGH_SYMBOLS.contains(symbol); }

    static void requireBoard(List<String> board) {
        if (board == null || board.size() != CELLS) throw new IllegalArgumentException("board must contain 25 symbols");
        for (String symbol : board) if (!ALL_SYMBOLS.contains(symbol)) {
            throw new IllegalArgumentException("unknown Jurassic Jungle symbol: " + symbol);
        }
    }

    private static List<Integer> flood(List<String> board, int start, String symbol, boolean[] globalSeen) {
        boolean[] local = new boolean[CELLS];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        local[start] = true;
        List<Integer> group = new ArrayList<>();
        while (!queue.isEmpty()) {
            int cur = queue.removeFirst();
            String cell = board.get(cur);
            if (!symbol.equals(cell) && !WILD.equals(cell)) continue;
            group.add(cur);
            if (!WILD.equals(cell)) globalSeen[cur] = true;
            int row = cur / COLUMNS, col = cur % COLUMNS;
            offer(queue, local, row, col - 1, row * COLUMNS + (col - 1));
            offer(queue, local, row, col + 1, row * COLUMNS + (col + 1));
            offer(queue, local, row - 1, col, (row - 1) * COLUMNS + col);
            offer(queue, local, row + 1, col, (row + 1) * COLUMNS + col);
        }
        return group;
    }

    private static void offer(ArrayDeque<Integer> queue, boolean[] local, int row, int col, int index) {
        if (row < 0 || row >= ROWS || col < 0 || col >= COLUMNS || local[index]) return;
        local[index] = true;
        queue.add(index);
    }

    private static void verifyExtra(List<String> board, List<ExtraCell> extra) {
        boolean[] used = new boolean[CELLS];
        for (ExtraCell cell : extra) {
            int index = boardIndex(cell.coord());
            if (used[index]) throw new IllegalArgumentException("duplicate extra coord " + cell.coord());
            used[index] = true;
            if (!isLow(cell.oldSymbol())) throw new IllegalArgumentException("extra old symbol must be low");
            if (!isHigh(board.get(index))) {
                throw new IllegalArgumentException("giant extra target must already be a high-paying symbol");
            }
        }
    }

    private static Map<String, Map<Integer, Integer>> publicPaytable() {
        Map<String, Map<Integer, Integer>> result = new LinkedHashMap<>();
        result.put("S2", sizes(30, 40, 70, 100, 200, 300, 500, 500, 500, 1000, 1000, 2000, 2000, 2000, 5000, 5000, 5000, 10000, 10000, 10000, 10000, 20000));
        result.put("S3", sizes(20, 30, 50, 80, 100, 200, 300, 300, 300, 600, 600, 800, 800, 800, 1000, 1000, 1000, 2000, 2000, 2000, 2000, 5000));
        result.put("S4", sizes(15, 20, 40, 70, 80, 100, 200, 200, 200, 400, 400, 500, 500, 500, 800, 800, 800, 1000, 1000, 1000, 1000, 1000));
        result.put("S5", sizes(10, 15, 30, 60, 70, 80, 100, 100, 100, 300, 300, 400, 400, 400, 600, 600, 600, 800, 800, 800, 800, 800));
        result.put("S6", sizes(5, 10, 15, 20, 30, 40, 50, 50, 50, 60, 60, 100, 100, 100, 500, 500, 500, 600, 600, 600, 600, 600));
        result.put("S7", sizes(4, 6, 9, 15, 20, 30, 40, 40, 40, 50, 50, 80, 80, 80, 300, 300, 300, 400, 400, 400, 400, 500));
        result.put("S8", sizes(3, 5, 6, 10, 15, 20, 30, 30, 30, 40, 40, 60, 60, 60, 200, 200, 200, 300, 300, 300, 300, 400));
        result.put("S9", sizes(2, 3, 4, 6, 8, 10, 15, 15, 15, 20, 20, 40, 40, 40, 100, 100, 100, 200, 200, 200, 200, 300));
        return Map.copyOf(result);
    }

    private static Map<Integer, Integer> sizes(int... values) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) map.put(i + 4, values[i]);
        return Map.copyOf(map);
    }

    public record BoardResult(List<WinMatch> matches, BigDecimal winAmount) {
        public BoardResult {
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
        }
    }
}
