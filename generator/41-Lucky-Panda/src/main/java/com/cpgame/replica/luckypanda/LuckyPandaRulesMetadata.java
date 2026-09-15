package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;

/** Identity shared by Loader output and later HTTP projection. */
public final class LuckyPandaRulesMetadata {
    public static final String VERSION = GameRuleCore.RULES_VERSION;
    public static final String HASH = GameRuleCore.RULES_HASH;

    private LuckyPandaRulesMetadata() { }
}
