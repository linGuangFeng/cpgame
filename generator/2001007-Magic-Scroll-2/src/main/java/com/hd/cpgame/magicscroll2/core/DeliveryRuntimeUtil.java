package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;

/**
 * Pure runtime projection from one cached complete Round and a deliveryIndex.
 * It has no HTTP, session, balance, Spring or Redis dependency and never patches a formation online.
 */
public final class DeliveryRuntimeUtil {
    private final ResultUtil resultUtil;
    private final GenerationPolicy policy;
    private final FormationCodec formationCodec = new FormationCodec();

    public DeliveryRuntimeUtil() { this(new ResultUtil(), GenerationPolicy.defaults()); }

    DeliveryRuntimeUtil(ResultUtil resultUtil, GenerationPolicy policy) {
        if (resultUtil == null || policy == null) {
            throw new IllegalArgumentException("ResultUtil and policy are required");
        }
        this.resultUtil = resultUtil;
        this.policy = policy;
    }

    public Projection project(GeneratedRound round, int deliveryIndex) {
        ResultUtil.RoundAnalysis analysis = resultUtil.analyzeCompleteRound(round, policy);
        if (deliveryIndex < 0 || deliveryIndex >= round.getSteps().size()) {
            throw new IllegalArgumentException("deliveryIndex is outside the complete Round");
        }
        RoundStep step = round.getSteps().get(deliveryIndex);
        int frontendActiveRows = deriveFrontendActiveRows(round, deliveryIndex);
        if (step.getActiveRows() != frontendActiveRows) {
            throw new IllegalStateException("Delivery activeRows is not reachable by original LineMgr at index "
                    + deliveryIndex + ": declared=" + step.getActiveRows()
                    + ", derived=" + frontendActiveRows);
        }
        int activatedRow = deliveryIndex > 0
                && round.getSteps().get(deliveryIndex - 1).getActiveRows() < frontendActiveRows
                ? frontendActiveRows - 1 : -1;
        int visibleSymbolsInActivatedRow = activatedRow >= 0
                ? formationCodec.countFrontendVisibleSymbols(step.getFormation(), activatedRow) : 0;
        return new Projection(round.getSchemaVersion(), round.getRulesVersion(), round.getRulesHash(),
                round.getDeterministicSeed(), round.getRoundKey(), analysis.getMode(), deliveryIndex,
                analysis.getDeliveryCount(), step.getFormation(), frontendActiveRows,
                step.getGlobalMultiplier(), step.getStepWin(), step.getCumulativeWin(),
                analysis.getPayout(), analysis.getActualMultiplier(), step.isTerminal(), 0,
                activatedRow, visibleSymbolsInActivatedRow);
    }

    /** Mirrors GlobalEx.spinGame's vaildRow transition, rather than trusting server-only metadata. */
    private int deriveFrontendActiveRows(GeneratedRound round, int deliveryIndex) {
        int rows = GameConstants.INITIAL_ACTIVE_ROWS;
        BigDecimal baseBet = RoundFactory.baseBet(round.getPaidBet());
        for (int index = 0; index < deliveryIndex; index++) {
            RoundStep previous = round.getSteps().get(index);
            ResultUtil.Inspection inspection = resultUtil.inspect(previous.getFormation(), rows,
                    baseBet, previous.getGlobalMultiplier());
            // Original GlobalEx resolves xSplit first without opening a row. A win, xBomb or
            // Magic-Mining combine opens one row for the next Delivery, capped at six.
            if (!inspection.hasXSplit() && (inspection.getWin().signum() > 0
                    || inspection.hasWild() || inspection.hasMining())) {
                rows = Math.min(GameConstants.MAX_ACTIVE_ROWS, rows + 1);
            }
        }
        return rows;
    }

    public static final class Projection {
        private final String schemaVersion, rulesVersion, rulesHash, roundKey, formation;
        private final long deterministicSeed;
        private final RoundMode mode;
        private final int deliveryIndex, deliveryCount, activeRows, globalMultiplier, free;
        private final int activatedRow, visibleSymbolsInActivatedRow;
        private final BigDecimal stepWin, cumulativeWin, payout, actualMultiplier;
        private final boolean terminal;

        Projection(String schemaVersion, String rulesVersion, String rulesHash, long deterministicSeed,
                   String roundKey, RoundMode mode, int deliveryIndex, int deliveryCount,
                   String formation, int activeRows, int globalMultiplier, BigDecimal stepWin,
                   BigDecimal cumulativeWin, BigDecimal payout, BigDecimal actualMultiplier,
                   boolean terminal, int free, int activatedRow,
                   int visibleSymbolsInActivatedRow) {
            this.schemaVersion = schemaVersion; this.rulesVersion = rulesVersion; this.rulesHash = rulesHash;
            this.deterministicSeed = deterministicSeed; this.roundKey = roundKey; this.mode = mode;
            this.deliveryIndex = deliveryIndex; this.deliveryCount = deliveryCount; this.formation = formation;
            this.activeRows = activeRows; this.globalMultiplier = globalMultiplier; this.stepWin = stepWin;
            this.cumulativeWin = cumulativeWin; this.payout = payout; this.actualMultiplier = actualMultiplier;
            this.terminal = terminal; this.free = free;
            this.activatedRow = activatedRow;
            this.visibleSymbolsInActivatedRow = visibleSymbolsInActivatedRow;
        }

        public String getSchemaVersion() { return schemaVersion; }
        public String getRulesVersion() { return rulesVersion; }
        public String getRulesHash() { return rulesHash; }
        public long getDeterministicSeed() { return deterministicSeed; }
        public String getRoundKey() { return roundKey; }
        public RoundMode getMode() { return mode; }
        public int getDeliveryIndex() { return deliveryIndex; }
        public int getDeliveryCount() { return deliveryCount; }
        public String getFormation() { return formation; }
        public int getActiveRows() { return activeRows; }
        public int getGlobalMultiplier() { return globalMultiplier; }
        public BigDecimal getStepWin() { return stepWin; }
        public BigDecimal getCumulativeWin() { return cumulativeWin; }
        public BigDecimal getPayout() { return payout; }
        public BigDecimal getActualMultiplier() { return actualMultiplier; }
        public boolean isTerminal() { return terminal; }
        public int getFree() { return free; }
        /** Zero-based row opened by this Delivery, or -1 when no new row was opened. */
        public int getActivatedRow() { return activatedRow; }
        /** Drawable, uncovered symbols in the newly opened row. */
        public int getVisibleSymbolsInActivatedRow() { return visibleSymbolsInActivatedRow; }
    }
}
