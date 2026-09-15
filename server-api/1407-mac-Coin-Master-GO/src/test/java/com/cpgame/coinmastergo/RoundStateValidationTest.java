package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.core.CardMaterialState;
import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;
import com.cpgame.coinmastergo.service.GameProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Direct regressions for the adjacent-step evidence added by this follow-up. */
class RoundStateValidationTest {
    private final RoundValidator validator = new RoundValidator();

    @Test
    void capturedGoldenWinnerBecomesWildThenFallsAndOtherGoldMarkersFollowSymbols() {
        SpinStep scene26 = new SpinStep();
        scene26.ss = 0;
        scene26.rskl = board("H7,H6,H1,H6,H5,H2,H5,H5,H6,H7,H1,H8,H2,H5,H6,H1,H5,H5,H3,H1,H3,H1,H3,H4,H8");
        scene26.gfl = new ArrayList<>(List.of(11, 14, 22, 23, 30, 32));
        scene26.wmkl = new ArrayList<>(List.of(
                List.of(List.of(0, 2), List.of(12), List.of(23)),
                List.of(List.of(3), List.of(10, 11), List.of(22), List.of(30, 31))));

        SpinStep scene27 = new SpinStep();
        scene27.rskl = board("H1,H4,H1,H7,H1,H2,H7,H2,WILD,H7,H5,H1,H8,H2,WILD,H5,H1,WILD,H3,H1,H3,H1,H3,H4,H8");
        scene27.gfl = new ArrayList<>(List.of(14, 23, 31));

        RoundValidator.validateCascadeTransition(scene26, scene27);
        assertEquals("WILD", scene27.rskl.get(14), "Golden winner at old reel-2 position 3 falls to position 4");
        assertTrue(scene27.gfl.contains(23), "non-winning Golden marker moves with its surviving symbol");
        assertFalse(scene27.gfl.contains(22));

        scene27.rskl.set(14, "H6");
        assertThrows(IllegalArgumentException.class,
                () -> RoundValidator.validateCascadeTransition(scene26, scene27));
    }

    @Test
    void validatorRejectsExactMatchAccumulatorMultiplierBalanceAndHistoryClassificationTampering() {
        RoundPlan matchTampered = round(RoundScenario.BASE_WIN);
        matchTampered.deliveries.getFirst().steps.getFirst().wmkl.getFirst().getFirst().set(0, 1);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(matchTampered));

