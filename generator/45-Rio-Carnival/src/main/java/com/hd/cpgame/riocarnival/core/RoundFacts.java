package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个完整局不可重算的最小事实：押注、随机牌面、初始免费次数选择和免费倍率选择。
 * wa/rwa/wmkl/进度/时间/会话均由同一规则核心重建，不进入成员。
 */
public final class RoundFacts {
    public int schemaVersion = 1;
    public int sourceGameId = GameRules.GAME_ID;
    public String rulesHash = GameRules.RULES_HASH;
    public BigDecimal betSize;
    public int betLevel;
    public int initialFreeSpins;
    public int freeMultiplier;
    public List<List<String>> boards = new ArrayList<List<String>>();

    public RoundFacts() {}
}
