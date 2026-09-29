package com.cpgame.fishinggo;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.DealingWeights;
import com.cpgame.fishinggo.core.ProtocolConstants;
import com.cpgame.fishinggo.core.ResultUtil;
import com.cpgame.fishinggo.core.RoundCodec;
import com.cpgame.fishinggo.core.RoundGenerator;
import com.cpgame.fishinggo.loader.OriginHoldout;
import com.cpgame.fishinggo.loader.LoaderLimits;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapturedPayoutTest {
    @Test
    void holdoutAgreesWithIndependentOracle() throws Exception {
        OriginHoldout.check(new ResultUtil());
    }

    @Test
    void generatedRoundsRoundtrip() {
        RoundGenerator g = new RoundGenerator(new SecureRandom());
        ResultUtil util = new ResultUtil();
        RoundCodec codec = new RoundCodec();
        for (CompleteRound round : List.of(g.loss(), g.win(), g.special(5))) {
            util.analyze(round);
            assertEquals(codec.encode(round), codec.encode(codec.decode(codec.encode(round), g)));
            assertTrue(codec.encode(round).equals("#") || codec.encode(round).startsWith("FG2|"));
        }
    }

    @Test
    void originOrdinaryWinFixtureAgreesWithIndependentOracle() {
        ResultUtil util = new ResultUtil();
        List<String> board = List.of(
                "S3", "K", "S2",
                "S2", "S3", "WILD",
                "J", "WILD", "S2",
                "S2", "WILD", "S1",
                "S2", "K", "S1");
        ResultUtil.Win win = util.evaluate(board, 1);
        assertEquals(0, win.payout().compareTo(new java.math.BigDecimal("22")));
        assertTrue(win.symbols().contains("S2"));
        assertTrue(win.symbols().contains("S3"));
        assertTrue(win.symbols().contains("K"));
    }

    @Test void configuredPaidWeightsAreConsumedAndZeroLossIsIndependentOfPositiveMinimum() {
        DealingWeights base = DealingWeights.empiricalDefaults();
        Map<String, Integer> boostedPaid = new LinkedHashMap<>(base.paid());
        boostedPaid.compute("A", (key, value) -> value * 20);
        RoundGenerator normal = new RoundGenerator(new SecureRandom(), base);
        RoundGenerator boosted = new RoundGenerator(new SecureRandom(),
                new DealingWeights(boostedPaid, base.free(), base.entryFill(), base.entryScatter()));
        int normalA = 0, boostedA = 0;
        for (int i = 0; i < 1000; i++) {
            normalA += java.util.Collections.frequency(normal.lossBoardCandidate(), "A");
            boostedA += java.util.Collections.frequency(boosted.lossBoardCandidate(), "A");
        }
        assertTrue(boostedA > normalA * 3 / 2, normalA + " -> " + boostedA);

        Properties limits = new Properties();
        limits.setProperty("generation.normal-min-win-multiplier", "1");
        limits.setProperty("generation.special-min-win-multiplier", "100");
        LoaderLimits parsed = new LoaderLimits(limits);
        assertTrue(parsed.accepts(false, 0));
        assertEquals(3, parsed.lossTarget(3));
    }
}
