package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisDirectLoaderTest {
    @Test
    void redisKeysFollowPlatformContractForGid41() {
        assertEquals("PerKeyList_000000041", RedisKeyContract.normalIndex(GameRuleCore.GAME_ID));
        assertEquals("MaryKeyList_000000041", RedisKeyContract.specialIndex(GameRuleCore.GAME_ID));
        assertEquals("BetLog:000000041:000000", RedisKeyContract.normalList(GameRuleCore.GAME_ID, 0));
        assertEquals("BetLog:000000041:000012", RedisKeyContract.normalList(GameRuleCore.GAME_ID, 12));
        assertEquals("MaryLog:000000041:000000", RedisKeyContract.specialList(GameRuleCore.GAME_ID, 0));
        assertEquals("MaryLog:000000041:000100", RedisKeyContract.specialList(GameRuleCore.GAME_ID, 100));
    }

    @Test
    void zeroMultiplierIsReservable() {
        Map<Integer, Integer> bag = new HashMap<>();
        assertTrue(RedisDirectLoader.tryReserveMultiplier(bag, 0, 300));
        assertEquals(1, bag.get(0));
    }

    @Test
    void specialEntryOnlyBoostsPaidStartScatter() {
        var ordinary = CompleteRoundFactoryTest.weights();
        var boosted = RedisDirectLoader.boostedScatter(ordinary);
        int scat = 12;
        assertEquals(ordinary.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.PAID_START)[scat] * 10,
                boosted.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.PAID_START)[scat]);
        assertEquals(ordinary.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.CASCADE_REFILL)[scat],
                boosted.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.CASCADE_REFILL)[scat]);
        assertEquals(ordinary.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.FREE_START)[scat],
                boosted.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.FREE_START)[scat]);
        assertEquals(ordinary.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.FREE_CASCADE_REFILL)[scat],
                boosted.get(com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene.FREE_CASCADE_REFILL)[scat]);
    }
}
