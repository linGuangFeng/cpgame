package com.cpgame.luckynightmarket;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 正式的一次尝试生成器：先随机生成完整事实，再结算、分类和过滤；失败不在本次尝试内补抽。
 */
public final class DealingModel {
    public enum Scenario { ORDINARY, LUCKY_FEATURE, LUCKY_WHEEL }

    public record Attempt(Scenario scenario, RoundFact round, long units, String rejectionReason) {
        public boolean accepted() { return rejectionReason == null; }
    }

    private static final double ORDINARY_LOSS_SAMPLE_WEIGHT = 2353.0d;
    private static final double ORDINARY_WIN_SAMPLE_WEIGHT = 430.0d;
    private static final double FEATURE_SAMPLE_WEIGHT = 115.0d;
    private static final double WHEEL_SAMPLE_WEIGHT = 2.0d;

    private final SecureRandom random = new SecureRandom();
    private final Properties config;
    private final SymbolWeightSchedule symbolWeights;
    private final RuleBasedBoardGenerator boards;
    private final Map<String, Long> rejectionCounts = new LinkedHashMap<>();
    private long attemptedRounds;
    private long acceptedRounds;

    public DealingModel(Properties config) throws IOException {
        this.config = config;
        int retiredAttemptLimit = Integer.parseInt(config.getProperty("generation.attempt-limit", "100000"));
        if (retiredAttemptLimit < 1) throw new IllegalArgumentException("Invalid retired generation.attempt-limit");

        // 兼容旧配置；新生成器不再读取列模型，也不会在一次尝试中按此值回退或重抽。
        double retiredBackoff = Double.parseDouble(config.getProperty("model.column-backoff-probability", "0.15"));
        if (!Double.isFinite(retiredBackoff) || retiredBackoff < 0.0d || retiredBackoff > 1.0d) {
            throw new IllegalArgumentException("Invalid retired model.column-backoff-probability");
        }
        if (Integer.parseInt(config.getProperty("generation.feature-steps", "8")) != GameRuleCore.FEATURE_STEPS) {
            throw new IllegalArgumentException("The original feature has exactly eight steps");
        }

        this.symbolWeights = new SymbolWeightSchedule(config);
        this.boards = new RuleBasedBoardGenerator(random, symbolWeights);
    }

    public Map<String, Object> metadata() {
        return Json.map(
                "generator", "weighted-symbol-random-facts-then-settlement",
                "sampleBoardCandidates", 0,
                "sampleColumnCandidates", 0,
                "sampleWinningCombinations", 0,
                "sampleMultiplierTriples", 0,
                "symbolRange", List.of(0, 1, 2, 3, 4, 5, 6),
                "symbolPositions", "全部九个位置均按当前单牌权重随机",
                "reelMultipliers", List.of(1, 2, 3, 5, 10, 15),
                "wheelPrizes", List.of(1, 3, 5, 8, 10, 15, 20, 30, 50, 100, 200, 1000),
                "featureSteps", GameRuleCore.FEATURE_STEPS,
                "sampleRole", "只提供基础权重和场景频率参考，不定义牌面或倍率可达上限");
    }

    public static int bin(long units) {
        int[] bounds = {0, 49, 174, 249, 499, 999, 2499, 16000};
        for (int i = 0; i < bounds.length; i++) if (units <= bounds[i]) return i;
        throw new IllegalArgumentException("Units exceed rule support");
    }

    public void useBatch(long batchIndex) {
        symbolWeights.useBatch(batchIndex);
    }

    public String symbolWeightProfile() {
        return symbolWeights.boostedSymbolName();
    }

    /** 与正式入口完全相同的一次候选生成及配置过滤。 */
    public Attempt attempt() {
        return attemptScenario(weightedScenario(), true);
    }

