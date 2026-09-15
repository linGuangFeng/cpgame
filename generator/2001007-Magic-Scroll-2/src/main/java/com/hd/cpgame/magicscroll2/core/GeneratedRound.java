package com.hd.cpgame.magicscroll2.core;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeneratedRound implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String schemaVersion;
    private final String rulesVersion;
    private final String rulesHash;
    private final long deterministicSeed;
    private final String roundKey;
    private final RoundMode mode;
    private final BigDecimal paidBet;
    private final BigDecimal payout;
    private final List<RoundStep> steps;

    public GeneratedRound(String roundKey, RoundMode mode, BigDecimal paidBet, BigDecimal payout,
                          List<RoundStep> steps) {
        this(GameConstants.ROUND_SCHEMA_VERSION, GameConstants.RULES_VERSION, GameConstants.RULES_HASH,
                0L, roundKey, mode, paidBet, payout, steps);
    }

    public GeneratedRound(String schemaVersion, String rulesVersion, String rulesHash,
                          long deterministicSeed, String roundKey, RoundMode mode,
                          BigDecimal paidBet, BigDecimal payout, List<RoundStep> steps) {
        this.schemaVersion = schemaVersion;
        this.rulesVersion = rulesVersion;
        this.rulesHash = rulesHash;
        this.deterministicSeed = deterministicSeed;
        this.roundKey = roundKey;
        this.mode = mode;
        this.paidBet = paidBet;
        this.payout = payout;
        this.steps = Collections.unmodifiableList(new ArrayList<RoundStep>(steps));
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getRulesVersion() { return rulesVersion; }
    public String getRulesHash() { return rulesHash; }
    public long getDeterministicSeed() { return deterministicSeed; }
    public String getRoundKey() { return roundKey; }
    public RoundMode getMode() { return mode; }
    public BigDecimal getPaidBet() { return paidBet; }
    public BigDecimal getPayout() { return payout; }
    public List<RoundStep> getSteps() { return steps; }
}
