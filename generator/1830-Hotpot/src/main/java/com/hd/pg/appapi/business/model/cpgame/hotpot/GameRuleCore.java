package com.hd.pg.appapi.business.model.cpgame.hotpot;

/**
 * Shared Hotpot rule core used by the Redis Loader and by server-api projection.
 * This type evaluates and classifies boards; it does not deal a Demo spin.
 */
public interface GameRuleCore {
    int rawGameId();
    String gameName();
    boolean implementationAllowed();

    HotpotEvaluation evaluate(HotpotBoard board);

    int awardedFreeSpins(int scatterCount, HotpotSpinMode mode);

    HotpotRoundKind classifyRound(boolean scatterFreeSpins, int integerMultiplier);
}
