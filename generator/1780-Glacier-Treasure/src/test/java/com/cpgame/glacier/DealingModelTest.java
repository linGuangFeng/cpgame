package com.cpgame.glacier;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class DealingModelTest {
    @Test
    void generatorPropertiesAreFullyConsumed() {
        var config = GeneratorConfiguration.load(java.nio.file.Path.of("generator.properties"));
        assertEquals(22411, java.util.Arrays.stream(config.paidInitial).sum());
        assertEquals(6461, java.util.Arrays.stream(config.paidRefill).sum());
        assertEquals(1510, java.util.Arrays.stream(config.freeInitial).sum());
        assertEquals(814, java.util.Arrays.stream(config.freeRefill).sum());
        assertEquals(168, java.util.Arrays.stream(config.silverToGold).sum());
        assertEquals(3388, config.paidInitialStructures.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, config.silverToGold[0]);
        assertEquals(1, config.maxScatterSymbolsColumn);
        GenerationModel model = config.model();
        assertEquals(config.paidInitial[11], model.paidInitial[11]);
        assertEquals(39, model.innerSignatures.length);
    }

    @Test
    void paidInitialNeverDealsGold() {
        BoardFactory factory = new BoardFactory(new Random(1780));
        int silver = 0;
        int gold = 0;
        int large = 0;
        for (int i = 0; i < 400; i++) {
            GameRuleCore.Board board = factory.initial(false, false);
            for (var col : board.columns()) {
                for (var s : col) {
                    if (s.grid() >= 2 && s.prop() <= 11) {
                        large++;
                        if (s.frame() == 1) silver++;
                        if (s.frame() == 2) gold++;
                    }
                }
            }
        }
        assertEquals(0, gold, "gold is never dealt on opening pages");
        assertTrue(silver > 0, "silver frames must appear on opening large symbols");
        assertTrue(large > 0);
        assertTrue((100.0 * silver / large) > 15 && (100.0 * silver / large) < 50);
    }

    @Test
    void cascadeNewSymbolsAreSingleUnframed() {
        BoardFactory factory = new BoardFactory(new Random(7));
        GameRuleCore core = new GameRuleCore();
        boolean sawGoldTransform = false;
        for (int i = 0; i < 200; i++) {
            GameRuleCore.Board board = factory.initial(false, false);
            var eval = core.evaluate(board, BigDecimal.ONE, 1);
            if (eval.terminal()) continue;
            GameRuleCore.Board next = factory.cascadeLegal(board, eval, false);
            for (var err : new ResultUtil().verifyCascade(board, next, eval.winningIds())) {
                fail(err);
            }
            for (var s : board.symbols()) {
                if (eval.winningIds().contains(s.id()) && s.frame() == 1) {
                    GameRuleCore.Symbol kept = next.symbols().stream().filter(n -> n.id() == s.id()).findFirst().orElse(null);
                    assertNotNull(kept);
                    assertEquals(2, kept.frame());
                    sawGoldTransform = true;
                }
            }
        }
        assertTrue(sawGoldTransform, "winning silver must become gold");
    }

    @Test
    void neverTwoScatterSymbolsInSameVerticalColumn() {
        BoardFactory factory = new BoardFactory(new Random(12));
        GameRuleCore core = new GameRuleCore();
        for (int i = 0; i < 300; i++) {
            assertSingleScatterPerColumn(factory.initial(false, i % 17 == 0));
            GameRuleCore.Board trigger = factory.featureTrigger();
            assertSingleScatterPerColumn(trigger);
            var eval = core.evaluate(trigger, BigDecimal.ONE, 1);
            if (!eval.terminal()) assertSingleScatterPerColumn(factory.cascadeLegal(trigger, eval, false));
        }
    }

    private static void assertSingleScatterPerColumn(GameRuleCore.Board board) {
        for (var col : board.columns()) {
            int n = 0;
            for (var s : col) if (s.prop() == GameRuleCore.SCATTER) n++;
            assertTrue(n <= 1, "origin never places two Scatter symbols in one vertical column");
        }
    }

    @Test
    void specialTriggerSpinCanAlsoPayLineWins() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        GameRuleCore core = new GameRuleCore();
        int triggerWin = 0;
        int triggerZero = 0;
        for (int i = 0; i < 40; i++) {
            CompleteRoundFactory.GeneratedRound round = null;
            Random rng = new Random(1780 + i * 31L);
            for (int attempt = 0; attempt < 2500 && round == null; attempt++) {
                try {
                    round = factory.generateTarget(rng, false, CompleteRoundFactory.Outcome.SPECIAL);
                } catch (CompleteRoundFactory.RoundRejectedException ignored) { }
            }
            assertNotNull(round, "could not sample special round " + i);
            assertTrue(round.special());
            var pages = round.fact.spins().get(0);
            int award = core.initialFreeAward(core.scatterSymbolCount(pages.get(pages.size() - 1)));
            assertTrue(award > 0, "special must award free spins");
            int ratio = 0;
            int mult = 1;
            for (var board : pages) {
                var eval = core.evaluate(board, BigDecimal.ONE, mult);
                ratio += eval.unitProduct();
                if (!eval.terminal()) mult = core.nextMultiplier(mult, false, true);
            }
            if (ratio > 0) triggerWin++;
            else triggerZero++;
        }
        assertTrue(triggerWin > 0,
            "origin special triggers pay line wins on 11/25 samples; generated triggers never paid. win="
                + triggerWin + " zero=" + triggerZero);
        assertTrue(triggerZero > 0, "origin also has zero-pay special triggers");
    }

    @Test
    void independentLossIsZeroAndNotFree() {
        IndependentLossGenerator gen = new IndependentLossGenerator(new Random(99));
        GameRuleCore core = new GameRuleCore();
        ResultUtil oracle = new ResultUtil();
        var table = ResultUtil.paytable();
        int zero = 0;
        for (int i = 0; i < 200; i++) {
            GameRuleCore.Board board = gen.generate();
            var eval = core.evaluate(board, BigDecimal.ONE, 1);
            oracle.verify(eval, oracle.evaluate(board, BigDecimal.ONE, 1, table));
            assertTrue(eval.terminal());
            assertEquals(0, eval.total().signum());
            assertEquals(0, core.initialFreeAward(core.scatterSymbolCount(board)));
            zero++;
        }
        assertEquals(200, zero);
    }
}
