package com.cpgame.luckydragon.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Spin 出牌：请求倍数落到合法列表后一把取 combo。联合模型仅保留给 Loader 配置校验与离线抽样。 */
public final class RandomRoundGenerator {
    /*
     * v38 冻结上限来自 1130 个原厂完整 Round，而不是 Help 未声明上限的推测。
     * 本游戏每列只有一个格，所以每列同一符号最多 1 个；H4 从未出现，禁止生成。
     * 完整联合状态抽样已经保留位置依赖，这里再对可配置模型做硬上限校验，防止加权时越界。
     */
    private static final Map<String, Integer> OBSERVED_TOTAL_MAX = Map.of(
        "H0", 3, "H1", 3, "H2", 2, "H3", 2, "H4", 0, "WILD", 2);
    public static final String DEFAULT_JOINT_MODEL =
        "H0,H0,H0,0:83;H0,H0,H1,0:36;H0,H0,H2,0:36;H0,H0,H3,0:32;H0,H1,H0,0:31;H0,H1,H1,0:19;H0,H1,H2,0:19;H0,H1,H3,0:17;H0,H1,WILD,0:1;H0,H2,H0,0:49;H0,H2,H1,0:18;H0,H2,H2,0:15;H0,H2,H3,0:19;H0,H2,WILD,0:1;H0,H3,H0,0:39;H0,H3,H1,0:15;H0,H3,H2,0:15;H0,H3,H3,0:11;H0,H3,WILD,0:1;H0,WILD,H0,3:1;H0,WILD,H0,5:1;H0,WILD,H1,5:1;H0,WILD,H2,3:1;H1,H0,H0,0:23;H1,H0,H1,0:18;H1,H0,H2,0:17;H1,H0,H3,0:15;H1,H0,WILD,0:1;H1,H1,H0,0:12;H1,H1,H1,0:40;H1,H1,WILD,0:1;H1,H2,H0,0:16;H1,H3,H0,0:14;H1,WILD,H1,3:38;H2,H0,H0,0:40;H2,H0,H1,0:14;H2,H0,H2,0:15;H2,H0,H3,0:13;H2,H0,WILD,0:1;H2,H1,H0,0:14;H2,H2,H0,0:15;H2,H3,H0,0:12;H2,WILD,H2,3:32;H2,WILD,H2,5:32;H2,WILD,H2,9:28;H2,WILD,WILD,3:3;H2,WILD,WILD,5:4;H2,WILD,WILD,9:2;H3,H0,H0,0:29;H3,H0,H1,0:18;H3,H0,H2,0:16;H3,H0,H3,0:15;H3,H0,WILD,0:3;H3,H1,H0,0:18;H3,H2,H0,0:23;H3,H3,H0,0:15;H3,WILD,H0,3:2;WILD,H0,H0,0:4;WILD,H0,H1,0:1;WILD,H0,H2,0:1;WILD,H1,H0,0:1;WILD,WILD,H2,3:1;WILD,WILD,H2,5:1;WILD,WILD,H2,9:1;H2,H2,WILD,0:8;WILD,H2,H2,0:8;H3,H3,WILD,0:8;WILD,H3,H3,0:8;H1,WILD,H1,5:8;H1,WILD,H1,9:8;H1,WILD,WILD,3:4;H1,WILD,WILD,5:4;H1,WILD,WILD,9:4;WILD,WILD,H1,3:4;WILD,WILD,H1,5:4;WILD,WILD,H1,9:4;H3,WILD,H3,3:8;H3,WILD,H3,5:8;H3,WILD,H3,9:8;H3,WILD,WILD,3:4;H3,WILD,WILD,5:4;H3,WILD,WILD,9:4;WILD,WILD,H3,3:4;WILD,WILD,H3,5:4;WILD,WILD,H3,9:4";

    private final GameRuleCore rules;
    private final RandomGenerator random;
    private final String jointModel;
    private List<JointState> weightedStates;

