package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.service.GameProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CascadeContinuityTest {
    @Test
    void baseWinPreGeneratesTwoPhysicalCascadesWithoutWholeBoardReplacement() {
        RoundPlan round = round(RoundScenario.BASE_WIN);
        assertEquals(1, round.deliveries.size());
        assertEquals(3, round.deliveries.getFirst().steps.size());
        SpinStep first = round.deliveries.getFirst().steps.get(0);
        SpinStep second = round.deliveries.getFirst().steps.get(1);
        SpinStep terminal = round.deliveries.getFirst().steps.get(2);
        assertTrue(first.wa.signum() > 0 && second.wa.signum() > 0);
        assertEquals(0, first.ss);
        assertEquals(0, second.ss);
        assertEquals(1, terminal.ss);

        // Reel 0 bottom H8 wins. Buffer + three survivors retain order and fall one row.
        assertEquals(first.rskl.get(0), second.rskl.get(1));
        assertEquals(first.rskl.get(1), second.rskl.get(2));
        assertEquals(first.rskl.get(2), second.rskl.get(3));
        assertEquals(first.rskl.get(3), second.rskl.get(4));
        assertNotEquals(first.rskl.get(4), second.rskl.get(4));

        // The second cascade removes two H7 cells on reel 2; its old buffer falls two rows.
        assertEquals(second.rskl.get(10), terminal.rskl.get(12));
        assertEquals(second.rskl.get(13), terminal.rskl.get(13));
        assertEquals(second.rskl.get(14), terminal.rskl.get(14));
        RoundValidator.validateCascadeTransition(first, second);
        RoundValidator.validateCascadeTransition(second, terminal);
    }

    @Test
    void validatorRejectsAJumpedSurvivorBoard() {
        RoundPlan round = round(RoundScenario.BASE_WIN);
        SpinStep first = round.deliveries.getFirst().steps.get(0);
        SpinStep second = round.deliveries.getFirst().steps.get(1);
        second.rskl.set(2, "H8");
        assertThrows(IllegalArgumentException.class,
                () -> RoundValidator.validateCascadeTransition(first, second));
    }

    @Test
    void goldenWinnerUsesFrontendCoordinateAndStaysAnchoredAsWild() {
        RoundPlan round = round(RoundScenario.GOLDEN_TRANSFORM);
        SpinStep first = round.deliveries.getFirst().steps.get(0);
        SpinStep second = round.deliveries.getFirst().steps.get(1);
        assertEquals(java.util.List.of(11), first.gfl);
        assertEquals("WILD", second.rskl.get(6));
        assertEquals(first.rskl.get(5), second.rskl.get(5));
        assertEquals(first.rskl.get(7), second.rskl.get(7));
        RoundValidator.validateCascadeTransition(first, second);
    }

    private RoundPlan round(RoundScenario scenario) {
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        return new GameRuleCore(properties).generateCompleteRound(scenario, "round-1407", "transfer-1407",
                1, new BigDecimal("0.02"), new BigDecimal("999.60"), 1_787_735_408L);
    }
}
