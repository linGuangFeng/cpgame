package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;

/**
 * server-api binds the same GameRuleCore / ResultUtil sources as the Redis Loader.
 * Demo Controller claims a complete Round from Redis db=15 and only projects with this core.
 * Do not add a second award engine here.
 */
public final class LuckyPandaRuleBinding {
    private LuckyPandaRuleBinding() { }

    public static int gameId() { return GameRuleCore.GAME_ID; }
    public static String rulesHash() { return GameRuleCore.RULES_HASH; }
    public static String rulesVersion() { return GameRuleCore.RULES_VERSION; }
    public static Class<LuckyPandaResultUtil> resultUtil() { return LuckyPandaResultUtil.class; }
}
