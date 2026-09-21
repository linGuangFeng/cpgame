package com.cpgame.curupira.core;

import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.EntryKind;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.FeatureStep.Role;
import com.cpgame.curupira.random.RandomSource;
import com.cpgame.curupira.random.WeightedSymbolSampler;
import java.util.ArrayList;
import java.util.List;

/** 按已确认线协议构造普通/特殊完整局。 */
public final class SpecialRoundFactory {
    private final WeightedSymbolSampler symbols;
    private final CandidateBoardGenerator candidates;
    private final ConstructiveLossGenerator losses;
    private final ResultUtil util;
    private final RandomSource random;
    private final RoundIdGenerator ids = new RoundIdGenerator();

    public SpecialRoundFactory(RandomSource random, GenerationPolicy policy) {
        this.random = random;
        this.util = new ResultUtil();
        this.symbols = new WeightedSymbolSampler(random, policy.symbolWeights());
        this.candidates = new CandidateBoardGenerator(symbols);
        this.losses = new ConstructiveLossGenerator(symbols, candidates, util, policy);
    }

    public CompleteRoundFact generate(Kind kind) {
        return switch (kind) {
            case LOSS -> ordinary(Kind.LOSS, losses.nextLoss().ps());
            case WIN -> generateWin(false);
            case EXPANDING_WILD -> generateWin(true);
            case TRIGGER -> generateTrigger();
            case FREE_EW -> generateFree(EntryKind.PAID, Kind.FREE_EW);
            case BUY_FE -> generateFree(EntryKind.BUY, Kind.BUY_FE);
            case HOLD -> generateHold(EntryKind.PAID, Kind.HOLD);
            case BUY_HS -> generateHold(EntryKind.BUY, Kind.BUY_HS);
        };
    }

    private CompleteRoundFact ordinary(Kind kind, List<Integer> board) {
        EvaluatedBoard evaluated = util.evaluate(board);
        if (kind == Kind.LOSS) util.assertIndependentLoss(evaluated);
        if (kind == Kind.WIN && (evaluated.awards().isEmpty() || !evaluated.expandingWildColumns().isEmpty()
                || evaluated.scatterCount() >= GameRules.SCATTER_TRIGGER)) {
            throw new IllegalStateException("WIN board failed independent check");
        }
        if (kind == Kind.EXPANDING_WILD && evaluated.expandingWildColumns().isEmpty()) {
            throw new IllegalStateException("Expanding Wild board has no triple-Wild column");
        }
        FeatureStep step = FeatureStep.symbol(Role.ORDINARY, board, evaluated, 0, 0, 0, 1, 1);
        return new CompleteRoundFact(ids.next(), kind, EntryKind.PAID, List.of(step));
    }

    public CompleteRoundFact generateWinRange(int minMultiplier, int maxMultiplier) {
        for (int i = 0; i < 80_000; i++) {
            List<Integer> board = GameRules.atMostOneScatterPerColumn(candidates.nextBoard());
            EvaluatedBoard evaluated = util.evaluate(board);
            if (evaluated.scatterCount() >= GameRules.SCATTER_TRIGGER) continue;
            if (!evaluated.expandingWildColumns().isEmpty() || evaluated.awards().isEmpty()) continue;
            int o = evaluated.multiplierSum();
            if (o < minMultiplier || o > maxMultiplier) continue;
            return ordinary(Kind.WIN, board);
        }
        throw new IllegalStateException("Unable to construct WIN in [" + minMultiplier + "," + maxMultiplier + "]");
    }

    private CompleteRoundFact generateWin(boolean expanding) {
        if (!expanding) return generateWinRange(1, Integer.MAX_VALUE);
        for (int i = 0; i < 500_000; i++) {
            List<Integer> board = GameRules.atMostOneScatterPerColumn(candidates.nextBoard());
            EvaluatedBoard evaluated = util.evaluate(board);
            if (evaluated.scatterCount() >= GameRules.SCATTER_TRIGGER) continue;
            if (evaluated.expandingWildColumns().isEmpty()) continue;
            if (evaluated.awards().isEmpty()) continue;
            return ordinary(Kind.EXPANDING_WILD, board);
        }
        throw new IllegalStateException("Unable to construct Expanding Wild board");
    }

    private CompleteRoundFact generateTrigger() {
        for (int i = 0; i < 20_000; i++) {
            List<Integer> board = scatterBoard(3);
            EvaluatedBoard evaluated = util.evaluate(board);
            if (evaluated.scatterCount() != 3 || !evaluated.expandingWildColumns().isEmpty()) continue;
            if (!evaluated.awards().isEmpty() || evaluated.multiplierSum() != 0) continue;
            FeatureStep step = FeatureStep.symbol(Role.TRIGGER, board, evaluated, 1, 1, 1, 1, 1);
            return new CompleteRoundFact(ids.next(), Kind.TRIGGER, EntryKind.PAID, List.of(step));
        }
        throw new IllegalStateException("Unable to construct non-winning scatter trigger");
    }

