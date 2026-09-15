package com.cpgame.coinmastergo.core;

/**
 * LOSS is the only production default because runtimeIndependentLoss is the only
 * independently generatable outcome authorized by the accepted capability file.
 * The other values are explicit demo/test scripts; they never participate in an
 * invented probability distribution.
 */
public enum RoundScenario {
    LOSS,
    BASE_WIN,
    GOLDEN_TRANSFORM,
    FREE_SPINS,
    FREE_RETRIGGER
}
