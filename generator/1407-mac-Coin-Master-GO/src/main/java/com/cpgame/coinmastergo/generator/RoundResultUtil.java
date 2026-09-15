package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independently reverses actual pool, multiplier and terminal state from a complete candidate. */
public final class RoundResultUtil {
    public RoundAnalysis analyze(RoundPlan round) {
        if (round == null || round.deliveries == null || round.deliveries.isEmpty()) throw new IllegalArgumentException("Round has no deliveries");
        BigDecimal total = BigDecimal.ZERO;
        int steps = 0, winRun = 0, longestWinRun = 0, priorFsn = 0, retriggers = 0;
        int goldenTransformCount = 0, cascadeTransitionCount = 0;
        SpinStep last = null;
        List<SpinStep> allSteps = round.deliveries.stream().flatMap(delivery -> delivery.steps.stream()).toList();
        int freeSteps = (int) round.deliveries.stream()
                .filter(delivery -> !delivery.steps.isEmpty() && delivery.steps.getFirst().gt == 2).count();
        Map<SpinStep, SpinStep> successorsWithinDelivery = new IdentityHashMap<>();
        for (RoundDelivery delivery : round.deliveries) {
            for (int index = 0; index + 1 < delivery.steps.size(); index++) {
                successorsWithinDelivery.put(delivery.steps.get(index), delivery.steps.get(index + 1));
            }
        }
        for (int stepIndex = 0; stepIndex < allSteps.size(); stepIndex++) {
                SpinStep step = allSteps.get(stepIndex);
                SpinStep successor = successorsWithinDelivery.get(step);
                BigDecimal boardWin = evaluateWays(step, round.betLevel, round.betSize);
                if (boardWin.compareTo(step.wa) != 0) throw new IllegalArgumentException("candidate wa cannot be reversed from board");
                goldenTransformCount += verifyGoldenContinuity(step, successor);
                if (step.ss == 0) cascadeTransitionCount += verifyCascadeContinuity(step, successor);
                total = total.add(boardWin);
                winRun = boardWin.signum() > 0 ? winRun + 1 : 0;
                longestWinRun = Math.max(longestWinRun, winRun);
                if (step.gt == 2) {
                    if (priorFsn > 0 && step.fsn > priorFsn) retriggers++;
                    priorFsn = Math.max(priorFsn, step.fsn);
                } else if (step.fsn > 0) priorFsn = step.fsn;
                last = step;
                steps++;
        }
        if (last == null || last.ss != 1 || !(last.fsn == 0 || (last.gt == 2 && last.nfsc == last.fsn))) {
            throw new IllegalArgumentException("candidate is not a complete Round");
        }
        total = money(total);
        if (total.compareTo(round.totalWin) != 0) throw new IllegalArgumentException("Round total differs from reversed total");
        BigDecimal unitBet = round.betSize.multiply(BigDecimal.valueOf(round.betLevel));
        BigDecimal multiplier = unitBet.signum() == 0 ? BigDecimal.ZERO
                : total.divide(unitBet, 0, RoundingMode.UNNECESSARY).stripTrailingZeros();
        ResultPool pool = freeSteps > 0 ? (retriggers > 0 ? ResultPool.FREE_SPIN_RETRIGGER : ResultPool.FREE_SPINS)
                : (total.signum() > 0 ? ResultPool.BASE_WIN : ResultPool.LOSS);
        return new RoundAnalysis(pool, multiplier, total, steps, freeSteps, retriggers, longestWinRun,
                cascadeTransitionCount, goldenTransformCount, true, GameRules.RULES_VERSION, GameRules.RULES_HASH);
    }

    private int verifyGoldenContinuity(SpinStep step, SpinStep successor) {
        if (step.gfl == null) throw new IllegalArgumentException("gfl must be an array");
        Set<Integer> unique = new HashSet<>();
        for (Integer coordinate : step.gfl) {
            if (coordinate == null || !unique.add(coordinate)) throw new IllegalArgumentException("gfl contains a null or duplicate coordinate");
            int reel = coordinate / 10;
            int row = coordinate % 10;
            if (reel < 1 || reel > 3 || row < 0 || row >= GameRules.TRANSPORT_ROWS) {
                throw new IllegalArgumentException("gfl coordinate is outside evidenced golden reels/rows: " + coordinate);
            }
            if (!GameRules.PAYING_SYMBOLS.contains(step.rskl.get(reel * GameRules.TRANSPORT_ROWS + row))) {
                throw new IllegalArgumentException("gfl marks a non-paying symbol: " + coordinate);
            }
        }
        if (step.silverCardCoordinates == null) {
            throw new IllegalArgumentException("explicit Silver state must be an array");
        }
        Set<Integer> explicitMaterials = new HashSet<>(unique);
        for (Integer coordinate : step.silverCardCoordinates) {
            if (coordinate == null || !explicitMaterials.add(coordinate)) {
                throw new IllegalArgumentException("Silver state overlaps Gold or contains duplicates");
            }
        }
        Set<Integer> independentlyEligible = new HashSet<>();
        for (int reel = 1; reel <= 3; reel++) {
            for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                if (GameRules.PAYING_SYMBOLS.contains(
                        step.rskl.get(reel * GameRules.TRANSPORT_ROWS + row))) {
                    independentlyEligible.add(reel * 10 + row);
                }
            }
        }
        if (!explicitMaterials.equals(independentlyEligible)) {
            throw new IllegalArgumentException("independent verifier found incomplete Silver/Gold partition");
        }

