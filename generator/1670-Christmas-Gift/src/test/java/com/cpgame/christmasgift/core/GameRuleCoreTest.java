package com.cpgame.christmasgift.core;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GameRuleCoreTest {
    @Test void ordinaryFivePaylineMapAndAmountsAreCanonical() {
        LinkedHashMap<Integer,Integer> board = new LinkedHashMap<>();
        for (int position = 1; position <= 9; position++) board.put(position, 1);
        var round = new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY, 0,
            List.of(new GameRuleCore.Deal(board)));
        var result = ResultUtil.evaluate(round);
        assertEquals(15, result.multiplier());
        assertEquals(5, result.steps().get(0).wins().size());
        assertEquals("3", GameRuleCore.displayedOdds(15).toPlainString());
    }

    @Test void featureUsesIncrementalDealsAndFullScreenTenTimes() {
        var round = new GameRuleCore.CompleteRound(GameRuleCore.Mode.CHRISTMAS_GIFT_FEATURE, 6, List.of(
            new GameRuleCore.Deal(new LinkedHashMap<>(java.util.Map.of(1,6,2,7))),
            new GameRuleCore.Deal(new LinkedHashMap<>(java.util.Map.of(3,6,4,6))),
            new GameRuleCore.Deal(new LinkedHashMap<>(java.util.Map.of(5,6,6,6))),
            new GameRuleCore.Deal(new LinkedHashMap<>(java.util.Map.of(7,6,8,6))),
            new GameRuleCore.Deal(new LinkedHashMap<>(java.util.Map.of(9,6)))
        ));
        var result = ResultUtil.evaluate(round);
        assertTrue(result.bigWin());
        assertEquals(5000, result.multiplier());
        assertEquals(round, new MinimalRoundFactCodec().decode(new MinimalRoundFactCodec().encode(round)));
    }

    @Test void generatedRoundsSurviveIndependentVerification() {
        var model = new GenerationModel(new SecureRandom(), new int[]{100,100,100,100,100,100,100});
        var factory = new RoundFactory(model);
        var verifier = new RoundVerifier();
        for (int index = 0; index < 2000; index++) {
            verifier.verify(index % 20 == 0 ? factory.christmasGiftFeature() : factory.ordinary());
        }
    }
}
