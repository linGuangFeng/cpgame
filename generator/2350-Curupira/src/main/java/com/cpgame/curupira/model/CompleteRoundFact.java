package com.cpgame.curupira.model;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.RulesContract;
import java.util.List;

/** 一局完整事实：付费起点到特殊终止的全部 Step，供 Redis member 与 Demo 投影。 */
public record CompleteRoundFact(
        long roundKey,
        Kind kind,
        EntryKind entry,
        List<FeatureStep> steps) {

    public enum EntryKind { PAID, BUY }

    public enum Kind {
        LOSS, WIN, EXPANDING_WILD, TRIGGER, FREE_EW, HOLD, BUY_FE, BUY_HS;

        public boolean ordinary() {
            return this == LOSS || this == WIN || this == EXPANDING_WILD;
        }

        public boolean specialPool() {
            return !ordinary();
        }

        public String resultPool() {
            return ordinary() ? GameRules.ORDINARY_POOL : GameRules.SPECIAL_POOL;
        }
    }

    public CompleteRoundFact {
        steps = List.copyOf(steps);
        if (roundKey <= 0 || kind == null || entry == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Invalid complete Round fact");
        }
        if (entry == EntryKind.BUY && kind != Kind.BUY_FE && kind != Kind.BUY_HS) {
            throw new IllegalArgumentException("Buy entry requires BUY_FE or BUY_HS");
        }
        if (entry == EntryKind.PAID && (kind == Kind.BUY_FE || kind == Kind.BUY_HS)) {
            throw new IllegalArgumentException("Paid entry cannot be a buy kind");
        }
        if (kind.ordinary() && steps.size() != 1) {
            throw new IllegalArgumentException("Ordinary Round is a single delivery");
        }
        if (kind == Kind.TRIGGER && steps.size() != 1) {
            throw new IllegalArgumentException("Trigger is a single live scatter board");
        }
        if ((kind == Kind.FREE_EW || kind == Kind.BUY_FE)
                && steps.size() != GameRules.FREE_EXPANDING_WILD_COUNT) {
            throw new IllegalArgumentException("Free Expanding Wild needs 6 steps");
        }
    }

    public int redisMultiplier() {
        return steps.stream().mapToInt(FeatureStep::redisUnits).sum();
    }

    public CompleteRound asOrdinaryRound() {
        if (!kind.ordinary()) throw new IllegalStateException("Not an ordinary Round");
        FeatureStep step = steps.get(0);
        return new CompleteRound(roundKey, RulesContract.GAME_ID, RulesContract.RULES_VERSION,
                RulesContract.RULES_HASH, GameRules.ORDINARY_POOL,
                List.of(new RoundStep(1, step.evaluatedBoard())));
    }
}
