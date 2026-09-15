package com.cpgame.coinmastergo.core;

import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates a complete Round from board facts instead of trusting its projected fields. */
public final class RoundValidator {
    public void validate(RoundPlan round) {
        if (round == null || round.deliveries == null || round.deliveries.isEmpty()) {
            throw new IllegalArgumentException("complete Round cannot be empty");
        }
        if (round.betAmount == null || round.betAmount.compareTo(GameRules.betAmount(round.betLevel, round.betSize)) != 0) {
            throw new IllegalArgumentException("Round betAmount differs from current GameRuleCore");
        }

        BigDecimal rwa = BigDecimal.ZERO;
        BigDecimal frwa = BigDecimal.ZERO;
        int expectedFsn = 0;
        int expectedNfsc = 0;
        int stepCount = 0;
        int initialAward = 0;
        boolean goldenWin = false;
        SpinStep last = null;
        List<SpinStep> allSteps = round.deliveries.stream().flatMap(delivery -> delivery.steps.stream()).toList();

        for (int deliveryIndex = 0; deliveryIndex < round.deliveries.size(); deliveryIndex++) {
            RoundDelivery delivery = round.deliveries.get(deliveryIndex);
            boolean free = deliveryIndex > 0;
            String expectedMode = free ? "FREE" : "BASE";
            if (!expectedMode.equals(delivery.mode) || delivery.steps == null || delivery.steps.isEmpty()) {
                throw new IllegalArgumentException("Round must contain exactly one leading BASE Delivery followed by FREE Deliveries");
            }
            if (free) {
                if (expectedFsn <= expectedNfsc) throw new IllegalArgumentException("FREE Delivery exists without an outstanding award");
                expectedNfsc++;
            }

            CoinMasterResultUtil.Evaluation terminalBoard = CoinMasterResultUtil.evaluate(
                    delivery.steps.getLast().rskl, round.betLevel, round.betSize,
                    expectedRpx(free, delivery.steps.size() - 1));
            int awardAtTerminal = GameRules.freeAward(terminalBoard.scatterCount());
            if (!free) initialAward = awardAtTerminal;
            SpinStep previousInDelivery = null;

            for (int index = 0; index < delivery.steps.size(); index++) {
                SpinStep step = delivery.steps.get(index);
                boolean deliveryTerminal = index + 1 == delivery.steps.size();
                CoinMasterResultUtil.Evaluation evaluated = CoinMasterResultUtil.evaluate(
                        step.rskl, round.betLevel, round.betSize, step.rpx);
                validateDerivedWinFields(step, evaluated);
                validateCardCoordinates(step);

                int expectedGt = free ? 2 : 1;
                int expectedSmallGameType = free ? 2 : (index == 0 ? 0 : 1);
                int expectedStepRpx = expectedRpx(free, index);
                int responseFsn = deliveryTerminal ? expectedFsn + awardAtTerminal : expectedFsn;
                if (step.gt != expectedGt || step.small_game_type != expectedSmallGameType
                        || step.rpx != expectedStepRpx || step.nfsc != (free ? expectedNfsc : 0)
                        || step.fsn != responseFsn) {
                    throw new IllegalArgumentException("Delivery state/rpx/fsn/nfsc progression is invalid");
                }
                BigDecimal expectedDebit = stepCount == 0 ? round.betAmount : BigDecimal.ZERO;
                if (step.ba == null || step.ba.compareTo(expectedDebit) != 0) {
                    throw new IllegalArgumentException("only the first paid Step may debit the Round bet");
                }
                int expectedSs = evaluated.totalWin().signum() > 0 ? 0 : 1;
                if (step.ss != expectedSs || (deliveryTerminal ? step.ss != 1 : step.ss != 0)) {
                    throw new IllegalArgumentException("winning Step/ss/Delivery termination relation is invalid");
                }

                rwa = CoinMasterResultUtil.money(rwa.add(step.wa));
                if (free) frwa = CoinMasterResultUtil.money(frwa.add(step.wa));
                if (step.rwa == null || step.rwa.compareTo(rwa) != 0
                        || step.frwa == null || step.frwa.compareTo(frwa) != 0) {
                    throw new IllegalArgumentException("rwa/frwa accumulator mismatch");
                }
                if (previousInDelivery != null) validateCascadeTransition(previousInDelivery, step);
                previousInDelivery = step;
                goldenWin |= hasWinningGolden(step, evaluated);
                last = step;
                stepCount++;
            }
            expectedFsn += awardAtTerminal;
            if (expectedFsn > GameRules.CAPTURED_MAX_FSN) {
                throw new IllegalArgumentException("fsn exceeds the current-game captured boundary");
            }
        }

        if (expectedNfsc != expectedFsn || last == null || last.ss != 1
                || !(last.fsn == 0 || (last.gt == 2 && last.nfsc == last.fsn))) {
            throw new IllegalArgumentException("Round does not end on the evidenced terminal predicate");
        }
        if (round.totalWin == null || rwa.compareTo(round.totalWin) != 0) {
            throw new IllegalArgumentException("Round totalWin mismatch");
        }
        for (int index = 0; index < allSteps.size(); index++) {
            BigDecimal expectedBalance = index + 1 == allSteps.size()
                    ? round.postDebitBalance.add(round.totalWin) : round.postDebitBalance;
            if (allSteps.get(index).pb == null
                    || new BigDecimal(allSteps.get(index).pb).compareTo(expectedBalance) != 0) {
                throw new IllegalArgumentException("delayed settlement/pb mismatch");
            }
        }

        String expectedScenario = expectedFsn > 0
                ? (expectedFsn > initialAward ? RoundScenario.FREE_RETRIGGER.name() : RoundScenario.FREE_SPINS.name())
                : (round.totalWin.signum() == 0 ? RoundScenario.LOSS.name()
                : (goldenWin ? RoundScenario.GOLDEN_TRANSFORM.name() : RoundScenario.BASE_WIN.name()));
        if (!expectedScenario.equals(round.scenario)) {
            throw new IllegalArgumentException("Round scenario/History classification differs from board facts");
        }
        if (RoundScenario.LOSS.name().equals(expectedScenario)
                && (stepCount != 1 || last.wa.signum() != 0 || !last.wskl.isEmpty())) {
            throw new IllegalArgumentException("runtimeIndependentLoss boundary violated");
        }
    }