    /** 测试规则可达性时强制场景，但仍只随机一次事实，不指定中奖结果，也不在内部重抽。 */
    Attempt attemptScenario(Scenario scenario, boolean applyConfiguredRange) {
        attemptedRounds++;
        RoundFact round = switch (scenario) {
            case ORDINARY -> ordinaryRound();
            case LUCKY_WHEEL -> new RoundFact(RoundFact.Mode.LUCKY_WHEEL, List.of(boards.wheel()));
            case LUCKY_FEATURE -> featureRound();
        };
        long units = GameRuleCore.totalUnits(round);

        if (round.mode() == RoundFact.Mode.LUCKY_FEATURE
                && GameRuleCore.evaluate(round.steps().get(0), true).units() != 0) {
            return reject(scenario, round, units, "FEATURE_START_NOT_ZERO");
        }
        if (units > (long) GameRuleCore.MAX_TOTAL_BET_MULTIPLIER * GameRuleCore.BET_BASE) {
            return reject(scenario, round, units, "ADVERTISED_MAX_EXCEEDED");
        }

        GameRuleCore.validate(round);
        if (units != ResultUtil.totalUnits(round)) {
            throw new IllegalStateException("Independent oracle disagreement");
        }
        if (applyConfiguredRange) {
            boolean special = scenario != Scenario.ORDINARY;
            long min = configuredRange(special, true);
            long max = configuredRange(special, false);
            if (units < min) return reject(scenario, round, units, special ? "SPECIAL_BELOW_CONFIGURED_MIN" : "NORMAL_BELOW_CONFIGURED_MIN");
            if (units > max) return reject(scenario, round, units, special ? "SPECIAL_ABOVE_CONFIGURED_MAX" : "NORMAL_ABOVE_CONFIGURED_MAX");
        }

        acceptedRounds++;
        return new Attempt(scenario, round, units, null);
    }

    private RoundFact ordinaryRound() {
        RoundFact.Step step = boards.ordinary();
        RoundFact.Mode mode = GameRuleCore.evaluate(step, false).units() == 0
                ? RoundFact.Mode.ORDINARY_LOSS : RoundFact.Mode.ORDINARY_WIN;
        return new RoundFact(mode, List.of(step));
    }

    private RoundFact featureRound() {
        List<RoundFact.Step> steps = new ArrayList<>(GameRuleCore.FEATURE_STEPS);
        for (int index = 0; index < GameRuleCore.FEATURE_STEPS; index++) {
            steps.add(boards.featureStep());
        }
        return new RoundFact(RoundFact.Mode.LUCKY_FEATURE, steps);
    }

    private Scenario weightedScenario() {
        double ordinary = ORDINARY_LOSS_SAMPLE_WEIGHT * modeScale("ORDINARY_LOSS")
                + ORDINARY_WIN_SAMPLE_WEIGHT * modeScale("ORDINARY_WIN");
        double feature = FEATURE_SAMPLE_WEIGHT * modeScale("LUCKY_FEATURE");
        double wheel = WHEEL_SAMPLE_WEIGHT * modeScale("LUCKY_WHEEL");
        double total = ordinary + feature + wheel;
        if (!(total > 0.0d) || !Double.isFinite(total)) {
            throw new IllegalArgumentException("No enabled generation scenarios");
        }
        double draw = random.nextDouble() * total;
        if ((draw -= ordinary) < 0.0d) return Scenario.ORDINARY;
        if ((draw -= feature) < 0.0d) return Scenario.LUCKY_FEATURE;
        return Scenario.LUCKY_WHEEL;
    }

    private double modeScale(String mode) {
        String key = "mode.weight." + mode;
        double value;
        try {
            value = Double.parseDouble(config.getProperty(key, "1").trim());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid mode weight: " + key, error);
        }
        if (!Double.isFinite(value) || value < 0.0d) throw new IllegalArgumentException("Invalid mode weight: " + key);
        return value;
    }

    private long configuredRange(boolean special, boolean minimum) {
        String key = special
                ? (minimum ? "range.special-min" : "range.special-max")
                : (minimum ? "range.normal-min" : "range.normal-max");
        String fallback = special ? (minimum ? "25" : "500") : (minimum ? "1" : "375");
        return Long.parseLong(config.getProperty(key, fallback));
    }

    private Attempt reject(Scenario scenario, RoundFact round, long units, String reason) {
        rejectionCounts.merge(reason, 1L, Long::sum);
        return new Attempt(scenario, round, units, reason);
    }

    public long attemptedRounds() { return attemptedRounds; }
    public long acceptedRounds() { return acceptedRounds; }
    public long rejectedRounds() { return attemptedRounds - acceptedRounds; }
    public Map<String, Long> rejectionCounts() { return Map.copyOf(rejectionCounts); }
}
