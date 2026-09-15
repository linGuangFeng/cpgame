package com.hd.pg.appapi.business.model.cpgame.hotpot;

/** Unique Hotpot GameRuleCore. Loader and server-api must share this type. */
public final class HotpotGameRuleCore implements GameRuleCore {
    @Override public int rawGameId() { return 1830; }
    @Override public String gameName() { return "Hotpot"; }
    @Override public boolean implementationAllowed() { return true; }

    @Override
    public HotpotEvaluation evaluate(HotpotBoard board) {
        return HotpotResultUtil.evaluate(board);
    }

    @Override
    public int awardedFreeSpins(int scatterCount, HotpotSpinMode mode) {
        return HotpotResultUtil.awardedFreeSpins(scatterCount, mode);
    }

    @Override
    public HotpotRoundKind classifyRound(boolean scatterFreeSpins, int integerMultiplier) {
        if (integerMultiplier < 0) throw new IllegalArgumentException("multiplier < 0");
        if (scatterFreeSpins) return HotpotRoundKind.SCATTER_FREE_SPINS;
        if (integerMultiplier == 0) return HotpotRoundKind.ORDINARY_LOSS;
        if (integerMultiplier > 0) return HotpotRoundKind.ORDINARY_WIN;
        throw new IllegalStateException("unclassified round");
    }
}