        RoundPlan frwaTampered = round(RoundScenario.BASE_WIN);
        frwaTampered.deliveries.getFirst().steps.getFirst().frwa = BigDecimal.ONE;
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frwaTampered));

        RoundPlan rpxTampered = round(RoundScenario.BASE_WIN);
        rpxTampered.deliveries.getFirst().steps.get(1).rpx = 5;
        assertThrows(IllegalArgumentException.class, () -> validator.validate(rpxTampered));

        RoundPlan balanceTampered = round(RoundScenario.BASE_WIN);
        balanceTampered.deliveries.getFirst().steps.getFirst().pb = "999.99";
        assertThrows(IllegalArgumentException.class, () -> validator.validate(balanceTampered));

        RoundPlan historyTampered = round(RoundScenario.BASE_WIN);
        historyTampered.scenario = RoundScenario.LOSS.name();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(historyTampered));
    }

    @Test
    void freeAwardAndRetriggerAppearOnlyAtDeliveryTerminalAndRoundEndsAtFsn() {
        RoundPlan round = round(RoundScenario.FREE_RETRIGGER);
        validator.validate(round);
        assertEquals(12, round.deliveries.getFirst().steps.getLast().fsn);
        assertEquals(12, round.deliveries.get(2).steps.getLast().fsn);
        assertEquals(24, round.deliveries.get(3).steps.getLast().fsn);
        SpinStep terminal = round.deliveries.getLast().steps.getLast();
        assertEquals(24, terminal.fsn);
        assertEquals(24, terminal.nfsc);
        assertEquals(1, terminal.ss);
    }

    @Test
    void cascadeCompletedVisibleScatterOnTerminalBoardAwardsMary() {
        assertEquals(12, GameRules.freeAward(3));
        assertEquals(14, GameRules.freeAward(4));
        assertEquals(0, GameRules.freeAward(2));

        RoundPlan round = cascadeCompletedScatterRound();
        SpinStep opening = round.deliveries.getFirst().steps.getFirst();
        SpinStep baseTerminal = round.deliveries.getFirst().steps.getLast();
        CoinMasterResultUtil.Evaluation openingEval = CoinMasterResultUtil.evaluate(
                opening.rskl, round.betLevel, round.betSize, opening.rpx);
        CoinMasterResultUtil.Evaluation terminalEval = CoinMasterResultUtil.evaluate(
                baseTerminal.rskl, round.betLevel, round.betSize, baseTerminal.rpx);
        assertEquals(2, openingEval.scatterCount(), "opening board has only two visible SC");
        assertTrue(openingEval.totalWin().signum() > 0);
        assertEquals(3, terminalEval.scatterCount(), "cascade refill completes three visible SC");
        assertEquals(0, terminalEval.totalWin().signum());
        assertEquals(0, opening.fsn);
        assertEquals(12, baseTerminal.fsn);
        assertEquals(13, round.deliveries.size());
        validator.validate(round);

        RoundPlan missingAward = cascadeCompletedScatterRound();
        missingAward.scenario = RoundScenario.BASE_WIN.name();
        missingAward.deliveries.subList(1, missingAward.deliveries.size()).clear();
        SpinStep unpaid = missingAward.deliveries.getFirst().steps.getLast();
        unpaid.fsn = 0;
        unpaid.pb = missingAward.postDebitBalance.add(missingAward.totalWin).setScale(2).toPlainString();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingAward),
                "3 visible SC on the cascade terminal must enter Mary");
    }

    @Test
    void bufferScatterIsNotAMaryTriggerAndCascadeBufferRefillsAreNeverScatter() {
        List<String> twoVisiblePlusTwoBuffer = board(
                "SC,H1,H2,H3,H4,SC,H5,H6,H7,H8,H1,H1,H5,H6,H7,H2,SC,H3,H7,H8,H3,H1,SC,H4,H6");
        List<String> fourPlayableScatter = board(
                "H4,SC,H1,H2,H3,H8,SC,H5,H6,H7,H8,SC,H5,H6,H7,H4,SC,H2,H3,H4,H5,H6,H7,H8,H1");
        assertEquals(2, CoinMasterResultUtil.evaluate(twoVisiblePlusTwoBuffer, 1, BigDecimal.ONE, 1).scatterCount());
        assertEquals(0, GameRules.freeAward(2), "buffer SC must not award Mary");
        assertEquals(4, CoinMasterResultUtil.evaluate(fourPlayableScatter, 1, BigDecimal.ONE, 1).scatterCount());
        assertEquals(14, GameRules.freeAward(4));

        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        GameRuleCore core = new GameRuleCore(properties);
        int cascadePairs = 0;
        int bufferRefills = 0;
        for (int ordinal = 0; ordinal < 2_000; ordinal++) {
            RoundPlan round = core.generateRuntimeRound("buf-" + ordinal, "t-" + ordinal,
                    1, new BigDecimal("0.02"), new BigDecimal("999.60"), ordinal);
            RoundDelivery paid = round.deliveries.getFirst();
            if (paid.steps.getLast().fsn > 0) {
                assertEquals(1, paid.steps.size(), "paid Mary trigger must be a single opening Step");
                assertEquals(0, paid.steps.getFirst().wa.signum(),
                        "paid Mary trigger must not have a first-step ways win");
            }
            for (RoundDelivery delivery : round.deliveries) {
                for (int index = 0; index + 1 < delivery.steps.size(); index++) {
                    SpinStep previous = delivery.steps.get(index);
                    SpinStep next = delivery.steps.get(index + 1);
                    cascadePairs++;
                    Set<Integer> winning = new HashSet<>();
                    previous.wmkl.forEach(match -> match.forEach(winning::addAll));
                    for (int reel = 0; reel < GameRules.REELS; reel++) {
                        int removed = 0;
                        for (int visibleRow = 0; visibleRow < GameRules.VISIBLE_ROWS; visibleRow++) {
                            int raw = reel * 10 + visibleRow;
                            if (winning.contains(raw) && !previous.gfl.contains(raw + 1)) removed++;
                        }
                        if (removed == 0) continue;
                        bufferRefills++;
                        assertNotEquals("SC", next.rskl.get(reel * GameRules.TRANSPORT_ROWS),
                                "cascade buffer refill must not be a Mary trigger symbol");
                    }
                }
            }
        }
        assertTrue(cascadePairs > 200, "seeded sample must include cascade pairs");
        assertTrue(bufferRefills > 50, "seeded sample must include buffer refills");
    }

    private RoundPlan round(RoundScenario scenario) {
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        return new GameRuleCore(properties).generateCompleteRound(scenario, "round-1407", "transfer-1407",
                1, new BigDecimal("0.02"), new BigDecimal("999.60"), 1_787_735_408L);
    }

    /**
     * Paid opening: H1 ways win + 2 visible SC. Cascade refill puts a third visible SC
     * on the terminal board with no further win — the captured Mary timing (scenes 182-187).
     */
    private RoundPlan cascadeCompletedScatterRound() {
        RoundPlan round = new RoundPlan();
        round.roundKey = "cascade-sc";
        round.transferId = "cascade-sc";
        round.paidBid = GameRules.GAME_PROTOCOL_ID + "-cascade-sc";
        round.betLevel = 1;
        round.betSize = new BigDecimal("0.02");
        round.betAmount = GameRules.betAmount(round.betLevel, round.betSize);
        round.postDebitBalance = new BigDecimal("999.60");
        round.createdAt = 1L;
        round.scenario = RoundScenario.FREE_SPINS.name();

        List<String> opening = board("H4,H1,H1,H2,H3,H8,H1,H5,H6,H7,H8,H1,H5,H6,H7,H4,SC,H2,H3,H4,H5,H6,SC,H7,H8");
        List<String> terminal = board("H6,SC,H4,H2,H3,H2,H8,H5,H6,H7,H2,H8,H5,H6,H7,H4,SC,H2,H3,H4,H5,H6,SC,H7,H8");
        List<String> idle = board("H8,H1,H2,H3,H4,H8,H5,H6,H7,H8,H8,H1,H5,H6,H7,H8,H2,H3,H7,H8,H8,H1,H4,H6,H8");

        SpinStep paid = fill(round, opening, 1, 0, 1, 0, 0, 0, round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO);
        SpinStep baseEnd = fill(round, terminal, 1, 1, 2, 1, 12, 0, BigDecimal.ZERO, paid.rwa, BigDecimal.ZERO);
        RoundValidator.validateCascadeTransition(paid, baseEnd);
        round.deliveries.add(new RoundDelivery("BASE", List.of(paid, baseEnd)));

        BigDecimal cumulative = baseEnd.rwa;
        for (int nfsc = 1; nfsc <= 12; nfsc++) {
            boolean last = nfsc == 12;
            SpinStep free = fill(round, idle, 2, 2, 2, 1, 12, nfsc, BigDecimal.ZERO, cumulative, BigDecimal.ZERO);
            if (last) free.pb = round.postDebitBalance.add(cumulative).setScale(2).toPlainString();
            round.deliveries.add(new RoundDelivery("FREE", List.of(free)));
        }
        round.totalWin = CoinMasterResultUtil.money(cumulative);
        return round;
    }

    private SpinStep fill(RoundPlan round, List<String> cells, int gt, int smallGameType, int rpx, int ss,
                          int fsn, int nfsc, BigDecimal ba, BigDecimal priorRwa, BigDecimal priorFrwa) {
        CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                cells, round.betLevel, round.betSize, rpx);
        SpinStep step = new SpinStep();
        step.ba = CoinMasterResultUtil.money(ba);
        step.gt = gt;
        step.small_game_type = smallGameType;
        step.rpx = rpx;
        step.ss = ss;
        step.fsn = fsn;
        step.nfsc = nfsc;
        step.pb = round.postDebitBalance.setScale(2).toPlainString();
        step.rskl = new ArrayList<>(cells);
        step.gfl = new ArrayList<>();
        step.silverCardCoordinates = new ArrayList<>(
                CardMaterialState.decodeSilverCoordinates(step.rskl, step.gfl));
        step.wa = evaluation.totalWin();
        step.rwa = CoinMasterResultUtil.money(priorRwa.add(step.wa));
        step.frwa = gt == 2 ? CoinMasterResultUtil.money(priorFrwa.add(step.wa)) : priorFrwa;
        step.matchDetails = evaluation.matches();
        for (WinMatch match : evaluation.matches()) {
            step.wskl.add(match.symbol);
            step.wmkl.add(match.coordinates);
        }
        return step;
    }

    private List<String> board(String csv) {
        return new ArrayList<>(List.of(csv.split(",")));
    }
}
