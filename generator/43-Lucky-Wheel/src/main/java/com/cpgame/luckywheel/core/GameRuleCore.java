package com.cpgame.luckywheel.core;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 当前游戏唯一的正式规则入口。试玩、服务端和 Loader 必须复用本类。 */
public final class GameRuleCore {
    public static final int SOURCE_GAME_ID = 43;
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "sha256:5BD756F5F541F6577977BA3A6FD309AED6BCC45730150E4A687B488157E595D1";

    private final RandomCandidateGenerator candidates;
    private final CompleteRoundFactory roundFactory;
    private final IndependentLossGenerator independentLossGenerator;

    /** 正式入口始终创建新的系统安全随机源，不接受 seed。 */
    public GameRuleCore() {
        this(new SecureRandom(), JointRoundModel.DEFAULT_OUTCOME_WEIGHTS,
                JointRoundModel.defaultJointStateWeights());
    }

    /** 正式 Loader 可调整整局结果类别权重；类别内联合状态仍使用已验证训练计数。 */
    public GameRuleCore(Map<String, Integer> outcomeWeights) {
        this(new SecureRandom(), outcomeWeights, JointRoundModel.defaultJointStateWeights());
    }

    /** 正式 Loader 可调整39个完整联合状态权重，键集合必须与当前规则模型完全一致。 */
    public GameRuleCore(Map<String, Integer> outcomeWeights, Map<String, Integer> jointStateWeights) {
        this(new SecureRandom(), outcomeWeights, jointStateWeights);
    }

    public static Map<String, Integer> defaultJointStateWeights() {
        return JointRoundModel.defaultJointStateWeights();
    }

    public static Map<String, Integer> defaultOutcomeWeights() {
        return JointRoundModel.defaultOutcomeWeights();
    }

    /** 仅供同包测试显式注入可复现随机源，正式配置和正式入口均不可达。 */
    GameRuleCore(RandomGenerator random) {
        this(random, JointRoundModel.DEFAULT_OUTCOME_WEIGHTS, JointRoundModel.defaultJointStateWeights());
    }

    GameRuleCore(RandomGenerator random, Map<String, Integer> outcomeWeights,
                 Map<String, Integer> jointStateWeights) {
        Objects.requireNonNull(random, "random");
        this.candidates = new RandomCandidateGenerator(random, outcomeWeights, jointStateWeights);
        this.roundFactory = new CompleteRoundFactory();
        this.independentLossGenerator = new IndependentLossGenerator(candidates);
    }

    /** 一次调用先生成完整候选，再组装并独立复核整个 Round。 */
    public synchronized GameRound generateRound(RoundRequest request) {
        RoundFacts facts = candidates.nextCandidate(request.betProfile());
        return roundFactory.create(request, facts, candidates.nextRoundKey());
    }

    /** 只用于 IDLE 状态下、与任何已激活 Round 无关的普通付费单步 LOSS。 */
    public GameRound generateIndependentLoss(RoundRequest request) {
        RoundFacts facts = independentLossGenerator.generate(request.betProfile());
        return roundFactory.create(request, facts, candidates.nextRoundKey());
    }

    public GameRound generateSs0ContinuationDisabled(RoundRequest request) {
        throw new UnsupportedOperationException("ss=0 缺少相邻响应证据，正式生成已安全禁用");
    }
}
