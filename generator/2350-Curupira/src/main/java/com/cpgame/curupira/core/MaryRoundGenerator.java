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

/** 按已确认状态规则生成两个 Mary 场景；不接收目标奖金或目标结构。 */
final class MaryRoundGenerator {
    private final RandomSource random;
    private final WeightedSymbolSampler symbols;
    private final ResultUtil resultUtil = new ResultUtil();
    private final RoundIdGenerator ids = new RoundIdGenerator();
    private final int holdEmptyWeight;
    private final int holdCoinWeight;

    MaryRoundGenerator(RandomSource random, WeightedSymbolSampler symbols,
                       int holdEmptyWeight, int holdCoinWeight) {
        if (holdEmptyWeight < 1 || holdCoinWeight < 1) {
            throw new IllegalArgumentException("Hold empty/coin weights must be positive");
        }
        this.random = random;
        this.symbols = symbols;
        this.holdEmptyWeight = holdEmptyWeight;
        this.holdCoinWeight = holdCoinWeight;
    }

    CompleteRoundFact freeExpandingWild() {
        List<FeatureStep> steps = new ArrayList<>(GameRules.FREE_EXPANDING_WILD_COUNT);
        for (int index = 0; index < GameRules.FREE_EXPANDING_WILD_COUNT; index++) {
            List<Integer> board = freeBoard();
            EvaluatedBoard evaluated = resultUtil.evaluate(board);
            steps.add(FeatureStep.symbol(Role.FREE_EW, board, evaluated,
                    GameRules.FREE_EXPANDING_WILD_COUNT - 1 - index,
                    GameRules.FREE_EXPANDING_WILD_COUNT, 2, index == 0 ? 2 : 1, 2));
        }
        return new CompleteRoundFact(ids.next(), Kind.FREE_EW, EntryKind.PAID, steps);
    }

    CompleteRoundFact holdAndSpins() {
        int[] cells = new int[GameRules.COIN_TOTAL_COUNT];
        int remaining = GameRules.HOLD_START_SPINS;
        int filled = 0;
        List<FeatureStep> steps = new ArrayList<>();
        while (remaining > 0) {
            List<Integer> newPositions = new ArrayList<>();
            int newValueSum = 0;
            for (int position = 0; position < cells.length; position++) {
                if (cells[position] != 0 || !coinLands()) continue;
                int value = 1 + random.nextInt(GameRules.COIN_MAX);
                cells[position] = value;
                newPositions.add(position);
                newValueSum += value;
                filled++;
            }
            int nextRemaining = remaining - 1 + newPositions.size();
            if (filled == GameRules.COIN_TOTAL_COUNT) nextRemaining = 0;
            List<Integer> snapshot = snapshot(cells);
            steps.add(FeatureStep.hold(snapshot, nextRemaining, GameRules.HOLD_START_SPINS,
                    filled, newValueSum, newPositions, snapshot, steps.isEmpty() ? 3 : 1,
                    newValueSum * GameRules.REDIS_UNITS_PER_ACTUAL_MULTIPLIER));
            remaining = nextRemaining;
        }
        return new CompleteRoundFact(ids.next(), Kind.HOLD, EntryKind.PAID, steps);
    }

    private List<Integer> freeBoard() {
        int expandingColumn = random.nextInt(GameRules.COLUMNS);
        int totalScatters = 0;
        List<Integer> board = new ArrayList<>(GameRules.CELL_COUNT);
        for (int column = 0; column < GameRules.COLUMNS; column++) {
            if (column == expandingColumn) {
                for (int row = 0; row < GameRules.ROWS; row++) board.add(GameRules.WILD);
                continue;
            }
            int columnWilds = 0;
            boolean scatterUsed = false;
            for (int row = 0; row < GameRules.ROWS; row++) {
                List<Integer> allowed = new ArrayList<>(GameRules.SYMBOL_ORDER);
                if (scatterUsed || totalScatters >= GameRules.SCATTER_TRIGGER - 1) {
                    allowed.remove(Integer.valueOf(GameRules.SCATTER));
                }
                if (columnWilds >= GameRules.ROWS - 1) {
                    allowed.remove(Integer.valueOf(GameRules.WILD));
                }
                int symbol = symbols.nextFrom(allowed);
                board.add(symbol);
                if (symbol == GameRules.SCATTER) {
                    scatterUsed = true;
                    totalScatters++;
                }
                if (symbol == GameRules.WILD) columnWilds++;
            }
        }
        return List.copyOf(board);
    }

    private boolean coinLands() {
        return random.nextInt(Math.addExact(holdEmptyWeight, holdCoinWeight)) >= holdEmptyWeight;
    }

    private static List<Integer> snapshot(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return List.copyOf(result);
    }
}
