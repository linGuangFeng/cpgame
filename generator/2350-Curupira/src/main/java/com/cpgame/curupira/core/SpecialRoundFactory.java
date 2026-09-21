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
        if (maxMultiplier >= 75) {
            for (int i = 0; i < 40_000; i++) {
                List<Integer> board = (i & 1) == 0 ? fourOakWinBoard() : fiveOakWinBoard();
                EvaluatedBoard evaluated = util.evaluate(board);
                if (evaluated.scatterCount() >= GameRules.SCATTER_TRIGGER) continue;
                if (!evaluated.expandingWildColumns().isEmpty() || evaluated.awards().isEmpty()) continue;
                int o = evaluated.multiplierSum();
                if (o < minMultiplier || o > maxMultiplier) continue;
                return ordinary(Kind.WIN, board);
            }
        }
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
        if (!expanding) {
            if (random.nextInt(5) == 0) {
                try {
                    return generateWinRange(125, Integer.MAX_VALUE);
                } catch (IllegalStateException ignored) {
                    // fall through to any WIN
                }
            }
            return generateWinRange(1, Integer.MAX_VALUE);
        }
        for (int i = 0; i < 20_000; i++) {
            List<Integer> board = expandingBoard();
            EvaluatedBoard evaluated = util.evaluate(board);
            if (evaluated.scatterCount() >= GameRules.SCATTER_TRIGGER) continue;
            if (evaluated.expandingWildColumns().size() != 1) continue;
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

    /**
     * 扩展 Wild：约六成做成 5 连高倍（同符号 + 额外 Wild），其余做成混色盘。
     * 5 连家族在同一倍数下靠额外 Wild 位置变化出大量牌面，例如符号 2 的 2000 倍。
     */
    private List<Integer> expandingBoard() {
        return random.nextInt(5) < 3 ? fiveOakExpandingBoard() : mixedExpandingBoard();
    }

    /** 一列整列 Wild，其余格子混色，接近原厂第 10 局。 */
    private List<Integer> mixedExpandingBoard() {
        int column = 1 + random.nextInt(4);
        List<Integer> board = new ArrayList<>(GameRules.CELL_COUNT);
        for (int i = 0; i < GameRules.CELL_COUNT; i++) {
            int col = i / GameRules.ROWS;
            if (col == column) {
                board.add(GameRules.WILD);
            } else if (col == 0) {
                board.add(symbols.nextFrom(GameRules.FIRST_REEL_SYMBOLS));
            } else {
                int draw = random.nextInt(8);
                if (draw == 0) board.add(GameRules.WILD);
                else board.add(symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS));
            }
        }
        capWildsPerColumn(board, column);
        return GameRules.atMostOneScatterPerColumn(board);
    }

    /**
     * 25 线都是同一符号 5 连：倍数 = 25 × 该符号 5 连赔付。
     * 扩展列在第 2–5 轴；其他轴最多 2 个 Wild，首轴保持赔付符号。
     */
    private List<Integer> fiveOakExpandingBoard() {
        int pay = GameRules.NON_SPECIAL_SYMBOLS.get(random.nextInt(GameRules.NON_SPECIAL_SYMBOLS.size()));
        int ewCol = 1 + random.nextInt(4);
        int[] cells = new int[GameRules.CELL_COUNT];
        for (int col = 0; col < GameRules.COLUMNS; col++) {
            int extra = (col == 0 || col == ewCol) ? 0 : random.nextInt(3);
            fillColumn(cells, col, col == ewCol ? GameRules.WILD : pay, extra);
        }
        return toList(cells);
    }

    /** 无扩展列的 5 连，倍数与 fiveOakExpanding 相同，种类是普通 WIN。 */
    private List<Integer> fiveOakWinBoard() {
        int pay = GameRules.NON_SPECIAL_SYMBOLS.get(random.nextInt(GameRules.NON_SPECIAL_SYMBOLS.size()));
        int[] cells = new int[GameRules.CELL_COUNT];
        for (int col = 0; col < GameRules.COLUMNS; col++) {
            fillColumn(cells, col, pay, col == 0 ? 0 : random.nextInt(3));
        }
        return toList(cells);
    }

    /**
     * 前 4 轴同一符号、第 5 轴换成其他赔付符号：25 线都是 4 连。
     * 符号 1 的 4 连是 2000 倍，第 5 轴与额外 Wild 位置提供组合。
     */
    private List<Integer> fourOakWinBoard() {
        int pay = GameRules.NON_SPECIAL_SYMBOLS.get(random.nextInt(GameRules.NON_SPECIAL_SYMBOLS.size()));
        List<Integer> others = new ArrayList<>();
        for (int id : GameRules.NON_SPECIAL_SYMBOLS) if (id != pay) others.add(id);
        int[] cells = new int[GameRules.CELL_COUNT];
        for (int col = 0; col < 4; col++) {
            fillColumn(cells, col, pay, col == 0 ? 0 : random.nextInt(3));
        }
        for (int row = 0; row < GameRules.ROWS; row++) {
            cells[4 * GameRules.ROWS + row] = others.get(random.nextInt(others.size()));
        }
        return toList(cells);
    }

    private void fillColumn(int[] cells, int col, int fill, int extraWilds) {
        extraWilds = Math.min(2, Math.max(0, extraWilds));
        int base = col * GameRules.ROWS;
        for (int row = 0; row < GameRules.ROWS; row++) cells[base + row] = fill;
        if (col == 0 || extraWilds == 0 || fill == GameRules.WILD) return;
        List<Integer> rows = new ArrayList<>(List.of(0, 1, 2));
        shuffle(rows);
        for (int i = 0; i < extraWilds; i++) cells[base + rows.get(i)] = GameRules.WILD;
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
                board.set(col * GameRules.ROWS + row, symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS));
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
            else if (col != 0 && random.nextInt(6) == 0) board.add(GameRules.WILD);
            else board.add(symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS));
        }
        capWildsPerColumn(board, column);
        return List.copyOf(board);
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