    private static int expectedRpx(boolean free, int stepIndex) {
        List<Integer> values = free ? GameRules.FREE_RPX : GameRules.BASE_RPX;
        return values.get(Math.min(stepIndex, values.size() - 1));
    }

    private static void validateDerivedWinFields(SpinStep step, CoinMasterResultUtil.Evaluation evaluated) {
        if (step.wa == null || step.wa.compareTo(evaluated.totalWin()) != 0) {
            throw new IllegalArgumentException("wa does not match ResultUtil");
        }
        List<String> symbols = evaluated.matches().stream().map(match -> match.symbol).toList();
        List<List<List<Integer>>> coordinates = evaluated.matches().stream().map(match -> match.coordinates).toList();
        if (!symbols.equals(step.wskl) || !coordinates.equals(step.wmkl)) {
            throw new IllegalArgumentException("wskl/wmkl differs from board-derived exact matches");
        }
    }

    private static void validateCardCoordinates(SpinStep step) {
        if (step.gfl == null) throw new IllegalArgumentException("gfl must be an array");
        if (step.silverCardCoordinates == null) {
            throw new IllegalArgumentException("explicit Silver card state must be an array");
        }
        Set<Integer> eligible = new HashSet<>(CardMaterialState.eligibleCoordinates(step.rskl));
        Set<Integer> unique = new HashSet<>();
        for (Integer coordinate : step.gfl) {
            if (coordinate == null || !unique.add(coordinate)) {
                throw new IllegalArgumentException("gfl contains a null or duplicate coordinate");
            }
            int reel = coordinate / 10;
            int row = coordinate % 10;
            if (reel < 1 || reel > 3 || row < 0 || row >= GameRules.TRANSPORT_ROWS) {
                throw new IllegalArgumentException("gfl coordinate is outside evidenced golden reels/rows");
            }
            String symbol = step.rskl.get(reel * GameRules.TRANSPORT_ROWS + row);
            if (!GameRules.PAYING_SYMBOLS.contains(symbol)) {
                throw new IllegalArgumentException("gfl may only mark a paying symbol");
            }
        }
        Set<Integer> materialPartition = new HashSet<>(unique);
        for (Integer coordinate : step.silverCardCoordinates) {
            if (coordinate == null || !materialPartition.add(coordinate) || !eligible.contains(coordinate)) {
                throw new IllegalArgumentException("Silver coordinates contain an invalid, duplicate or Gold coordinate");
            }
        }
        if (!materialPartition.equals(eligible)) {
            throw new IllegalArgumentException("every eligible inner-reel card must be explicitly Silver or Gold");
        }
    }

