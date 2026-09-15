package com.hd.cpgame.magicscroll2.core;

/** Generic safety limits. It deliberately contains no unproved symbol or mode weights. */
public final class GenerationPolicy {
    public static final int DEFAULT_CONSTRUCTIVE_ATTEMPTS = 5;
    public static final int DEFAULT_FALLBACK_SAMPLES = 10;
    public static final int DEFAULT_VALIDATION_SAMPLES = 100000;
    public static final int DEFAULT_MINIMUM_FIRST_SUCCESS_PERCENT = 90;
    public static final int DEFAULT_MAX_ROUND_STEPS = 30;
    public static final int DEFAULT_MAX_CONSECUTIVE_WINS = 10;
    public static final int DEFAULT_MAX_TOTAL_WIN_MULTIPLIER = 20000;
    public static final int DEFAULT_MAX_MEMBERS_PER_MULTIPLIER = 300;

    private final int constructiveAttempts;
    private final int fallbackSamples;
    private final int validationSamples;
    private final int minimumFirstSuccessPercent;
    private final int maxRoundSteps;
    private final int maxConsecutiveWins;
    private final int maxTotalWinMultiplier;
    private final int maxMembersPerMultiplier;

    public GenerationPolicy(int constructiveAttempts, int fallbackSamples, int validationSamples,
                            int minimumFirstSuccessPercent, int maxRoundSteps,
                            int maxConsecutiveWins, int maxTotalWinMultiplier,
                            int maxMembersPerMultiplier) {
        this.constructiveAttempts = positive(constructiveAttempts, "constructiveAttempts");
        this.fallbackSamples = positive(fallbackSamples, "fallbackSamples");
        this.validationSamples = positive(validationSamples, "validationSamples");
        if (minimumFirstSuccessPercent < 1 || minimumFirstSuccessPercent > 100) {
            throw new IllegalArgumentException("minimumFirstSuccessPercent must be 1..100");
        }
        this.minimumFirstSuccessPercent = minimumFirstSuccessPercent;
        this.maxRoundSteps = positive(maxRoundSteps, "maxRoundSteps");
        this.maxConsecutiveWins = positive(maxConsecutiveWins, "maxConsecutiveWins");
        this.maxTotalWinMultiplier = positive(maxTotalWinMultiplier, "maxTotalWinMultiplier");
        this.maxMembersPerMultiplier = positive(maxMembersPerMultiplier, "maxMembersPerMultiplier");
    }

    public static GenerationPolicy defaults() {
        return new GenerationPolicy(DEFAULT_CONSTRUCTIVE_ATTEMPTS, DEFAULT_FALLBACK_SAMPLES,
                DEFAULT_VALIDATION_SAMPLES, DEFAULT_MINIMUM_FIRST_SUCCESS_PERCENT,
                DEFAULT_MAX_ROUND_STEPS, DEFAULT_MAX_CONSECUTIVE_WINS,
                DEFAULT_MAX_TOTAL_WIN_MULTIPLIER, DEFAULT_MAX_MEMBERS_PER_MULTIPLIER);
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public int getConstructiveAttempts() { return constructiveAttempts; }
    public int getFallbackSamples() { return fallbackSamples; }
    public int getValidationSamples() { return validationSamples; }
    public int getMinimumFirstSuccessPercent() { return minimumFirstSuccessPercent; }
    public int getMaxRoundSteps() { return maxRoundSteps; }
    public int getMaxConsecutiveWins() { return maxConsecutiveWins; }
    public int getMaxTotalWinMultiplier() { return maxTotalWinMultiplier; }
    public int getMaxMembersPerMultiplier() { return maxMembersPerMultiplier; }
}
