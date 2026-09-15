package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.SpinStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRuleCoreTest {
    @Test void restoreOnlyCoreRejectsGeneration() {
        assertThrows(IllegalStateException.class,
                () -> GameRuleCore.forRestoration().generateIndependentLoss(1, new BigDecimal("0.02"),
                        new BigDecimal("1000")));
    }

    @Test void generatedCategoriesMatchPools() {
        GameRuleCore core = GameRuleCore.forTesting(61);
        BigDecimal bs = new BigDecimal("0.02");
        BigDecimal start = new BigDecimal("10000");
        RoundResult loss = core.generateIndependentLoss(1, bs, start);
        RoundResult win = core.generateOrdinaryWin(1, bs, start);
        RoundResult special = core.generateSpecial(1, bs, start);
        assertEquals(RoundMode.ORDINARY_LOSS, ResultUtil.analyze(loss).mode());
        assertEquals(RoundMode.ORDINARY_WIN, ResultUtil.analyze(win).mode());
        assertTrue(special.mode() == RoundMode.FREE_SPINS || special.mode() == RoundMode.WILD_VORTEX);
        assertTrue(core.trainingKernelCount() > 1000);
        assertTrue(core.validateIndependentLossStrategy() >= 0.9d);
    }

    @Test void energyUtilDecidesTriggerAndStitchesVortexTail() {
        GameRuleCore core = GameRuleCore.forTesting(61061);
        BigDecimal bs = new BigDecimal("0.02");
        BigDecimal start = new BigDecimal("10000");
        RoundResult ordinary = null;
        for (int i = 0; i < 400; i++) {
            RoundResult win = core.generateOrdinaryWin(1, bs, start);
            if (ResultUtil.energyDelta(win.candidate().steps()) > 0) {
                ordinary = win;
                break;
            }
        }
        assertTrue(ordinary != null, "need an ordinary win that eliminates Wild");
        RoundResult vortex = null;
        for (int i = 0; i < 80; i++) {
            RoundResult special = core.generateSpecial(1, bs, start);
            if (special.mode() == RoundMode.WILD_VORTEX) {
                vortex = special;
                break;
            }
        }
        assertTrue(vortex != null, "need a vortex tail cache member");

        ResultUtil.EnergyState empty = ResultUtil.EnergyState.initial();
        ResultUtil.EnergyProjection noTrigger = ResultUtil.applyEnergyTransition(empty, ordinary.candidate().steps());
        assertFalse(noTrigger.vortex());
        RoundResult kept = core.compose(ordinary.candidate(), empty, vortex.candidate(), 1, bs, start);
        assertEquals(ordinary.mode(), kept.mode());
        assertEquals(0, kept.steps().get(0).rsn());

        ResultUtil.EnergyState almostFull = new ResultUtil.EnergyState(GameRules.WILD_ENERGY_CAP - 1);
        ResultUtil.EnergyProjection trigger = ResultUtil.applyEnergyTransition(almostFull, ordinary.candidate().steps());
        assertTrue(trigger.vortex());
        assertEquals(0, trigger.nextState().wn());
        RoundResult stitched = core.compose(ordinary.candidate(), almostFull, vortex.candidate(), 1, bs, start);
        assertEquals(RoundMode.WILD_VORTEX, ResultUtil.analyze(stitched).mode());
        List<SpinStep> steps = stitched.steps();
        SpinStep announce = null;
        int vortexPlays = 0;
        for (SpinStep step : steps) {
            if (step.gm() == 1 && step.rsn() == 3 && step.nrsc() == 0) announce = step;
            if (step.gm() == 3) vortexPlays++;
        }
        assertTrue(announce != null, "announce step missing");
        assertEquals(ordinary.steps().get(ordinary.steps().size() - 1).rskl(), announce.rskl());
        assertTrue(vortexPlays >= 3);
        assertEquals(0, steps.get(steps.size() - 1).wn());
        assertTrue(steps.get(steps.size() - 1).roundTerminal());
        assertEquals(0, steps.get(0).ba().compareTo(GameRules.betAmount(1, bs)));
        for (int i = 1; i < steps.size(); i++) assertEquals(0, steps.get(i).ba().signum());
    }
}
