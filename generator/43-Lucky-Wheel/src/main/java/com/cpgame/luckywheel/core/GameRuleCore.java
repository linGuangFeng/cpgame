package com.cpgame.luckywheel.core;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 当前游戏唯一的正式规则入口。试玩、服务端和 Loader 必须复用本类。 */
public final class GameRuleCore {
    public static final int SOURCE_GAME_ID = 43;
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "sha256:5BD756F5F541F6577977BA3A6FD309AED6BCC45730150E4A687B488157E595D1";

    private final RandomGenerator random;
    private final RealtimeRoundGenerator realtime;
    private final CompleteRoundFactory roundFactory;
    private final IndependentLossGenerator independentLossGenerator;

    public GameRuleCore() {
        this(new SecureRandom());
    }

    GameRuleCore(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
        this.realtime = new RealtimeRoundGenerator(random);
        this.roundFactory = new CompleteRoundFactory();
        this.independentLossGenerator = new IndependentLossGenerator();
    }

    public synchronized GameRound generateRound(RoundRequest request) {
        RoundFacts facts = realtime.generate(request);
        return roundFactory.create(request, facts, nextRoundKey());
    }

    /**
     * Spin/PG path: map bl to mode 1/5/10/50, floor requestedRatio onto that mode's odds list,
     * then pick a combo from that mode. 0 (or nothing &lt;= requested) is independent loss.
     */
    public synchronized GameRound generateRound(RoundRequest request, int requestedRatio) {
        int floored = LuckyWheelMultiplierCatalog.floorOdd(request.betLevel(), requestedRatio);
        if (floored <= 0) {
            return generateIndependentLoss(request);
        }
        RoundFacts facts = realtime.generate(request, requestedRatio);
        ResultAnalysis analysis = ResultUtil.analyze(facts);
        int actual = analysis.totalAward().stripTrailingZeros().intValueExact();
        if (actual != floored) {
            throw new IllegalStateException("Lucky Wheel generated " + actual + " != floored " + floored
                    + " (requested " + requestedRatio + ", mode " + LuckyWheelMultiplierCatalog.mode(request.betLevel()) + ")");
        }
        return roundFactory.create(request, facts, nextRoundKey());
    }

    public GameRound generateIndependentLoss(RoundRequest request) {
        RoundFacts facts = independentLossGenerator.generate(request.betProfile());
        return roundFactory.create(request, facts, nextRoundKey());
    }

    public GameRound generateSs0ContinuationDisabled(RoundRequest request) {
        throw new UnsupportedOperationException("ss=0 缺少相邻响应证据，正式生成已安全禁用");
    }

    private String nextRoundKey() {
        return Long.toUnsignedString(random.nextLong(), 16);
    }
}
