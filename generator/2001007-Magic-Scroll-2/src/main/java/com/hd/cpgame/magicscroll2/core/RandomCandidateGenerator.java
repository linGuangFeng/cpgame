package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Produces randomized board candidates; it does not evaluate or classify their result. */
final class RandomCandidateGenerator {
    private final SecureRandom random;
    private final GenerationPolicy policy;
    private final SymbolWeightPolicy symbolWeights;
    private final FormationCodec codec = new FormationCodec();

    RandomCandidateGenerator(SecureRandom random, GenerationPolicy policy,
                             SymbolWeightPolicy symbolWeights) {
        if (random == null || policy == null || symbolWeights == null) {
            throw new IllegalArgumentException("random, policy and symbol weights are required");
        }
        this.random = random;
        this.policy = policy;
        this.symbolWeights = symbolWeights;
    }

    String terminalLoss(int activeRows, ResultUtil independentOracle, BigDecimal baseBet) {
        int attempts = policy.getConstructiveAttempts() + policy.getFallbackSamples();
        for (int i = 0; i < attempts; i++) {
            String candidate = constructiveLoss(activeRows);
            ResultUtil.Inspection inspection =
                    independentOracle.inspect(candidate, activeRows, baseBet, 1);
            if (independentOracle.isTerminalBaseStep(inspection)) return candidate;
        }
        throw new CandidateGenerationExhaustedException(
                "bounded independent LOSS generation exhausted", null);
    }

    String ordinaryWin(int activeRows) {
        int[] raw = codec.decode(constructiveLoss(activeRows));
        int symbol = regularSymbol();
        int row = random.nextInt(activeRows);
        for (int column = 0; column < 3; column++) raw[FormationCodec.index(column, row)] = symbol;
        return codec.encode(raw);
    }

    String xSplitActivation(int activeRows) {
        int[] raw = codec.decode(constructiveLoss(activeRows));
        int row = random.nextInt(activeRows);
        int target = regularSymbolNotInColumn(raw, 0, activeRows);
        raw[FormationCodec.index(0, row)] = GameConstants.XSPLIT;
        raw[FormationCodec.index(1, row)] = target;
        raw[FormationCodec.index(2, row)] = target;
        return codec.encode(raw);
    }

