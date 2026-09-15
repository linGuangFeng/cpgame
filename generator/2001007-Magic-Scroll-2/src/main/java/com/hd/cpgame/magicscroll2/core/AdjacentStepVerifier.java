package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 独立逐格相邻 Step 校验器。判定来自原前端 fallOld/fallNew、xSplit 状态机和
 * 原站连续完整局证据，不把生成器输出当作预期结果。
 */
public final class AdjacentStepVerifier {
    private final FormationCodec codec = new FormationCodec();
    private final ResultUtil resultUtil = new ResultUtil();

    public TransitionInspection verify(RoundStep previous, RoundStep current, BigDecimal baseBet) {
        if (previous == null || current == null || baseBet == null) {
            throw new IllegalArgumentException("previous, current and baseBet are required");
        }
        int[] before = codec.decode(previous.getFormation());
        int[] after = codec.decode(current.getFormation());
        ResultUtil.Inspection inspection = resultUtil.inspect(previous.getFormation(),
                previous.getActiveRows(), baseBet, previous.getGlobalMultiplier());
        if (inspection.hasXSplit()) return verifySplit(before, after, previous, current);
        return verifyCollapse(before, after, previous, current, inspection);
    }

    private TransitionInspection verifySplit(int[] before, int[] after,
                                             RoundStep previous, RoundStep current) {
        if (current.getActiveRows() != previous.getActiveRows()) {
            throw new IllegalStateException("xSplit adjacent Delivery must retain active row count");
        }
        Set<Integer> splitRows = new HashSet<Integer>();
        int splitCount = 0;
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            for (int row = 0; row < previous.getActiveRows(); row++) {
                int raw = before[FormationCodec.index(column, row)];
                if (FormationCodec.symbolId(raw) == GameConstants.XSPLIT
                        && FormationCodec.blockOrDirt(raw) <= 0) {
                    splitRows.add(row);
                    splitCount++;
                }
            }
        }
        if (splitCount == 0) throw new IllegalStateException("xSplit transition has no xSplit cell");
        int transformed = 0;
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            for (int row = 0; row < GameConstants.ENCODED_ROWS; row++) {
                int index = FormationCodec.index(column, row);
                int oldRaw = before[index];
                int oldSymbol = FormationCodec.symbolId(oldRaw);
                if (row < previous.getActiveRows() && splitRows.contains(row)) {
                    if (oldSymbol == GameConstants.XSPLIT) {
                        int replacement = FormationCodec.symbolId(after[index]);
                        if (replacement < 3 || replacement > 12) {
                            throw new IllegalStateException("xSplit cell was not replaced by a regular symbol");
                        }
                        transformed++;
                    } else if (oldSymbol >= 3 && oldSymbol <= 12
                            && FormationCodec.blockOrDirt(oldRaw) <= 0) {
                        if (after[index] != oldRaw + 1000) {
                            throw new IllegalStateException("xSplit row multiplicity was not doubled at "
                                    + column + "," + row);
                        }
                        transformed++;
                    } else if (after[index] != oldRaw) {
                        throw new IllegalStateException("xSplit changed an excluded symbol at "
                                + column + "," + row);
                    }
                } else if (after[index] != oldRaw) {
                    throw new IllegalStateException("xSplit changed an unrelated cell at "
                            + column + "," + row);
                }
            }
        }
        return new TransitionInspection("XSPLIT_ROW_TRANSFORM", splitCount, 0, 0, transformed);
    }

    private TransitionInspection verifyCollapse(int[] before, int[] after,
                                                RoundStep previous, RoundStep current,
                                                ResultUtil.Inspection inspection) {
        int expectedRows = Math.min(GameConstants.MAX_ACTIVE_ROWS, previous.getActiveRows() + 1);
        if (current.getActiveRows() != expectedRows) {
            throw new IllegalStateException("collapse must open exactly the next evidenced row");
        }
        Set<Integer> removed = new HashSet<Integer>();
        for (ResultUtil.WayWin win : inspection.getWins()) {
            for (ResultUtil.CellPosition position : win.getPositions()) {
                removed.add(FormationCodec.index(position.getColumn(), position.getRow()));
            }
        }
        int wildCount = addWildBlastCells(before, previous.getActiveRows(), removed);
        if (removed.isEmpty()) {
            throw new IllegalStateException("non-terminal adjacent Delivery has no evidenced removal set");
        }
        int moves = 0;
        int refills = 0;
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            List<Integer> survivors = new ArrayList<Integer>();
            for (int row = 0; row < current.getActiveRows(); row++) {
                int index = FormationCodec.index(column, row);
                int symbol = FormationCodec.symbolId(before[index]);
                boolean emptyWithoutBlock = symbol == GameConstants.EMPTY
                        && FormationCodec.blockOrDirt(before[index]) <= 0;
                if (!removed.contains(index) && !emptyWithoutBlock) survivors.add(before[index]);
            }
            int survivorStart = current.getActiveRows() - survivors.size();
            if (survivorStart < 0) throw new IllegalStateException("survivors exceed current active rows");
            for (int offset = 0; offset < survivors.size(); offset++) {
                int row = survivorStart + offset;
                int actual = after[FormationCodec.index(column, row)];
                if (actual != survivors.get(offset)) {
                    throw new IllegalStateException("retained symbol did not fall to the evidenced cell at "
                            + column + "," + row);
                }
                moves++;
            }
            for (int row = 0; row < survivorStart; row++) {
                validateRefillCell(after[FormationCodec.index(column, row)], column, row);
            }
            refills += survivorStart;
            for (int row = current.getActiveRows(); row < GameConstants.ENCODED_ROWS; row++) {
                int index = FormationCodec.index(column, row);
                if (after[index] != before[index]) {
                    throw new IllegalStateException("buffer cell changed outside fallOld range at "
                            + column + "," + row);
                }
            }
        }
        return new TransitionInspection("ELIMINATE_FALL_REFILL", removed.size(), wildCount,
                moves, refills);
    }

    /**
     * 原站相邻证据中的 68 个 fallNew 格仅出现原始编码 1、3..12。
     * 补位格不能携带 dirt/block、倍增指数、EMPTY、WILD 或 xSplit 隐藏编码。
     */
    private void validateRefillCell(int raw, int column, int row) {
        int symbol = FormationCodec.symbolId(raw);
        boolean evidencedSymbol = symbol == GameConstants.BONUS
                || (symbol >= 3 && symbol <= 12);
        boolean canonicalEncoding = raw == symbol
                && FormationCodec.blockOrDirt(raw) == 0
                && FormationCodec.multiplicity(raw) == 1;
        if (!evidencedSymbol || !canonicalEncoding) {
            throw new IllegalStateException("refill cell has unsupported symbol/encoding at "
                    + column + "," + row + ": " + raw);
        }
    }

    private int addWildBlastCells(int[] before, int activeRows, Set<Integer> removed) {
        int wilds = 0;
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            for (int row = 0; row < activeRows; row++) {
                int index = FormationCodec.index(column, row);
                if (FormationCodec.symbolId(before[index]) != GameConstants.WILD
                        || FormationCodec.blockOrDirt(before[index]) > 0) continue;
                wilds++;
                for (int dc = -1; dc <= 1; dc++) for (int dr = -1; dr <= 1; dr++) {
                    int adjacentColumn = column + dc;
                    int adjacentRow = row + dr;
                    if (adjacentColumn < 0 || adjacentColumn >= GameConstants.COLUMNS
                            || adjacentRow < 0 || adjacentRow >= activeRows) continue;
                    int adjacentIndex = FormationCodec.index(adjacentColumn, adjacentRow);
                    int symbol = FormationCodec.symbolId(before[adjacentIndex]);
                    if (symbol != GameConstants.BONUS) removed.add(adjacentIndex);
                }
            }
        }
        return wilds;
    }

    public static final class TransitionInspection {
        private final String transitionType;
        private final int removedCellCount;
        private final int wildCount;
        private final int retainedMoveCount;
        private final int refillCellCount;

        TransitionInspection(String transitionType, int removedCellCount, int wildCount,
                             int retainedMoveCount, int refillCellCount) {
            this.transitionType = transitionType;
            this.removedCellCount = removedCellCount;
            this.wildCount = wildCount;
            this.retainedMoveCount = retainedMoveCount;
            this.refillCellCount = refillCellCount;
        }

        public String getTransitionType() { return transitionType; }
        public int getRemovedCellCount() { return removedCellCount; }
        public int getWildCount() { return wildCount; }
        public int getRetainedMoveCount() { return retainedMoveCount; }
        public int getRefillCellCount() { return refillCellCount; }
    }
}
