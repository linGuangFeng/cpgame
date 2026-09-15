package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.service.GameProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceDrivenSpecialMechanicsTest {
    @Test
    void naturalRuntimeContainsTheObservedSpecialStateChainsWithoutDirectWildDraws() {
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        GameRuleCore core = new GameRuleCore(properties);
        int startsWithGold = 0;
        boolean transformedWild = false;
        boolean scatterRefill = false;
        boolean freeFeature = false;
        boolean retrigger = false;
        long eligibleCards = 0;
        long silverCards = 0;
        long goldCards = 0;

        for (int ordinal = 0; ordinal < 3_000; ordinal++) {
            RoundPlan round = core.generateRuntimeRound("r-" + ordinal, "t-" + ordinal,
                    1, new BigDecimal("0.02"), new BigDecimal("999.60"), ordinal);
            SpinStep initial = round.deliveries.getFirst().steps.getFirst();
            assertFalse(initial.rskl.contains("WILD"), "initial WILD would violate all 501 captured starts");
            if (!initial.gfl.isEmpty()) startsWithGold++;
            for (int reel = 1; reel <= 3; reel++) {
                for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                    String symbol = initial.rskl.get(reel * GameRules.TRANSPORT_ROWS + row);
                    if (!GameRules.PAYING_SYMBOLS.contains(symbol)) continue;
                    eligibleCards++;
                    int coordinate = reel * 10 + row;
                    if (initial.gfl.contains(coordinate)) goldCards++;
                    if (initial.silverCardCoordinates.contains(coordinate)) silverCards++;
                }
            }
            assertEquals(eligibleCards, silverCards + goldCards,
                    "every eligible card must be explicitly Silver or Gold");
            for (RoundDelivery delivery : round.deliveries) {
                List<SpinStep> steps = delivery.steps;
                if ("FREE".equals(delivery.mode)) {
                    freeFeature = true;
                    for (int index = 0; index < steps.size(); index++) {
                        assertEquals(GameRules.FREE_RPX.get(Math.min(index, 3)), steps.get(index).rpx);
                    }
                }
                int terminalScatter = CoinMasterResultUtil.evaluate(
                        steps.getLast().rskl, 1, BigDecimal.ONE, 1).scatterCount();
                int terminalAward = GameRules.freeAward(terminalScatter);
                if (steps.size() > 1) {
                    int heldFsn = steps.getFirst().fsn;
                    for (int index = 0; index + 1 < steps.size(); index++) {
                        assertEquals(heldFsn, steps.get(index).fsn,
                                "fsn stays unchanged until the Delivery terminal");
                    }
                    assertEquals(heldFsn + terminalAward, steps.getLast().fsn,
                            "cascade-completed visible SC must award at the Delivery terminal");
                }
                for (int index = 0; index + 1 < steps.size(); index++) {
                    SpinStep before = steps.get(index);
                    SpinStep after = steps.get(index + 1);
                    if (!before.rskl.contains("WILD") && after.rskl.contains("WILD")) transformedWild = true;
                    if (count(before, "SC") < count(after, "SC")) scatterRefill = true;
                }
            }
            retrigger |= RoundScenario.FREE_RETRIGGER.name().equals(round.scenario);
        }

        assertTrue(startsWithGold > 2_700, "evidence profile should make golden cards visible in normal play");
        assertTrue(transformedWild, "a winning golden card must visibly transform into WILD after cascade");
        assertTrue(scatterRefill, "captured adjacent steps prove SC may enter through cascade refill");
        assertTrue(freeFeature, "three or more SC must naturally open the complete free-spin chain");
        assertTrue(retrigger, "free-spin SC retrigger must naturally extend fsn");
        assertEquals(GameRules.EVIDENCE_GOLD_CARD_WEIGHT /
                        (double) (GameRules.EVIDENCE_SILVER_CARD_WEIGHT + GameRules.EVIDENCE_GOLD_CARD_WEIGHT),
                goldCards / (double) eligibleCards, 0.01,
                "per-card Gold rate must follow the inferred configurable Silver/Gold weights");
        assertTrue(eligibleCards - goldCards > goldCards, "the inferred profile must visibly include Silver cards");
        assertEquals(eligibleCards, silverCards + goldCards,
                "Silver must be a first-class generated state, not an implicit else branch");
    }

    @Test
    void silverAndGoldCardWeightsAreIndependentlyConfigurable() {
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        GameRuleCore core = new GameRuleCore(properties);

        core.configureCardMaterialWeights(1, 0);
        SpinStep allSilver = core.neutralSnapshot(new BigDecimal("1000.00"));
        assertTrue(allSilver.gfl.isEmpty(), "Gold weight 0 must make every eligible card Silver");
        assertFalse(allSilver.silverCardCoordinates.isEmpty());

        core.configureCardMaterialWeights(0, 1);
        SpinStep allGold = core.neutralSnapshot(new BigDecimal("1000.00"));
        long eligible = 0;
        for (int reel = 1; reel <= 3; reel++) {
            for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                String symbol = allGold.rskl.get(reel * GameRules.TRANSPORT_ROWS + row);
                if (GameRules.PAYING_SYMBOLS.contains(symbol)) eligible++;
            }
        }
        assertEquals(eligible, allGold.gfl.size(), "Silver weight 0 must make every eligible card Gold");
        assertTrue(allGold.silverCardCoordinates.isEmpty(), "Silver weight 0 must leave no Silver state");
        assertThrows(IllegalArgumentException.class, () -> core.configureCardMaterialWeights(0, 0));
    }

    private long count(SpinStep step, String symbol) {
        return step.rskl.stream().filter(symbol::equals).count();
    }
}
