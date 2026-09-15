package com.cpgame.fishinggo;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.ResultUtil;
import com.cpgame.fishinggo.core.RoundCodec;
import com.cpgame.fishinggo.core.RoundGenerator;
import com.cpgame.fishinggo.loader.OriginHoldout;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

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
            assertTrue(codec.encode(round).startsWith("FG1|"));
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
}
