package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public final class CoreSelfTest {
    @Test
    public void verifiesGenerationChain() {
        runAssertions();
    }

    public static void main(String[] args) {
        runAssertions();
    }

    private static void runAssertions() {
        GameRuleCore core = testCore(2001007L);
        ResultUtil oracle = new ResultUtil();
        RoundVerifier verifier = core.verifier();
        MinimalFactCodec factCodec = core.minimalFactCodec();
        Set<String> losses = new HashSet<String>();
        for (int i = 0; i < 250; i++) {
            GeneratedRound loss = core.generateCompleteRound(new BigDecimal("0.40"), RoundMode.LOSS);
            require(loss.getSteps().size() == 1, "LOSS is not single-step");
            RoundStep step = loss.getSteps().get(0);
            oracle.assertIndependentLoss(step.getFormation(), 3, new BigDecimal("0.02"));
            require(step.isTerminal(), "LOSS is not terminal");
            require(verifier.verify(loss).getInferredMode() == RoundMode.LOSS, "LOSS mode was not reversed");
            require(sameFacts(loss, factCodec.decode(factCodec.encode(loss), loss.getPaidBet())),
                    "LOSS minimal facts do not round-trip");
            losses.add(step.getFormation());
        }
        require(losses.size() > 240, "LOSS generator is not random enough");
        for (RoundMode mode : new RoundMode[]{RoundMode.BASE_WIN, RoundMode.XSPLIT, RoundMode.XBOMB_WILD}) {
            for (int i = 0; i < 50; i++) {
                GeneratedRound round = core.generateCompleteRound(new BigDecimal("0.40"), mode);
                require(round.getSteps().size() >= 2, mode + " is not a complete multi-step Round");
                require(round.getPayout().signum() > 0, mode + " has no payout");
                require(round.getSteps().get(round.getSteps().size() - 1).isTerminal(), mode + " has no terminal Delivery");
                require(verifier.verify(round).getInferredMode() == mode, mode + " was not independently reversed");
                require(sameFacts(round, factCodec.decode(factCodec.encode(round), round.getPaidBet())),
                        mode + " minimal facts do not round-trip");
                for (int deliveryIndex = 0; deliveryIndex < round.getSteps().size(); deliveryIndex++) {
                    DeliveryRuntimeUtil.Projection projection = core.deliveryRuntimeUtil()
                            .project(round, deliveryIndex);
                    require(projection.getDeliveryIndex() == deliveryIndex, "deliveryIndex was not projected");
                    require(projection.getFormation().split(",", -1).length == 36,
                            "projected formation is not the full 6x6 encoded board");
                    require(projection.isTerminal() == (deliveryIndex == round.getSteps().size() - 1),
                            "projected terminal boundary is inconsistent");
                    require(projection.getActualMultiplier().compareTo(
                            round.getPayout().divide(round.getPaidBet(), 8, java.math.RoundingMode.HALF_UP)) == 0,
                            "projected multiplier fact is inconsistent");
                }
            }
        }
        GameRuleCore unseededLiveCore = new GameRuleCore(GenerationPolicy.defaults(), trialPolicy());
        int liveWins = 0;
        int liveLosses = 0;
        int liveMultiStepWins = 0;
        Set<String> winningFirstBoards = new HashSet<String>();
        for (int i = 0; i < 10000; i++) {
            GeneratedRound live = unseededLiveCore.generateLiveRound(new BigDecimal("0.40"));
            verifier.verify(live);
            require(sameFacts(live, factCodec.decode(factCodec.encode(live), live.getPaidBet())),
                    "live Round minimal facts do not round-trip");
            if (live.getPayout().signum() > 0) {
                liveWins++;
                winningFirstBoards.add(live.getSteps().get(0).getFormation());
                if (live.getSteps().size() > 1) liveMultiStepWins++;
            } else {
                liveLosses++;
                RoundStep only = live.getSteps().get(0);
                oracle.assertIndependentLoss(only.getFormation(), only.getActiveRows(), new BigDecimal("0.02"));
            }
        }
        require(liveWins > 0, "10,000 unseeded live Rounds contain no WIN");
        require(liveLosses > 0, "10,000 unseeded live Rounds contain no LOSS");
        require(winningFirstBoards.size() > 1, "live winning boards are fixed or repeated as one preset");
        require(liveMultiStepWins > 0, "no complete multi-step winning Round was generated live");
        GeneratedRound sound = core.generateCompleteRound(new BigDecimal("0.40"), RoundMode.BASE_WIN);
        RoundStep original = sound.getSteps().get(0);
        java.util.List<RoundStep> tamperedSteps = new java.util.ArrayList<RoundStep>(sound.getSteps());
        tamperedSteps.set(0, new RoundStep(original.getFormation(), original.getActiveRows(),
                original.getGlobalMultiplier(), original.getStepWin().add(BigDecimal.ONE),
                original.getCumulativeWin(), original.isTerminal()));
        GeneratedRound tampered = new GeneratedRound(sound.getSchemaVersion(), sound.getRulesVersion(),
                sound.getRulesHash(), sound.getDeterministicSeed(), sound.getRoundKey(), sound.getMode(),
                sound.getPaidBet(), sound.getPayout(), tamperedSteps);
        expectFailure(() -> verifier.verify(tampered), "Verifier accepted a tampered step win");
        GeneratedRound wrongSeed = new GeneratedRound(sound.getSchemaVersion(), sound.getRulesVersion(),
                sound.getRulesHash(), sound.getDeterministicSeed() + 1, sound.getRoundKey(), sound.getMode(),
                sound.getPaidBet(), sound.getPayout(), sound.getSteps());
        expectFailure(() -> verifier.verify(wrongSeed), "Verifier accepted a tampered deterministic seed");
        GeneratedRound a = core.generateCompleteRound(new BigDecimal("0.40"), RoundMode.LOSS, 7L);
        GeneratedRound b = core.generateCompleteRound(new BigDecimal("0.40"), RoundMode.LOSS, 7L);
        require(a.getSteps().get(0).getFormation().equals(b.getSteps().get(0).getFormation()), "test seed is not reproducible");
        require(a.getRoundKey().equals(b.getRoundKey()), "test seed does not reproduce roundKey");
        require(a.getDeterministicSeed() == 7L && b.getDeterministicSeed() == 7L,
                "deterministic seed fact is missing");
        require(GameConstants.ROUND_SCHEMA_VERSION.equals(a.getSchemaVersion())
                        && GameConstants.RULES_VERSION.equals(a.getRulesVersion()),
                "Round schemaVersion/rulesVersion facts are missing");
        System.out.println("CoreSelfTest PASS; uniqueLosses=" + losses.size()
                + ", liveTotal=10000, liveWins=" + liveWins + ", liveLosses=" + liveLosses
                + ", uniqueWinningFirstBoards=" + winningFirstBoards.size()
                + ", multiStepWins=" + liveMultiStepWins);
    }

    private static boolean sameFacts(GeneratedRound left, GeneratedRound right) {
        if (!left.getSchemaVersion().equals(right.getSchemaVersion())
                || !left.getRulesVersion().equals(right.getRulesVersion())
                || !left.getRulesHash().equals(right.getRulesHash())
                || left.getDeterministicSeed() != right.getDeterministicSeed()
                || !left.getRoundKey().equals(right.getRoundKey())
                || left.getMode() != right.getMode() || left.getPayout().compareTo(right.getPayout()) != 0
                || left.getSteps().size() != right.getSteps().size()) return false;
        for (int i = 0; i < left.getSteps().size(); i++) {
            RoundStep a = left.getSteps().get(i), b = right.getSteps().get(i);
            if (!a.getFormation().equals(b.getFormation()) || a.getActiveRows() != b.getActiveRows()
                    || a.getGlobalMultiplier() != b.getGlobalMultiplier()
                    || a.getStepWin().compareTo(b.getStepWin()) != 0
                    || a.getCumulativeWin().compareTo(b.getCumulativeWin()) != 0
                    || a.isTerminal() != b.isTerminal()) return false;
        }
        return true;
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static GameRuleCore testCore(long seed) {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(seed);
            return new GameRuleCore(random, GenerationPolicy.defaults(), trialPolicy());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TrialProbabilityPolicy trialPolicy() {
        return new TrialProbabilityPolicy(80, 12, 4, 4);
    }
}
