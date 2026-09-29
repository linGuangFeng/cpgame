package com.cpgame.batcha.g8;

/**
 * Result pools for raw game id 8.
 * LOSS/WIN write to PerKeyList (WIN includes earth/water/fire dragons).
 * DRAGON is giant transform only and writes to MaryKeyList.
 */
public enum RoundMode {
    LOSS,
    WIN,
    DRAGON
}
