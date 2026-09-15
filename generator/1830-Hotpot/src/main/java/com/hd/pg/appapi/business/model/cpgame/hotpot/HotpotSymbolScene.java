package com.hd.pg.appapi.business.model.cpgame.hotpot;

/**
 * Per-cell generation scenes. Paid start weights must not be copied onto cascade fill
 * or free-step start; each scene has its own empirical counts.
 */
public enum HotpotSymbolScene {
    PAID_START,
    PAID_CASCADE,
    FREE_START,
    FREE_CASCADE
}