    private CompleteRoundFact generateFree(EntryKind entry, Kind kind) {
        List<FeatureStep> steps = new ArrayList<>(GameRules.FREE_EXPANDING_WILD_COUNT);
        for (int i = 0; i < GameRules.FREE_EXPANDING_WILD_COUNT; i++) {
            List<Integer> board = freeExpandingBoard();
            EvaluatedBoard evaluated = util.evaluate(board);
            int st = GameRules.FREE_EXPANDING_WILD_COUNT - 1 - i;
            int gt = i == 0 ? 2 : 1;
            steps.add(FeatureStep.symbol(Role.FREE_EW, board, evaluated, st, GameRules.FREE_EXPANDING_WILD_COUNT,
                    2, gt, 2));
        }
        return new CompleteRoundFact(ids.next(), kind, entry, steps);
    }

    private CompleteRoundFact generateHold(EntryKind entry, Kind kind) {
        int[] board = new int[GameRules.COIN_TOTAL_COUNT];
        int st = GameRules.HOLD_START_SPINS;
        int filled = 0;
        boolean first = true;
        List<FeatureStep> steps = new ArrayList<>();
        while (st > 0 && steps.size() < 20) {
            List<Integer> empty = new ArrayList<>();
            for (int i = 0; i < board.length; i++) if (board[i] == 0) empty.add(i);
            int add = first ? 2 + random.nextInt(3) : random.nextInt(3);
            first = false;
            add = Math.min(add, empty.size());
            List<Integer> fcn = new ArrayList<>();
            int fcnw = 0;
            shuffle(empty);
            for (int n = 0; n < add; n++) {
                int pos = empty.get(n);
                int face = 1 + random.nextInt(GameRules.COIN_MAX);
                board[pos] = face;
                fcn.add(pos);
                fcnw += face;
                filled++;
            }
            if (add == 0) st--;
            if (filled >= GameRules.COIN_TOTAL_COUNT) st = 0;
            List<Integer> cells = toList(board);
            int gt = steps.isEmpty() ? 3 : 1;
            int units = fcnw * GameRules.PAYLINE_COUNT;
            steps.add(FeatureStep.hold(cells, st, GameRules.HOLD_START_SPINS, filled, fcnw, fcn, cells, gt, units));
        }
        if (steps.isEmpty() || steps.get(steps.size() - 1).st() != 0) {
            throw new IllegalStateException("Hold & Spins did not terminate at st=0");
        }
        return new CompleteRoundFact(ids.next(), kind, entry, steps);
    }

    private void capWildsPerColumn(List<Integer> board, int reservedEw) {
        for (int col = 0; col < GameRules.COLUMNS; col++) {
            if (col == reservedEw) continue;
            List<Integer> wildRows = new ArrayList<>();
            for (int row = 0; row < GameRules.ROWS; row++) {
                if (board.get(col * GameRules.ROWS + row) == GameRules.WILD) wildRows.add(row);
            }
            while (wildRows.size() >= GameRules.ROWS) {
                int row = wildRows.remove(random.nextInt(wildRows.size()));
                int replacement = col == 0
                        ? symbols.nextFrom(GameRules.FIRST_REEL_SYMBOLS)
                        : symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS);
                board.set(col * GameRules.ROWS + row, replacement);
            }
        }
    }

    private List<Integer> scatterBoard(int count) {
        List<Integer> board = new ArrayList<>(losses.nextLoss().ps());
        for (int i = 0; i < board.size(); i++) {
            if (board.get(i) == GameRules.SCATTER) board.set(i, GameRules.NON_SPECIAL_SYMBOLS.get(i % 4));
        }
        List<Integer> reels = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        shuffle(reels);
        for (int i = 0; i < count; i++) {
            int col = reels.get(i);
            int row = random.nextInt(GameRules.ROWS);
            board.set(col * GameRules.ROWS + row, GameRules.SCATTER);
        }
        return List.copyOf(board);
    }

    private List<Integer> freeExpandingBoard() {
        int column = random.nextInt(GameRules.COLUMNS);
        List<Integer> board = new ArrayList<>(GameRules.CELL_COUNT);
        for (int i = 0; i < GameRules.CELL_COUNT; i++) {
            int col = i / GameRules.ROWS;
            if (col == column) board.add(GameRules.WILD);
            else if (col == 0) board.add(symbols.nextFrom(GameRules.FIRST_REEL_SYMBOLS));
            else board.add(symbols.next());
        }
        capWildsPerColumn(board, column);
        return capScatters(GameRules.atMostOneScatterPerColumn(board), GameRules.SCATTER_TRIGGER - 1);
    }

    private List<Integer> capScatters(List<Integer> board, int max) {
        List<Integer> copy = new ArrayList<>(board);
        int count = 0;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) != GameRules.SCATTER) continue;
            count++;
            if (count > max) copy.set(i, symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS));
        }
        return List.copyOf(copy);
    }

    private void shuffle(List<Integer> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }

    private static List<Integer> toList(int[] board) {
        List<Integer> values = new ArrayList<>(board.length);
        for (int v : board) values.add(v);
        return values;
    }
}
