package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent result oracle. It decodes and evaluates the emitted wire formation itself. */
public final class ResultUtil {
    private final FormationCodec codec = new FormationCodec();

    public Inspection inspect(String formation, int activeRows, BigDecimal baseBet, int globalMultiplier) {
        if (activeRows < 1 || activeRows > GameConstants.MAX_ACTIVE_ROWS) {
            throw new IllegalArgumentException("activeRows is outside the confirmed board model");
        }
        if (baseBet == null || baseBet.signum() <= 0) throw new IllegalArgumentException("baseBet must be positive");
        if (globalMultiplier < 1) throw new IllegalArgumentException("globalMultiplier must be positive");
        int[] raw = codec.decode(formation);

        boolean xSplit = false;
        int wildCountValue = 0;
        int bonusCount = 0;
        for (int c = 0; c < GameConstants.COLUMNS; c++) {
            for (int r = 0; r < activeRows; r++) {
                int cell = raw[FormationCodec.index(c, r)];
                int symbol = FormationCodec.symbolId(cell);
                if (symbol == GameConstants.XSPLIT) xSplit = true;
                if (symbol == GameConstants.WILD) wildCountValue += FormationCodec.multiplicity(cell);
                if (symbol == GameConstants.BONUS) bonusCount++;
            }
        }

        List<WayWin> wins = new ArrayList<WayWin>();
        BigDecimal total = BigDecimal.ZERO;
        int totalWays = 0;
        for (int symbol = 3; symbol <= 12; symbol++) {
            int matched = 0;
            int ways = 1;
            List<CellPosition> positions = new ArrayList<CellPosition>();
            for (int c = 0; c < GameConstants.COLUMNS; c++) {
                int columnCount = 0;
                List<CellPosition> columnPositions = new ArrayList<CellPosition>();
                for (int r = 0; r < activeRows; r++) {
                    int cellRaw = raw[FormationCodec.index(c, r)];
                    int cellSymbol = FormationCodec.symbolId(cellRaw);
                    if (FormationCodec.blockOrDirt(cellRaw) <= 0
                            && (cellSymbol == symbol || cellSymbol == GameConstants.WILD)) {
                        columnCount += FormationCodec.multiplicity(cellRaw);
                        columnPositions.add(new CellPosition(c, r, cellRaw));
                    }
                }
                if (columnCount == 0) break;
                matched++;
                ways *= columnCount;
                positions.addAll(columnPositions);
            }
            if (matched >= 3) {
                if (ways > GameConstants.MAX_WAYS) throw new IllegalStateException("ways exceed 46,656");
                int paytable = GameConstants.PAYTABLE[symbol][matched];
                BigDecimal win = baseBet.multiply(BigDecimal.valueOf(paytable))
                        .multiply(BigDecimal.valueOf(ways))
                        .multiply(BigDecimal.valueOf(globalMultiplier))
                        .setScale(2, RoundingMode.HALF_UP);
                wins.add(new WayWin(symbol, matched, ways, paytable, win, positions));
                total = total.add(win);
                totalWays += ways;
            }
        }
        boolean mining = hasMiningAlignment(raw, activeRows);
        return new Inspection(wins, total.setScale(2, RoundingMode.HALF_UP), totalWays,
                xSplit, wildCountValue, bonusCount, mining);
    }

    /** Reverses whether a confirmed base-mode Delivery can legally terminate. */
    public boolean isTerminalBaseStep(Inspection inspection) {
        if (inspection == null) throw new IllegalArgumentException("inspection is required");
        return inspection.getWin().signum() == 0
                && !inspection.hasXSplit()
                && !inspection.hasWild()
                && inspection.getBonusCount() < 3
                && !inspection.hasMining();
    }

    private boolean hasMiningAlignment(int[] raw, int activeRows) {
        for (int r = 0; r < activeRows; r++) {
            int runSymbol = -1, run = 0;
            for (int c = 0; c < GameConstants.COLUMNS; c++) {
                int symbol = FormationCodec.symbolId(raw[FormationCodec.index(c, r)]);
                if (symbol >= 3 && symbol <= 12 && symbol == runSymbol) run++;
                else { runSymbol = symbol; run = symbol >= 3 && symbol <= 12 ? 1 : 0; }
                if (run >= 3) return true;
            }
        }
        for (int c = 0; c < GameConstants.COLUMNS; c++) {
            int runSymbol = -1, run = 0;
            for (int r = 0; r < activeRows; r++) {
                int symbol = FormationCodec.symbolId(raw[FormationCodec.index(c, r)]);
                if (symbol >= 3 && symbol <= 12 && symbol == runSymbol) run++;
                else { runSymbol = symbol; run = symbol >= 3 && symbol <= 12 ? 1 : 0; }
                if (run >= 3) return true;
            }
        }
        return false;
    }

