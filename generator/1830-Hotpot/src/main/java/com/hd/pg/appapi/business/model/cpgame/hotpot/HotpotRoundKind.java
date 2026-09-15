package com.hd.pg.appapi.business.model.cpgame.hotpot;

/**
 * Mutually exclusive complete-Round classification for game 1830.
 * BUY is not a member: this game has no purchase path.
 */
public enum HotpotRoundKind {
    ORDINARY_LOSS,
    ORDINARY_WIN,
    SCATTER_FREE_SPINS
}
