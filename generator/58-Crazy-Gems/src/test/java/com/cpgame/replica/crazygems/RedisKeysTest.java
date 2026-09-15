package com.cpgame.replica.crazygems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RedisKeysTest {
    @Test
    void usesOneResultPageGroupForBothGameIds() {
        assertEquals("PerKeyList_000000058", RedisKeys.index(58));
        assertEquals("BetLog:000000058:000200", RedisKeys.list(58, 200));
        assertEquals("PerKeyList_008000058", RedisKeys.index(8000058));
        assertEquals("BetLog:008000058:000200", RedisKeys.list(8000058, 200));
    }
}
