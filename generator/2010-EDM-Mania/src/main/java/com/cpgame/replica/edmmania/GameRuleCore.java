package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;

import java.math.BigDecimal;

/** 2010 唯一规则核心入口。判奖只走 ResultUtil，生成只走 CompleteRoundFactory。 */
public final class GameRuleCore {
    public static final int GAME_ID = 2010;
    public static final String GAME_NAME = "EDM Mania";
    public static final String RULES_VERSION = EdmManiaRulesMetadata.VERSION;
    public static final String RULES_HASH = EdmManiaRulesMetadata.HASH;
    public static final int WAYS = 20;

    public EdmManiaEvaluation evaluate(EdmManiaBoard board, BigDecimal unitBet, int baseMultiplier, int newBalls) {
        return EdmManiaResultUtil.evaluate(board, unitBet, baseMultiplier, 2, newBalls);
    }

    public RoundVerification verify(String member, int maxConsecutiveWins, int maxMarySpins) {
        return new CompleteRoundCodec().verify(member, maxConsecutiveWins, false, maxMarySpins);
    }
}
