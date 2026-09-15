package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

/**
 * Four empirically distinct entry scenes. Paid-start weights must not be copied onto refill or free.
 */
public enum WeightScene {
    PAID_START,
    CASCADE_REFILL,
    FREE_START,
    FREE_CASCADE_REFILL
}