    String resolveXSplit(String activation, int activeRows) {
        int[] raw = codec.decode(activation);
        Set<Integer> splitRows = new HashSet<Integer>();
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            for (int row = 0; row < activeRows; row++) {
                if (FormationCodec.symbolId(raw[FormationCodec.index(column, row)]) == GameConstants.XSPLIT) {
                    splitRows.add(row);
                }
            }
        }
        for (Integer row : splitRows) for (int column = 0; column < GameConstants.COLUMNS; column++) {
            int index = FormationCodec.index(column, row);
            int symbol = FormationCodec.symbolId(raw[index]);
            if (symbol == GameConstants.XSPLIT) raw[index] = raw[FormationCodec.index(1, row)] % 100;
            else if (symbol >= 3 && symbol <= 12 && FormationCodec.blockOrDirt(raw[index]) <= 0) {
                raw[index] += 1000;
            }
        }
        return codec.encode(raw);
    }

    String xBombWin(int activeRows) {
        int[] raw = codec.decode(constructiveLoss(activeRows));
        int target = regularSymbol();
        int row = random.nextInt(activeRows);
        raw[FormationCodec.index(0, row)] = target;
        raw[FormationCodec.index(1, row)] = target;
        raw[FormationCodec.index(2, row)] = GameConstants.WILD;
        return codec.encode(raw);
    }

    String collapseToTerminal(String previousFormation, int previousActiveRows, int nextActiveRows,
                              ResultUtil independentOracle, BigDecimal baseBet, int globalMultiplier) {
        int[] before = codec.decode(previousFormation);
        ResultUtil.Inspection inspection = independentOracle.inspect(previousFormation,
                previousActiveRows, baseBet, globalMultiplier);
        Set<Integer> removed = new HashSet<Integer>();
        for (ResultUtil.WayWin win : inspection.getWins()) {
            for (ResultUtil.CellPosition position : win.getPositions()) {
                removed.add(FormationCodec.index(position.getColumn(), position.getRow()));
            }
        }
        addWildBlastCells(before, previousActiveRows, removed);
        if (removed.isEmpty()) throw new IllegalStateException("collapse source has no removable cells");

        // fallOld/fallNew 只处理新开放的有效行；其余 dirt/block 缓冲格逐格保留。
        int[] after = before.clone();
        List<Integer> refillIndexes = new ArrayList<Integer>();
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            List<Integer> survivors = new ArrayList<Integer>();
            for (int row = 0; row < nextActiveRows; row++) {
                int index = FormationCodec.index(column, row);
                int symbol = FormationCodec.symbolId(before[index]);
                boolean emptyWithoutBlock = symbol == GameConstants.EMPTY
                        && FormationCodec.blockOrDirt(before[index]) <= 0;
                if (!removed.contains(index) && !emptyWithoutBlock) survivors.add(before[index]);
            }
            int survivorStart = nextActiveRows - survivors.size();
            if (survivorStart < 0) throw new IllegalStateException("collapse survivors exceed active rows");
            for (int row = 0; row < survivorStart; row++) {
                int index = FormationCodec.index(column, row);
                after[index] = -1;
                refillIndexes.add(index);
            }
            for (int offset = 0; offset < survivors.size(); offset++) {
                after[FormationCodec.index(column, survivorStart + offset)] = survivors.get(offset);
            }
        }
        fillTerminalRefills(after, refillIndexes, nextActiveRows);
        ResultUtil.Inspection terminal = independentOracle.inspect(
                codec.encode(after), nextActiveRows, baseBet, globalMultiplier);
        if (!independentOracle.isTerminalBaseStep(terminal)) {
            throw new IllegalStateException("constructed terminal refill failed the independent oracle");
        }
        return codec.encode(after);
    }

    /**
     * fallNew 按列线性构造。前三列填入每一格时，排除已同时出现在另外两列的符号，
     * 因而结构上不可能形成三列 Ways；同时逐格排除横向或纵向三连。候选仍按配置权重
     * 随机排序，seed 影响符号分布，但不依赖固定次数碰运气。
     */
    private void fillTerminalRefills(int[] raw, List<Integer> refillIndexes, int activeRows) {
        for (Integer index : refillIndexes) {
            int column = index / GameConstants.ENCODED_ROWS;
            int row = index % GameConstants.ENCODED_ROWS;
            boolean assigned = false;
            for (Integer symbol : symbolWeights.weightedPermutation(random)) {
                if (column < 3 && appearsUncoveredInBothOtherWayColumns(
                        raw, column, symbol, activeRows)) continue;
                if (createsMiningRun(raw, column, row, symbol, activeRows)) continue;
                raw[index] = symbol;
                assigned = true;
                break;
            }
            if (!assigned) {
                throw new IllegalStateException("terminal refill cell has no legal regular symbol at "
                        + column + "," + row);
            }
        }
    }

    private boolean appearsUncoveredInBothOtherWayColumns(int[] raw, int column,
                                                           int symbol, int activeRows) {
        int matchingOtherColumns = 0;
        for (int other = 0; other < 3; other++) {
            if (other != column && appearsUncovered(raw, other, symbol, activeRows)) {
                matchingOtherColumns++;
            }
        }
        return matchingOtherColumns == 2;
    }

    private boolean appearsUncovered(int[] raw, int column, int symbol, int activeRows) {
        for (int row = 0; row < activeRows; row++) {
            int cell = raw[FormationCodec.index(column, row)];
            if (cell >= 0 && FormationCodec.symbolId(cell) == symbol
                    && FormationCodec.blockOrDirt(cell) <= 0) return true;
        }
        return false;
    }

    private boolean createsMiningRun(int[] raw, int column, int row, int symbol, int activeRows) {
        int index = FormationCodec.index(column, row);
        int previous = raw[index];
        raw[index] = symbol;
        try {
            for (int start = Math.max(0, column - 2);
                 start <= Math.min(column, GameConstants.COLUMNS - 3); start++) {
                if (sameRegular(raw, FormationCodec.index(start, row),
                        FormationCodec.index(start + 1, row), FormationCodec.index(start + 2, row))) return true;
            }
            for (int start = Math.max(0, row - 2); start <= Math.min(row, activeRows - 3); start++) {
                if (sameRegular(raw, FormationCodec.index(column, start),
                        FormationCodec.index(column, start + 1), FormationCodec.index(column, start + 2))) return true;
            }
            return false;
        } finally {
            raw[index] = previous;
        }
    }

    private boolean sameRegular(int[] raw, int first, int second, int third) {
        if (raw[first] < 0 || raw[second] < 0 || raw[third] < 0) return false;
        int symbol = FormationCodec.symbolId(raw[first]);
        return symbol >= 3 && symbol <= 12
                && FormationCodec.symbolId(raw[second]) == symbol
                && FormationCodec.symbolId(raw[third]) == symbol;
    }

    private void addWildBlastCells(int[] raw, int activeRows, Set<Integer> removed) {
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            for (int row = 0; row < activeRows; row++) {
                int index = FormationCodec.index(column, row);
                if (FormationCodec.symbolId(raw[index]) != GameConstants.WILD
                        || FormationCodec.blockOrDirt(raw[index]) > 0) continue;
                for (int dc = -1; dc <= 1; dc++) for (int dr = -1; dr <= 1; dr++) {
                    int adjacentColumn = column + dc;
                    int adjacentRow = row + dr;
                    if (adjacentColumn < 0 || adjacentColumn >= GameConstants.COLUMNS
                            || adjacentRow < 0 || adjacentRow >= activeRows) continue;
                    int adjacentIndex = FormationCodec.index(adjacentColumn, adjacentRow);
                    if (FormationCodec.symbolId(raw[adjacentIndex]) != GameConstants.BONUS) {
                        removed.add(adjacentIndex);
                    }
                }
            }
        }
    }

    private String constructiveLoss(int activeRows) {
        if (activeRows < 1 || activeRows > GameConstants.MAX_ACTIVE_ROWS) {
            throw new IllegalArgumentException("activeRows is outside the confirmed board model");
        }
        int[] raw = blankBoard();
        List<Integer> symbols = symbolWeights.weightedPermutation(random);
        List<Integer> groupA = symbols.subList(0, 5);
        List<Integer> groupB = symbols.subList(5, 10);
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            List<Integer> group = column % 2 == 0 ? groupA : groupB;
            int offset = random.nextInt(group.size());
            int direction = random.nextBoolean() ? 1 : -1;
            for (int row = 0; row < activeRows; row++) {
                int pos = (offset + direction * row) % group.size();
                if (pos < 0) pos += group.size();
                raw[FormationCodec.index(column, row)] = group.get(pos);
            }
        }
        return codec.encode(raw);
    }

    private int[] blankBoard() {
        int[] raw = new int[GameConstants.CELL_COUNT];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = GameConstants.EMPTY + symbolWeights.selectEmptyMaterial(random) * 100;
        }
        return raw;
    }

    private int regularSymbol() { return symbolWeights.select(random); }

    private int regularSymbolNotInColumn(int[] raw, int column, int activeRows) {
        Set<Integer> excluded = new HashSet<Integer>();
        for (int symbol = 3; symbol <= 12; symbol++) {
            boolean found = false;
            for (int row = 0; row < activeRows; row++) {
                if (FormationCodec.symbolId(raw[FormationCodec.index(column, row)]) == symbol) {
                    found = true;
                    break;
                }
            }
            if (found) excluded.add(symbol);
        }
        return symbolWeights.selectExcluding(random, excluded);
    }
}
