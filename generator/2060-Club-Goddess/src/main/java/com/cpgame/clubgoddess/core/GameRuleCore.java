package com.cpgame.clubgoddess.core;

import com.cpgame.clubgoddess.core.GameModels.GameResult;
import com.cpgame.clubgoddess.core.GameModels.RoundBundle;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Stable facade shared by the Loader and server API. */
public final class GameRuleCore {
    public static final String RULES_VERSION = GameRuleDefinition.RULES_VERSION;
    public static final String RULES_HASH = GameRuleDefinition.RULES_HASH;
    private final RoundFactory roundFactory;

    public GameRuleCore() { this(new SecureRandom()); }

    /** Package-visible random injection is reserved for tests in this rules module. */
    GameRuleCore(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        this.roundFactory = new RoundFactory(new RandomBoardCandidateGenerator(random), random);
    }

    public RoundBundle generateRound(BigDecimal bet, int level, BigDecimal startBalance) {
        return roundFactory.create(bet, level, startBalance);
    }
    public RoundBundle generateOrdinaryRound(BigDecimal bet,int level,BigDecimal startBalance) {
        return roundFactory.createOrdinary(bet,level,startBalance);
    }
    public RoundBundle generateSpecialRound(BigDecimal bet,int level,BigDecimal startBalance) {
        return roundFactory.createSpecial(bet,level,startBalance);
    }

    public GameResult generateIndependentLoss(BigDecimal bet, int level, BigDecimal startBalance, String oid) {
        return roundFactory.createIndependentLoss(bet, level, startBalance, oid);
    }

    /** 从 Redis 最小事实重建派生字段，并再次交给独立 ResultUtil/RoundVerifier 复核。 */
    public static RoundBundle rebuildBaseRound(String roundKey, java.util.List<Integer> board,
                                               BigDecimal bet, int level, BigDecimal startBalance) {
        return RoundFactory.rebuildVerifiedBaseRound(roundKey, board, bet, level, startBalance);
    }
    public static RoundBundle rebuildRound(String roundKey,java.util.List<java.util.List<Integer>> boards,
                                           BigDecimal bet,int level,BigDecimal startBalance) {
        return RoundFactory.rebuildVerifiedRound(roundKey,boards,bet,level,startBalance);
    }
}
