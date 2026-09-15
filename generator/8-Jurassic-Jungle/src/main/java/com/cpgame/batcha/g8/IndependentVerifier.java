package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Independent complete-member verifier used before Redis writes and after claims. */
public final class IndependentVerifier {
    private final BigDecimal maximumRoundMultiplier;
    private final int maximumSteps;
    private final ResultUtil resultUtil = new ResultUtil();

    public IndependentVerifier(BigDecimal maximumRoundMultiplier, int maximumSteps) {
        if (maximumRoundMultiplier == null || maximumRoundMultiplier.signum() <= 0 || maximumSteps < 1) {
            throw new IllegalArgumentException("verifier limits must be positive");
        }
        this.maximumRoundMultiplier = maximumRoundMultiplier;
        this.maximumSteps = maximumSteps;
    }

    public Verification verify(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) fail("raw game id is not 8");
        GameRuleCore.validateBet(round.betSize(), round.betLevel());
        equal(GameRuleCore.paidBet(round.betSize(), round.betLevel()), round.paidBet(), "paid bet");
        if (round.steps().isEmpty() || round.steps().getLast().spinStatus() != 1) {
            fail("Round does not end at spin_status=1");
        }
        if (round.steps().size() > maximumSteps) fail("step count exceeds observed maximum");
        BigDecimal sum = BigDecimal.ZERO;
        int collected = 0;
        for (int index = 0; index < round.steps().size(); index++) {
            Step step = round.steps().get(index);
            if (step.deliveryIndex() != index) fail("deliveryIndex is not contiguous");
            if (index == 0) equal(round.paidBet(), step.betAmount(), "paid start bet");
            else equal(BigDecimal.ZERO, step.betAmount(), "continuation bet");
            if (step.smallGameType() != (index == 0 ? 0 : 1)) fail("small_game_type does not match delivery");
            ResultUtil.BoardResult independent = resultUtil.evaluate(step.symbols(), round.betSize(), round.betLevel());
            if (!resultUtil.sameMatches(independent.matches(), step.winMatches())) {
                fail("win_match_key_list differs from independent cluster reconstruction");
            }
            equal(independent.winAmount(), step.winAmount(), "Step win_amount");
            collected = Math.min(GameRuleCore.COLLECTOR_CAP,
                collected + GameRuleCore.uniqueWinningCells(independent.matches()).size());
            if (step.removeNum() != collected) fail("remove_num differs from unique exploded cells cap 70");
            sum = sum.add(independent.winAmount());
            equal(sum, step.winAmountSum(), "win_amount_sum");
            GameRuleCore.legalWilds(step.symbols(), index == 0);
            for (ExtraCell extra : step.extra()) {
                if (!GameRuleCore.isLow(extra.oldSymbol())) fail("extra old symbol is not low-paying");
                if (!GameRuleCore.isHigh(step.symbols().get(GameRuleCore.boardIndex(extra.coord())))) {
                    fail("extra target is not a high-paying symbol");
                }
            }
            if (index > 0) verifyTransition(round.steps().get(index - 1), step);
        }
        equal(sum, round.payout(), "round payout");
        equal(sum.divide(round.paidBet(), 8, RoundingMode.HALF_UP), round.multiplier(), "multiplier");
        if (round.multiplier().compareTo(maximumRoundMultiplier) > 0) fail("multiplier exceeds cap");
        int units = sum.multiply(BigDecimal.TEN).divide(round.paidBet(), 0, RoundingMode.UNNECESSARY).intValueExact();
        if (units != round.unitRatio()) fail("integer unit ratio does not match payout/bet*10");
        if (resultUtil.mode(round) != round.mode()) fail("mode classification differs");
        return new Verification(round.mode(), round.steps().size(), round.payout());
    }

    public void verifyCodecRoundTrip(CompleteRound round, MemberCodec codec) {
        CompleteRound decoded = codec.decode(codec.encode(round));
        verify(decoded);
        if (decoded.mode() != round.mode() || decoded.steps().size() != round.steps().size()) {
            fail("codec round-trip changed mode or step count");
        }
        for (int i = 0; i < round.steps().size(); i++) {
            if (!round.steps().get(i).symbols().equals(decoded.steps().get(i).symbols())) {
                fail("codec round-trip changed board");
            }
        }
    }

    private void verifyTransition(Step previous, Step next) {
        ResultUtil.BoardResult prior = resultUtil.evaluate(previous.symbols(), previous.betSize(), previous.betLevel());
        if (prior.winAmount().signum() > 0) {
            List<Integer> removed = GameRuleCore.uniqueWinningCells(prior.matches());
            List<String> holes = GameRuleCore.cascadeRetainAndHoles(previous.symbols(), removed);
            for (int i = 0; i < GameRuleCore.CELLS; i++) {
                if (holes.get(i) == null) continue;
                if (previous.removeStatus() == 4) continue; // giant refill then transform
                if (!holes.get(i).equals(next.symbols().get(i))) fail("cascade retain/fall does not match next board");
            }
        }
        if (previous.spinStatus() != 0) fail("non-terminal step followed another terminal step");
    }

    private static void equal(BigDecimal left, BigDecimal right, String label) {
        if (left.compareTo(right) != 0) fail(label + " mismatch: " + left + " vs " + right);
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    public record Verification(RoundMode mode, int steps, BigDecimal payout) { }
}
