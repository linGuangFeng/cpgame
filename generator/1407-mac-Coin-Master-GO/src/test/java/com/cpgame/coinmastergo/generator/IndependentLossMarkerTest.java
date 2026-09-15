package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.*;
import com.cpgame.coinmastergo.model.*;
import com.cpgame.coinmastergo.service.GameProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    private GameRuleCore core() {
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        return new GameRuleCore(properties);
    }

    private RoundPlan round(GameRuleCore core, RoundScenario scenario) {
        return core.generateCompleteRound(scenario, "marker", "marker", 1,
                new BigDecimal("0.02"), BigDecimal.ZERO, 0);
    }

    @Test void hashProducesFreshRealLossesAndRejectsMalformedTokens() {
        MinimalFactCodec codec = new MinimalFactCodec();
        Set<String> boards = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            RoundPlan loss = codec.rebuild("#");
            assertEquals(RoundScenario.LOSS.name(), loss.scenario);
            assertEquals(1, loss.deliveries.size());
            assertEquals(1, loss.deliveries.getFirst().steps.size());
            SpinStep step = loss.deliveries.getFirst().steps.getFirst();
            assertTrue(GameRuleCore.isIndependentLossBoard(step.rskl));
            assertEquals(0, step.wa.signum());
            assertEquals(0, step.fsn);
            assertEquals("#", codec.encode(loss));
            boards.add(codec.encodeFull(loss));
        }
        assertTrue(boards.size() > 1);
        String oldBoard = boards.iterator().next();
        assertThrows(IllegalArgumentException.class, () -> codec.rebuild(oldBoard.substring(0, 39) + "#"));
        for (String invalid : List.of("0", "##", "|#", "#|", "#|#", "#" + boards.iterator().next())) {
            assertThrows(IllegalArgumentException.class, () -> codec.rebuild(invalid), invalid);
        }
    }

    @Test void preservesEveryCascadeAndTriggerIncludingGoldenWildAndRetriggers() {
        GameRuleCore core = core();
        MinimalFactCodec codec = new MinimalFactCodec();
        int compressed = 0, cascades = 0, triggers = 0;
        for (RoundScenario scenario : RoundScenario.values()) {
            RoundPlan original = round(core, scenario);
            String full = codec.encodeFull(original);
            assertEquals(codec.extract(original), codec.extract(codec.rebuild(full)), "legacy exact round trip");
            String compact = codec.encode(original);
            var facts = codec.decode(compact);
            RoundPlan rebuilt = codec.rebuild(facts);
            assertEquals(codec.extract(rebuilt), codec.extract(codec.rebuild(facts)), "reuse materialized facts");
            assertEquals(original.scenario, rebuilt.scenario);
            assertEquals(0, original.totalWin.compareTo(rebuilt.totalWin));
            assertEquals(original.deliveries.size(), rebuilt.deliveries.size());
            String[] originalTokens = full.split("\\|");
            String[] tokens = compact.split("\\|");
            for (int i = 0; i < tokens.length; i++) {
                RoundDelivery before = original.deliveries.get(i), after = rebuilt.deliveries.get(i);
                SpinStep last = before.steps.getLast();
                boolean trigger = GameRules.freeAward(CoinMasterResultUtil.evaluate(last.rskl, 1, BigDecimal.ONE, 1).scatterCount()) > 0;
                if (before.steps.size() > 1 || trigger) {
                    assertEquals(originalTokens[i], tokens[i], "dependent pages and triggers must stay literal");
                    assertEquals(codec.extract(original).d().get(i), facts.d().get(i));
                    if (before.steps.size() > 1) cascades++;
                    if (trigger) triggers++;
                } else {
                    assertEquals("#", tokens[i]);
                    compressed++;
                }
                assertEquals(before.steps.size(), after.steps.size());
                for (int j = 0; j < before.steps.size(); j++) {
                    SpinStep a = before.steps.get(j), b = after.steps.get(j);
                    assertEquals(a.fsn, b.fsn); assertEquals(a.nfsc, b.nfsc);
                    assertEquals(a.rpx, b.rpx); assertEquals(a.ss, b.ss); assertEquals(a.gt, b.gt);
                    assertEquals(0, a.wa.compareTo(b.wa)); assertEquals(0, a.rwa.compareTo(b.rwa));
                    assertEquals(0, a.frwa.compareTo(b.frwa));
                }
            }
            System.out.printf("MARKER_1407 scenario=%s before=%d after=%d%n", scenario, full.length(), compact.length());
        }
        assertTrue(compressed > 0 && cascades > 0 && triggers > 0);
    }

    @Test void ignoresForgedZeroPayoutLabelWhenBoardWins() {
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundPlan win = round(core(), RoundScenario.BASE_WIN);
        SpinStep first = win.deliveries.getFirst().steps.getFirst();
        first.wa = BigDecimal.ZERO;
        win.deliveries = List.of(new RoundDelivery("BASE", List.of(first)));
        assertFalse(codec.encode(win).contains("#"), "use real rules, not payout fields");
    }

    @Test void firstCandidateAndModeAwareEntryAreRealLosses() {
        GameRuleCore core = core();
        for (int i = 0; i < 100_000; i++) assertTrue(GameRuleCore.isIndependentLossBoard(core.lossBoardCandidate()));
        for (boolean free : List.of(false, true)) {
            SpinStep loss = core.generateIndependentLoss(free);
            assertTrue(GameRuleCore.isIndependentLossBoard(loss.rskl));
            assertEquals(free ? 2 : 1, loss.gt);
            assertEquals((free ? GameRules.FREE_RPX : GameRules.BASE_RPX).getFirst(), loss.rpx);
        }
    }

    @Test void sharedCodecSupportsConcurrentMaterialization() throws Exception {
        MinimalFactCodec codec = new MinimalFactCodec();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> {
                for (int j = 0; j < 250; j++) assertEquals(0, codec.rebuild("#").totalWin.signum());
                return true;
            });
            for (Future<Boolean> result : executor.invokeAll(calls)) assertTrue(result.get());
        } finally { executor.shutdownNow(); }
    }
}