    public void assertIndependentLoss(String formation, int activeRows, BigDecimal baseBet) {
        Inspection i = inspect(formation, activeRows, baseBet, 1);
        if (!isTerminalBaseStep(i)) {
            throw new IllegalStateException("candidate is not an independent terminal LOSS");
        }
    }

    /** 独立反推完整 Round 的实际模式、赔付和精确实际倍率。 */
    public RoundAnalysis analyzeCompleteRound(GeneratedRound round, GenerationPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("generation policy is required");
        RoundVerifier.Verification verification = new RoundVerifier(this, policy).verify(round);
        BigDecimal actualMultiplier = verification.getPayout().divide(round.getPaidBet(), 8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        boolean special = verification.getInferredMode() == RoundMode.XSPLIT
                || verification.getInferredMode() == RoundMode.XBOMB_WILD;
        return new RoundAnalysis(verification.getInferredMode(), verification.getPayout(),
                actualMultiplier, special, verification.getDeliveryCount());
    }

    /** Integer Redis ratio: 0 for LOSS, otherwise payout/bet in hundredths (2.40x → 240). */
    public int integerRatio(GeneratedRound round, GenerationPolicy policy) {
        RoundAnalysis analysis = analyzeCompleteRound(round, policy);
        if (analysis.getPayout().signum() <= 0) return 0;
        return analysis.getPayout().multiply(BigDecimal.valueOf(100))
                .divide(round.getPaidBet(), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    public static final class RoundAnalysis {
        private final RoundMode mode;
        private final BigDecimal payout;
        private final BigDecimal actualMultiplier;
        private final boolean special;
        private final int deliveryCount;

        RoundAnalysis(RoundMode mode, BigDecimal payout, BigDecimal actualMultiplier,
                      boolean special, int deliveryCount) {
            this.mode = mode;
            this.payout = payout;
            this.actualMultiplier = actualMultiplier;
            this.special = special;
            this.deliveryCount = deliveryCount;
        }
        public RoundMode getMode() { return mode; }
        public BigDecimal getPayout() { return payout; }
        public BigDecimal getActualMultiplier() { return actualMultiplier; }
        public String getRedisMultiplierToken() { return actualMultiplier.toPlainString(); }
        public boolean isSpecial() { return special; }
        public int getDeliveryCount() { return deliveryCount; }
    }

    public static final class Inspection {
        private final List<WayWin> wins;
        private final BigDecimal win;
        private final int ways;
        private final boolean xSplit;
        private final int wildCountValue;
        private final int bonusCount;
        private final boolean mining;

        Inspection(List<WayWin> wins, BigDecimal win, int ways, boolean xSplit, int wildCountValue,
                   int bonusCount, boolean mining) {
            this.wins = Collections.unmodifiableList(wins);
            this.win = win;
            this.ways = ways;
            this.xSplit = xSplit;
            this.wildCountValue = wildCountValue;
            this.bonusCount = bonusCount;
            this.mining = mining;
        }
        public List<WayWin> getWins() { return wins; }
        public BigDecimal getWin() { return win; }
        public int getWays() { return ways; }
        public boolean hasXSplit() { return xSplit; }
        public boolean hasWild() { return wildCountValue > 0; }
        public int getWildCountValue() { return wildCountValue; }
        public int getBonusCount() { return bonusCount; }
        public boolean hasMining() { return mining; }
    }

    public static final class WayWin {
        private final int symbol;
        private final int matchedColumns;
        private final int ways;
        private final int paytableMultiplier;
        private final BigDecimal win;
        private final List<CellPosition> positions;
        WayWin(int symbol, int matchedColumns, int ways, int paytableMultiplier, BigDecimal win,
               List<CellPosition> positions) {
            this.symbol = symbol;
            this.matchedColumns = matchedColumns;
            this.ways = ways;
            this.paytableMultiplier = paytableMultiplier;
            this.win = win;
            this.positions = Collections.unmodifiableList(new ArrayList<CellPosition>(positions));
        }
        public int getSymbol() { return symbol; }
        public int getMatchedColumns() { return matchedColumns; }
        public int getWays() { return ways; }
        public int getPaytableMultiplier() { return paytableMultiplier; }
        public BigDecimal getWin() { return win; }
        public List<CellPosition> getPositions() { return positions; }
    }

    public static final class CellPosition {
        private final int column;
        private final int row;
        private final int raw;
        CellPosition(int column, int row, int raw) {
            this.column = column;
            this.row = row;
            this.raw = raw;
        }
        public int getColumn() { return column; }
        public int getRow() { return row; }
        public int getRaw() { return raw; }
    }
}
