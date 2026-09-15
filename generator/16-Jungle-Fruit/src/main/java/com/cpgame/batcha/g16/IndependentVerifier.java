package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Independent complete-member verifier used before Redis writes and after atomic claims. */
public final class IndependentVerifier {
    private final BigDecimal maximumRoundMultiplier;
    private final int maximumCascades;
    private final int maximumSpecialSpins;
    private final ResultUtil resultUtil = new ResultUtil();

    public IndependentVerifier(BigDecimal maximumRoundMultiplier,
                               int maximumCascades, int maximumSpecialSpins) {
        if (maximumRoundMultiplier == null || maximumRoundMultiplier.signum() <= 0
            || maximumCascades < 1 || maximumSpecialSpins < 1) {
            throw new IllegalArgumentException("verifier limits must be positive");
        }
        this.maximumRoundMultiplier = maximumRoundMultiplier;
        this.maximumCascades = maximumCascades;
        this.maximumSpecialSpins = maximumSpecialSpins;
    }

    public Verification verify(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) fail("raw game id is not 16");
        GameRuleCore.validateBet(round.betSize(), round.betLevel());
        BigDecimal expectedPaidBet = round.betSize()
            .multiply(BigDecimal.valueOf(20L * round.betLevel())).stripTrailingZeros();
        equal(expectedPaidBet, round.paidBet(), "paid bet");
        if (round.steps().isEmpty() || round.steps().getLast().spinStatus() != 1) {
            fail("Round does not end at spin_status=1");
        }