    public RandomRoundGenerator(GameRuleCore rules) { this(rules, new SecureRandom(), DEFAULT_JOINT_MODEL); }
    public RandomRoundGenerator(GameRuleCore rules, RandomGenerator random) { this(rules, random, DEFAULT_JOINT_MODEL); }

    /**
     * Joint-model text is still validated for Loader/config compatibility. Spin generation
     * floors the requested odd into the legal list and looks up one combo; no retry.
     */
    public RandomRoundGenerator(GameRuleCore rules, RandomGenerator random, String jointModel) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.random = Objects.requireNonNull(random, "random");
        this.jointModel = jointModel;
    }

    private List<JointState> states() {
        if (weightedStates == null) weightedStates = parse(jointModel, rules);
        return weightedStates;
    }

    public SpinResult next(RoundRequest request) {
        return generate(request, LuckyDragonMultiplierCatalog.sampleRequestedOdd(random));
    }

    /** One generate entry: requested odd floors into the list, then one catalog combo. */
    public SpinResult generate(RoundRequest request, int requestedOdd) {
        int floored = LuckyDragonMultiplierCatalog.floorOdd(requestedOdd);
        LuckyDragonMultiplierCatalog.Combo combo = LuckyDragonMultiplierCatalog.pickCombo(floored, random);
        SpinResult result = rules.evaluate(request, combo.symbols(), combo.reelMultiplier());
        int got = ResultUtil.positiveMultiplier(request, result);
        if (got != floored) {
            throw new IllegalStateException("generated multiplier " + got + " != " + floored);
        }
        return result;
    }

    public SpinResult independentLoss(RoundRequest request) {
        return generate(request, 0);
    }

    /** Frozen joint-state sample; used by the offline model validator, not Spin. */
    public SpinResult nextJoint(RoundRequest request) {
        JointState state = states().get(random.nextInt(states().size()));
        return rules.evaluate(request, state.symbols, state.reelMultiplier);
    }

    public static int jointStateCount(String specification) {
        return specification == null || specification.isBlank() ? 0 : specification.split(";", -1).length;
    }

    private static List<JointState> parse(String specification, GameRuleCore rules) {
        if (specification == null || specification.isBlank()) throw new IllegalArgumentException("joint state model is empty");
        List<JointState> weighted = new ArrayList<>();
        for (String encoded : specification.split(";", -1)) {
            int separator = encoded.lastIndexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("invalid joint state: " + encoded);
            int weight;
            try { weight = Integer.parseInt(encoded.substring(separator + 1)); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("invalid joint state weight: " + encoded); }
            if (weight <= 0) throw new IllegalArgumentException("joint state weight must be positive");
            String[] fields = encoded.substring(0, separator).split(",", -1);
            if (fields.length != 4) throw new IllegalArgumentException("joint state must contain three symbols and rpx");
            int rpx;
            try { rpx = Integer.parseInt(fields[3]); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("invalid joint rpx: " + encoded); }
            JointState state = new JointState(List.of(fields[0], fields[1], fields[2]), rpx);
            validateObservedSymbolLimits(state.symbols);
            rules.evaluate(new RoundRequest(java.math.BigDecimal.ONE, 1), state.symbols, state.reelMultiplier);
            for (int index = 0; index < weight; index++) weighted.add(state);
        }
        if (weighted.size() < 100) throw new IllegalArgumentException("joint model training mass is unexpectedly small");
        return List.copyOf(weighted);
    }

    private static void validateObservedSymbolLimits(List<String> symbols) {
        for (String symbol : symbols) {
            if (!OBSERVED_TOTAL_MAX.containsKey(symbol) || OBSERVED_TOTAL_MAX.get(symbol) == 0) {
                throw new IllegalArgumentException("joint state contains unobserved symbol: " + symbol);
            }
        }
        for (var limit : OBSERVED_TOTAL_MAX.entrySet()) {
            long count = symbols.stream().filter(limit.getKey()::equals).count();
            if (count > limit.getValue()) {
                throw new IllegalArgumentException("joint state exceeds observed total limit for " + limit.getKey());
            }
        }
    }

    private record JointState(List<String> symbols, int reelMultiplier) { }
}
