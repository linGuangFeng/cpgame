package com.cpgame.luckywheel.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 按 bet&lt;5 与 bet&gt;=5 分层的完整 Round 联合模型。每个 Pattern 是整个
 * md+rskl+rpx+fwi/fwa 联合状态，不做单格边际独立抽样，也不为制造LOSS改盘。
 */
final class JointRoundModel {
    static final Map<String, Integer> DEFAULT_OUTCOME_WEIGHTS = defaultOutcomeWeights();

    private static final List<Pattern> PATTERNS = List.of(
            low(3012, 0, "H0", "H0", 1), low(430, 0, "H0", "H1", 1),
            low(19, 0, "H4", "H1", 1), low(29, 0, "H4", "H3", 1),
            low(48, 0, "H4", "H4", 1), low(30, 0, "H5", "H1", 1),
            low(25, 0, "H5", "H3", 1), low(48, 0, "H5", "H4", 1),
            low(156, 1, "H0", "H0", 2), low(114, 1, "H0", "H0", 5),
            low(28, 1, "H0", "H1", 2), low(15, 1, "H0", "H1", 5),
            low(2, 1, "H3", "H3", 5), low(42, 1, "H3", "H4", 5),
            low(3, 1, "H4", "H1", 2), low(57, 1, "H4", "H1", 5),
            low(51, 1, "H4", "H3", 2), low(45, 1, "H4", "H3", 5),
            low(37, 1, "H4", "H4", 2), low(65, 1, "H4", "H4", 5),
            low(9, 1, "H5", "H0", 5), low(42, 1, "H5", "H1", 2),
            low(51, 1, "H5", "H1", 5), low(53, 1, "H5", "H3", 2),
            low(45, 1, "H5", "H4", 2),
            lowRespin(9, "H0", "H0", "H4", "H1"), lowRespin(13, "H0", "H0", "H4", "H3"),
            lowRespin(3, "H0", "H0", "H4", "H4"), lowRespin(4, "H0", "H0", "H5", "H1"),
            lowRespin(5, "H0", "H0", "H5", "H3"), lowRespin(7, "H0", "H0", "H5", "H4"),
            lowRespin(2, "H0", "H1", "H4", "H1"), lowRespin(3, "H0", "H1", "H4", "H4"),
            lowRespin(4, "H0", "H1", "H5", "H1"), lowRespin(2, "H0", "H3", "H5", "H3"),
            lowRespin(6, "H3", "H0", "H5", "H3"), lowRespin(11, "H4", "H0", "H5", "H4"),
            lowRespin(1, "H5", "H1", "H0", "H4"), lowRespin(6, "H5", "H4", "H0", "H4"),
            high(35, 0, "H0", "H0", "H0", 1), high(8, 0, "H0", "H0", "H1", 1),
            high(2, 0, "H0", "H1", "H0", 1), high(1, 0, "H4", "H4", "H1", 1),
            high(1, 0, "H5", "H1", "H4", 1),
            high(2, 1, "H0", "H0", "H0", 2), high(1, 1, "H0", "H1", "H0", 2),
            high(1, 1, "H4", "H3", "H4", 2), high(1, 1, "H4", "H4", "H1", 2),
            high(1, 1, "H5", "H3", "H1", 2), high(1, 1, "H5", "H3", "H3", 2),
            pattern(RoundFacts.respin(5, List.of("H5", "H0", "H1"), List.of("H3", "H4", "H1")), 1),
            pattern(RoundFacts.luckyWheel(List.of("H4", "H1", "H1"), 50), 1),
            pattern(RoundFacts.luckyWheel(List.of("H4", "H4", "H3"), 150), 1)
    );

    private final RandomGenerator random;
    private final Map<OutcomeType, Integer> outcomeWeights;
    private final Map<String, Integer> jointStateWeights;
    private final Map<OutcomeType, List<Pattern>> byOutcome;

    JointRoundModel(RandomGenerator random, int betProfile, Map<String, Integer> allOutcomeWeights,
                    Map<String, Integer> jointStateWeights) {
        this.random = Objects.requireNonNull(random, "random");
        if (betProfile != 1 && betProfile != 5) throw new IllegalArgumentException("未知下注档案");
        EnumMap<OutcomeType, Integer> checked = new EnumMap<>(OutcomeType.class);
        for (OutcomeType outcome : supportedOutcomes(betProfile)) {
            Integer weight = Objects.requireNonNull(allOutcomeWeights.get(outcomeKey(betProfile, outcome)),
                    "缺少结果类别权重: " + outcome);
            if (weight <= 0) throw new IllegalArgumentException("结果类别权重必须为正数: " + outcome);
            checked.put(outcome, weight);
        }
        this.outcomeWeights = Map.copyOf(checked);
        List<Pattern> active = PATTERNS.stream().filter(pattern -> pattern.betProfile() == betProfile).toList();
        for (Pattern pattern : PATTERNS) {
            Integer weight = Objects.requireNonNull(jointStateWeights.get(pattern.id()),
                    "缺少联合状态权重: " + pattern.id());
            if (weight <= 0) throw new IllegalArgumentException("联合状态权重必须为正数: " + pattern.id());
        }
        if (jointStateWeights.size() != PATTERNS.size()) throw new IllegalArgumentException("联合状态权重集合不完整");
        this.jointStateWeights = Map.copyOf(jointStateWeights);
        EnumMap<OutcomeType, List<Pattern>> grouped = new EnumMap<>(OutcomeType.class);
        for (OutcomeType outcome : supportedOutcomes(betProfile)) grouped.put(outcome, new ArrayList<>());
        for (Pattern pattern : active) grouped.get(pattern.outcome()).add(pattern);
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        this.byOutcome = Map.copyOf(grouped);
    }