        Set<Integer> winningCoordinates = winningCoordinates(step);
        int transformed = 0;
        for (Integer rawCoordinate : winningCoordinates) {
            int frontendCoordinate = rawCoordinate + 1;
            if (!unique.contains(frontendCoordinate)) continue;
            if (successor == null) throw new IllegalArgumentException("winning golden coordinate has no successor cascade");
            transformed++;
        }
        return transformed;
    }

    /** Independent reconstruction of captured reel-local cascade physics. */
    private int verifyCascadeContinuity(SpinStep previous, SpinStep successor) {
        if (successor == null) throw new IllegalArgumentException("non-terminal winning Step has no successor in its Delivery");
        Set<Integer> winning = winningCoordinates(previous);
        if (winning.isEmpty()) throw new IllegalArgumentException("non-terminal cascade Step has no winning coordinates");
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
                        previous.silverCardCoordinates.contains(directCoordinate) && !goldenWinner,
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
                int position = removed + index;
                Survivor survivor = survivors.get(index);
                if (!survivor.symbol.equals(newReel.get(position))) {
                    throw new IllegalArgumentException("independent verifier found survivor/Golden WILD jump on reel " + reel);
                }
                if (survivor.silver) expectedSilver.add(reel * 10 + position);
                if (survivor.golden) expectedGolden.add(reel * 10 + position);
            }
        }
        if (!expectedSilver.equals(successor.silverCardCoordinates)) {
            throw new IllegalArgumentException("independent verifier found Silver survivor/refill mismatch");
        }
        if (!expectedGolden.equals(successor.gfl)) {
            throw new IllegalArgumentException("independent verifier found gfl/symbol movement mismatch");
        }
        return 1;
    }

    private record Survivor(String symbol, boolean silver, boolean golden) { }

    private Set<Integer> winningCoordinates(SpinStep step) {
        Set<Integer> result = new HashSet<>();
        for (String target : GameRules.PAYING_SYMBOLS) {
            List<List<Integer>> coordinatesByReel = new java.util.ArrayList<>();
            for (int reel = 0; reel < GameRules.REELS; reel++) {
                List<Integer> reelCoordinates = new java.util.ArrayList<>();
                for (int visibleRow = 0; visibleRow < GameRules.VISIBLE_ROWS; visibleRow++) {
                    String actual = step.rskl.get(reel * GameRules.TRANSPORT_ROWS + visibleRow + 1);
                    if (actual.equals(target) || actual.equals("WILD")) reelCoordinates.add(reel * 10 + visibleRow);
                }
                if (reelCoordinates.isEmpty()) break;
                coordinatesByReel.add(reelCoordinates);
            }
            if (coordinatesByReel.size() >= 3) coordinatesByReel.forEach(result::addAll);
        }
        return result;
    }

    private BigDecimal evaluateWays(SpinStep step, int betLevel, BigDecimal betSize) {
        if (step.rskl == null || step.rskl.size() != GameRules.TRANSPORT_CELLS) throw new IllegalArgumentException("transport board size differs from capability");
        BigDecimal total = BigDecimal.ZERO;
        for (String target : GameRules.PAYING_SYMBOLS) {
            int matchedReels = 0, ways = 1;
            for (int reel = 0; reel < GameRules.REELS; reel++) {
                int count = 0;
                for (int visibleRow = 1; visibleRow <= GameRules.VISIBLE_ROWS; visibleRow++) {
                    String actual = step.rskl.get(reel * GameRules.TRANSPORT_ROWS + visibleRow);
                    if (actual.equals(target) || actual.equals("WILD")) count++;
                }
                if (count == 0) break;
                ways *= count;
                matchedReels++;
            }
            if (matchedReels >= 3) total = total.add(GameRules.PAYTABLE.get(target).get(matchedReels)
                    .multiply(betSize).multiply(BigDecimal.valueOf(betLevel))
                    .multiply(BigDecimal.valueOf(ways)).multiply(BigDecimal.valueOf(step.rpx)));
        }
        return money(total);
    }

    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros(); }
    public enum ResultPool { LOSS, BASE_WIN, FREE_SPINS, FREE_SPIN_RETRIGGER }
    public record RoundAnalysis(ResultPool pool, BigDecimal multiplier, BigDecimal totalWin, int stepCount,
            int freeStepCount, int retriggerCount, int longestConsecutiveWins, int cascadeTransitionCount,
            int goldenTransformCount, boolean terminal,
            String rulesVersion, String rulesHash) {
        public boolean special() { return freeStepCount > 0; }
    }
}