    private static boolean hasWinningGolden(SpinStep step, CoinMasterResultUtil.Evaluation evaluated) {
        Set<Integer> gold = new HashSet<>(step.gfl);
        return evaluated.matches().stream().map(match -> match.coordinates).flatMap(List::stream)
                .flatMap(List::stream).anyMatch(raw -> gold.contains(raw + 1));
    }

    /** Validates Silver removal/refill, stable material fall, Golden-to-WILD fall, and gfl movement. */
    public static void validateCascadeTransition(SpinStep previous, SpinStep successor) {
        if (previous.ss != 0) throw new IllegalArgumentException("only a non-terminal winning Step may cascade");
        Set<Integer> winning = new HashSet<>();
        previous.wmkl.forEach(match -> match.forEach(winning::addAll));
        if (winning.isEmpty()) throw new IllegalArgumentException("non-terminal cascade Step has no winning coordinates");

        List<Integer> previousSilver = previous.silverCardCoordinates == null
                || previous.silverCardCoordinates.isEmpty()
                ? CardMaterialState.decodeSilverCoordinates(previous.rskl, previous.gfl)
                : previous.silverCardCoordinates;
        List<Integer> successorSilver = successor.silverCardCoordinates == null
                || successor.silverCardCoordinates.isEmpty()
                ? CardMaterialState.decodeSilverCoordinates(successor.rskl, successor.gfl)
                : successor.silverCardCoordinates;
        List<Integer> expectedSilver = new ArrayList<>();
        List<Integer> expectedGolden = new ArrayList<>();
        for (int reel = 0; reel < GameRules.REELS; reel++) {
            List<Survivor> survivors = new ArrayList<>();
            int removed = 0;
            for (int position = 0; position < GameRules.TRANSPORT_ROWS; position++) {
                int directCoordinate = reel * 10 + position;
                int rawCoordinate = reel * 10 + position - 1;
                boolean winner = position > 0 && winning.contains(rawCoordinate);
                boolean goldenWinner = winner && previous.gfl.contains(rawCoordinate + 1);
                if (winner && !goldenWinner) {
                    removed++;
                    continue;
                }
                survivors.add(new Survivor(goldenWinner ? "WILD"
                        : previous.rskl.get(reel * GameRules.TRANSPORT_ROWS + position),
                        previousSilver.contains(directCoordinate) && !goldenWinner,
                        previous.gfl.contains(directCoordinate) && !goldenWinner));
            }
            List<String> newReel = successor.rskl.subList(
                    reel * GameRules.TRANSPORT_ROWS, (reel + 1) * GameRules.TRANSPORT_ROWS);
            for (int position = 0; position < removed; position++) {
                if (reel >= 1 && reel <= 3 && GameRules.PAYING_SYMBOLS.contains(newReel.get(position))) {
                    expectedSilver.add(reel * 10 + position);
                }
            }
            for (int index = 0; index < survivors.size(); index++) {
                int newPosition = removed + index;
                Survivor survivor = survivors.get(index);
                if (!survivor.symbol.equals(newReel.get(newPosition))) {
                    throw new IllegalArgumentException("cascade survivor/Golden WILD drop mismatch on reel " + reel);
                }
                if (survivor.silver) expectedSilver.add(reel * 10 + newPosition);
                if (survivor.golden) expectedGolden.add(reel * 10 + newPosition);
            }
        }
        if (!expectedSilver.equals(successorSilver)) {
            throw new IllegalArgumentException("Silver material does not follow survivors/refills across cascade");
        }
        if (!expectedGolden.equals(successor.gfl)) {
            throw new IllegalArgumentException("gfl does not follow surviving symbols across cascade");
        }
    }

    private record Survivor(String symbol, boolean silver, boolean golden) { }
}