        BigDecimal phaseWin = BigDecimal.ZERO;
        BigDecimal payout = BigDecimal.ZERO;
        int cascades = 0;
        int maximumObservedFreeSpin = 0;
        for (int index = 0; index < round.steps().size(); index++) {
            Step step = round.steps().get(index);
            if (step.deliveryIndex() != index) fail("deliveryIndex is not contiguous");
            if (index == 0) equal(round.paidBet(), step.betAmount(), "paid start bet");
            else equal(BigDecimal.ZERO, step.betAmount(), "continuation bet");
            ResultUtil.BoardResult independent = resultUtil.evaluate(step.symbols(), round.betSize(), round.betLevel());
            if (!independent.matches().equals(step.winMatches())) fail("win_match_key_list differs from independent whole-board count");
            equal(independent.winAmount(), step.winAmount(), "Step win_amount");
            if ((step.spinStatus() == 0) != (independent.winAmount().signum() > 0))
                fail("original terminal/pay relationship violated");
            if (!step.symbols().stream().filter(GameRuleCore::isMultiplier).toList().equals(step.winXKeys()))
                fail("win_x_key_list differs from board");
            int allScat = 0, allX = 0;
            for (int col = 0; col < 6; col++) {
                int columnScat = 0, columnX = 0;
                for (int row = 0; row < 6; row++) {
                    String symbol = step.symbols().get(col * 6 + row);
                    if (symbol.equals("Scat")) { columnScat++; allScat++; }
                    if (symbol.startsWith("X")) { columnX++; allX++; }
                }
                if (columnScat > 1 || columnX > 2) fail("observed column special-symbol cap exceeded");
            }
            if (allScat > 5 || allX > 5) fail("observed board special-symbol cap exceeded");
            phaseWin = phaseWin.add(independent.winAmount());
            if (step.spinStatus() == 0) {
                cascades++;
                if (cascades > maximumCascades) fail("cascade limit exceeded");
                equal(phaseWin, step.roundWinAmount(), "in-progress round_win_amount");
            } else {
                int multiplierSum = step.symbols().stream().filter(GameRuleCore::isMultiplier)
                    .mapToInt(GameRuleCore::multiplierValue).sum();
                BigDecimal terminal = multiplierSum > 0 && phaseWin.signum() > 0
                    ? phaseWin.multiply(BigDecimal.valueOf(multiplierSum)) : phaseWin;
                equal(terminal, step.roundWinAmount(), "terminal round_win_amount");
                payout = payout.add(terminal);
                phaseWin = BigDecimal.ZERO;
                cascades = 0;
            }
            if (index > 0 && round.steps().get(index - 1).spinStatus() == 0) {
                verifyColumnDrop(round.steps().get(index - 1), step);
            }
            maximumObservedFreeSpin = Math.max(maximumObservedFreeSpin, step.freeSpinNum());
            if (step.nowFreeSpinCount() > step.freeSpinNum()) fail("free-spin progress exceeds granted count");
        }
        if (maximumObservedFreeSpin > maximumSpecialSpins) fail("special spin limit exceeded");
        equal(payout, round.payout(), "Round payout");
        equal(resultUtil.payout(round), round.payout(), "terminal-Step payout sum");
        if (resultUtil.mode(round) != round.mode()) fail("Round mode classification differs");
        BigDecimal multiplier = payout.divide(round.paidBet(), 8, RoundingMode.HALF_UP).stripTrailingZeros();
        equal(multiplier, round.multiplier(), "Round multiplier");
        if (multiplier.compareTo(maximumRoundMultiplier) > 0) fail("maximum Round multiplier exceeded");
        verifyModeState(round);
        return new Verification(GameRuleCore.RULES_VERSION, GameRuleCore.RULES_HASH,
            round.mode(), round.multiplier(), round.steps().size());
    }

    public Verification verifyCodecRoundTrip(CompleteRound round, MemberCodec codec) {
        Verification before = verify(round);
        CompleteRound decoded = codec.decode(codec.encode(round));
        if (!round.equals(decoded)) fail("minimal member codec round-trip differs");
        return before;
    }

    private void verifyModeState(CompleteRound round) {
        boolean hasMary = round.steps().stream().anyMatch(step -> step.smallGameType() == 1);
        boolean hasFree = round.steps().stream().anyMatch(step -> step.smallGameType() == 2 || step.freeSpinNum() > 0);
        switch (round.mode()) {
            case LOSS -> {
                if (round.payout().signum() != 0 || hasMary || hasFree || round.steps().size() != 1
                    || count(round.steps().getFirst().symbols(), "Scat") >= 3) {
                    fail("LOSS must be an independent paid single Step");
                }
            }
            case WIN -> {
                if (round.payout().signum() <= 0 || hasMary || hasFree) fail("WIN state is inconsistent");
            }
            case MARY -> {
                if (round.payout().signum() <= 0 || !hasMary || hasFree) fail("Mary is only small_game_type=1");
                if (round.steps().getFirst().spinStatus() != 0 || round.steps().getFirst().smallGameType() != 0) {
                    fail("Mary must begin with a paid winning continuation Step of small_game_type=0");
                }
                if (round.steps().subList(1, round.steps().size()).stream()
                    .anyMatch(step -> step.smallGameType() != 1)) {
                    fail("Mary continuation Steps must use small_game_type=1");
                }
            }
            case FREE -> {
                if (round.payout().signum() <= 0 || !hasFree) fail("Free is small_game_type=2 or free_spin_num>0");
                Step trigger = round.steps().getFirst();
                int scatter = count(trigger.symbols(), "Scat");
                int expected = switch (scatter) { case 3 -> 10; case 4 -> 12; case 5 -> 14; default -> -1; };
                if (expected < 0 || trigger.freeSpinNum() != expected) fail("Free trigger must map 3/4/5 Scat to 10/12/14 spins");
                int total = trigger.freeSpinNum();
                for (int index = 1; index < round.steps().size(); index++) {
                    Step step = round.steps().get(index);
                    if (step.freeSpinNum() == total) continue;
                    if (step.spinStatus() != 1 || step.freeSpinNum() != total + 5
                        || count(step.symbols(), "Scat") != 2) {
                        fail("Free retrigger must add exactly five at a terminal Step containing two Scat");
                    }
                    total = step.freeSpinNum();
                }
                Step terminal = round.steps().getLast();
                if (terminal.nowFreeSpinCount() != terminal.freeSpinNum()) {
                    fail("Free Round must end when now_free_spin_count equals free_spin_num");
                }
            }
        }
    }

    private void verifyColumnDrop(Step previous, Step next) {
        boolean[] removed = new boolean[36];
        previous.winMatches().forEach(match -> match.indices().forEach(position ->
            removed[GameRuleCore.boardIndex(position)] = true));
        if (previous.winMatches().isEmpty()) fail("spin_status=0 Step has no winning symbol to remove");
        for (int column = 0; column < 6; column++) {
            java.util.ArrayList<String> retained = new java.util.ArrayList<>();
            for (int row = 0; row < 6; row++) {
                int cell = column * 6 + row;
                if (!removed[cell]) retained.add(previous.symbols().get(cell));
            }
            int offset = 6 - retained.size();
            for (int row = 0; row < retained.size(); row++) {
                if (!retained.get(row).equals(next.symbols().get(column * 6 + offset + row))) {
                    fail("cascade does not preserve old non-winning symbols at column bottom");
                }
            }
        }
    }

    private int count(List<String> symbols, String wanted) {
        return (int) symbols.stream().filter(wanted::equals).count();
    }

    private void equal(BigDecimal expected, BigDecimal actual, String label) {
        if (expected.compareTo(actual) != 0) fail(label + " differs: expected " + expected + " but got " + actual);
    }

    private void fail(String message) {
        throw new IllegalArgumentException("INVALID_COMPLETE_ROUND: " + message);
    }

    public record Verification(String rulesVersion, String rulesHash, RoundMode mode,
                               BigDecimal multiplier, int stepCount) { }
}
