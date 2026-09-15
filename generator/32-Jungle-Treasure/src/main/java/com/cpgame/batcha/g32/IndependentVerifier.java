package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.util.List;

public final class IndependentVerifier {
    private final BigDecimal maximumRoundMultiplier;
    private final int maximumCascades;
    private final int maximumSpecialSpins;
    private final ResultUtil resultUtil = new ResultUtil();

    public IndependentVerifier(BigDecimal maximumRoundMultiplier, int maximumCascades, int maximumSpecialSpins) {
        if (maximumRoundMultiplier == null || maximumRoundMultiplier.signum() <= 0
            || maximumCascades < 1 || maximumSpecialSpins < 1) {
            throw new IllegalArgumentException("verifier limits must be positive");
        }
        this.maximumRoundMultiplier = maximumRoundMultiplier;
        this.maximumCascades = maximumCascades;
        this.maximumSpecialSpins = maximumSpecialSpins;
    }

    public Verification verify(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) fail("raw game id is not 32");
        GameRuleCore.validateBet(round.betSize(), round.betLevel());
        equal(GameRuleCore.paidBet(round.betSize(), round.betLevel()), round.paidBet(), "paid bet");
        if (round.steps().isEmpty() || round.steps().getLast().spinStatus() != 1
            || round.steps().getLast().freeSpinNum() != round.steps().getLast().nowFreeSpinCount()) {
            fail("Round does not end at spin_status=1 with fsn==nfsc");
        }
        int cascades = 0;
        int maxFree = 0;
        for (int i = 0; i < round.steps().size(); i++) {
            Step step = round.steps().get(i);
            if (step.deliveryIndex() != i) fail("deliveryIndex is not contiguous");
            if (i == 0) equal(round.paidBet(), step.betAmount(), "paid start bet");
            else equal(BigDecimal.ZERO, step.betAmount(), "continuation bet");
            ResultUtil.BoardResult independent = resultUtil.evaluate(step.tokens(), round.betSize(),
                round.betLevel(), step.roundPayX());
            if (independent.matches().size() != step.winMatches().size()) fail("win match count differs");
            for (int m = 0; m < independent.matches().size(); m++) {
                WinMatch left = independent.matches().get(m);
                WinMatch right = step.winMatches().get(m);
                if (!left.symbolKey().equals(right.symbolKey()) || !left.reelGroups().equals(right.reelGroups())) {
                    fail("wmkl differs from independent ways reconstruction");
                }
            }
            equal(independent.winAmount(), step.winAmount(), "Step win_amount");
            if ((step.spinStatus() == 0) != (independent.winAmount().signum() > 0)) {
                fail("ss does not agree with ways win");
            }
            if (!GameRuleCore.legalSpecials(step.tokens())) fail("special-symbol cap exceeded");
            if (step.roundPayX() < 1 || step.roundPayX() > GameRuleCore.MAX_RPX) fail("rpx cap exceeded");
            if (step.spinStatus() == 0) {
                cascades++;
                if (cascades > maximumCascades) fail("cascade limit exceeded");
            } else {
                cascades = 0;
            }
            maxFree = Math.max(maxFree, step.freeSpinNum());
        }
        if (maxFree > maximumSpecialSpins) fail("free-spin grant cap exceeded");
        equal(round.steps().getLast().roundWinAmount(), round.payout(), "round payout");
        if (round.payout().compareTo(maximumRoundMultiplier.multiply(round.paidBet())) > 0) {
            fail("round multiplier cap exceeded");
        }
        if (GameRuleCore.classify(round.steps(), round.payout()) != round.mode()) fail("mode mismatch");
        return new Verification(round.mode(), round.payout(), round.multiplier());
    }

    public void verifyCodecRoundTrip(CompleteRound round, MemberCodec codec) {
        CompleteRound decoded = codec.decode(codec.encode(round));
        verify(decoded);
        equal(round.payout(), decoded.payout(), "codec payout");
        if (decoded.steps().size() != round.steps().size()) fail("codec step count");
    }

    private static void equal(BigDecimal expected, BigDecimal actual, String label) {
        if (expected.compareTo(actual) != 0) fail(label + " expected " + expected + " actual " + actual);
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    public record Verification(RoundMode mode, BigDecimal payout, BigDecimal multiplier) { }
}