    RoundFacts sample() {
        return sample(weightedOutcome());
    }

    RoundFacts sample(OutcomeType outcome) {
        List<Pattern> patterns = byOutcome.get(outcome);
        int total = patterns.stream().mapToInt(pattern -> jointStateWeights.get(pattern.id())).sum();
        int ticket = random.nextInt(total);
        for (Pattern pattern : patterns) {
            ticket -= jointStateWeights.get(pattern.id());
            if (ticket < 0) return pattern.facts();
        }
        throw new IllegalStateException("联合状态抽样越界");
    }

    static Map<String, Integer> defaultJointStateWeights() {
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (Pattern pattern : PATTERNS) weights.put(pattern.id(), pattern.trainingCount());
        return Map.copyOf(weights);
    }

    static Map<String, Integer> defaultOutcomeWeights() {
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        weights.put(outcomeKey(1, OutcomeType.ORDINARY_LOSS), 3442);
        weights.put(outcomeKey(1, OutcomeType.ORDINARY_WIN), 199);
        weights.put(outcomeKey(1, OutcomeType.MULTIPLIER_MD1), 815);
        weights.put(outcomeKey(1, OutcomeType.RESPIN_MD2), 76);
        weights.put(outcomeKey(5, OutcomeType.ORDINARY_LOSS), 45);
        weights.put(outcomeKey(5, OutcomeType.ORDINARY_WIN), 2);
        weights.put(outcomeKey(5, OutcomeType.MULTIPLIER_MD1), 7);
        weights.put(outcomeKey(5, OutcomeType.RESPIN_MD2), 1);
        weights.put(outcomeKey(5, OutcomeType.SCATTER_LUCKY_WHEEL_MD3), 2);
        return Map.copyOf(weights);
    }

    static String outcomeKey(int profile, OutcomeType outcome) {
        return (profile == 1 ? "bet-lt5." : "bet-gte5.") + outcome.name();
    }

    private OutcomeType weightedOutcome() {
        int total = outcomeWeights.values().stream().mapToInt(Integer::intValue).sum();
        int ticket = random.nextInt(total);
        for (Map.Entry<OutcomeType, Integer> entry : outcomeWeights.entrySet()) {
            ticket -= entry.getValue();
            if (ticket < 0) return entry.getKey();
        }
        throw new IllegalStateException("结果类别抽样越界");
    }

    private static List<OutcomeType> supportedOutcomes(int profile) {
        return profile == 1
                ? List.of(OutcomeType.ORDINARY_LOSS, OutcomeType.ORDINARY_WIN,
                    OutcomeType.MULTIPLIER_MD1, OutcomeType.RESPIN_MD2)
                : List.of(OutcomeType.ORDINARY_LOSS, OutcomeType.ORDINARY_WIN,
                    OutcomeType.MULTIPLIER_MD1, OutcomeType.RESPIN_MD2,
                    OutcomeType.SCATTER_LUCKY_WHEEL_MD3);
    }

    private static Pattern low(int count, int mode, String first, String second, int multiplier) {
        return pattern(mode == 0 ? RoundFacts.ordinary(List.of(first, second))
                : RoundFacts.multiplier(List.of(first, second), multiplier), count);
    }

    private static Pattern lowRespin(int count, String b0, String b1, String r0, String r1) {
        return pattern(RoundFacts.respin(List.of(b0, b1), List.of(r0, r1)), count);
    }

    private static Pattern high(int count, int mode, String first, String second, String third, int multiplier) {
        return pattern(mode == 0 ? RoundFacts.ordinary(5, List.of(first, second, third))
                : RoundFacts.multiplier(5, List.of(first, second, third), multiplier), count);
    }

    private static Pattern pattern(RoundFacts facts, int count) {
        return new Pattern(id(facts), facts, ResultUtil.analyze(facts).outcome(), count, facts.betProfile());
    }

    private static String id(RoundFacts facts) {
        String base = facts.baseSymbols().stream().map(value -> value.substring(1)).reduce("", String::concat);
        String respin = facts.respinSymbols().isEmpty() ? "none"
                : facts.respinSymbols().stream().map(value -> value.substring(1)).reduce("", String::concat);
        String wheel = facts.mode() == 3 ? Integer.toString(facts.luckyWheelAward()) : "none";
        return "b" + facts.betProfile() + "-m" + facts.mode() + "-base" + base
                + "-x" + facts.multiplier() + "-respin" + respin + "-wheel" + wheel;
    }

    private record Pattern(String id, RoundFacts facts, OutcomeType outcome, int trainingCount,
                           int betProfile) { }
}
