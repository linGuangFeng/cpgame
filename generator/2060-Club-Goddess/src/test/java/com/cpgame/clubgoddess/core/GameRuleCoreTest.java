package com.cpgame.clubgoddess.core;

import static org.junit.jupiter.api.Assertions.*;
import com.cpgame.clubgoddess.codec.MinimalRoundFactCodec;
import com.cpgame.clubgoddess.core.GameModels.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GameRuleCoreTest {
    @Test void independentOracleBoardMatchesCapturedWaysEvidence() {
        List<Integer> board = List.of(6,1,6,10,6,3,1,6,10,1,1,2,1,1,1);
        List<WinItem> wins = ResultMath.evaluate(board, new BigDecimal("0.01"), 10);
        assertEquals(2, wins.size());
        assertEquals(6, wins.get(0).wp()); assertEquals(8, wins.get(0).ways());
        assertEquals(List.of(0,2,3,4,7,8), wins.get(0).pos_arr()); assertEquals(0, wins.get(0).tw().compareTo(new BigDecimal("6.4")));
        assertEquals(1, wins.get(1).wp()); assertEquals(12, wins.get(1).ways());
        assertEquals(List.of(1,3,6,8,9,10,12,13,14), wins.get(1).pos_arr()); assertEquals(0, wins.get(1).tw().compareTo(new BigDecimal("180")));
        assertEquals(0, ResultMath.total(wins).compareTo(new BigDecimal("186.4")));

        GameResult oracle = new GameResult(new BigDecimal("0.01"), new BigDecimal("3"),
                new BigDecimal("183.4"), new BigDecimal("1156.4"), Frees.disabledBaseState(), 10,
                new BigDecimal("62.13333333333333"), "oracle", new Props(0, 1, board, 0,
                new BigDecimal("186.4"), wins), 2, 0, new BigDecimal("973"),
                new BigDecimal("186.4"), 1);
        ResultAnalysis analysis = ResultUtil.analyze(oracle);
        assertEquals(ResultMode.ORDINARY_PAID_WIN, analysis.mode());
        assertEquals(0, analysis.stakeMultiplier().compareTo(new BigDecimal("62.13333333333333")));
        assertFalse(analysis.hasContinuation());
    }

    @Test void formalRoundIsSingleVerifiedDeliveryAndTestSeedIsReproducible() {
        GameRuleCore a = new GameRuleCore(new Random(2060));
        GameRuleCore b = new GameRuleCore(new Random(2060));
        RoundBundle ra = a.generateOrdinaryRound(new BigDecimal("0.01"), 10, new BigDecimal("1000"));
        RoundBundle rb = b.generateOrdinaryRound(new BigDecimal("0.01"), 10, new BigDecimal("1000"));
        assertEquals(ra.deliveries().get(0).result().props().prop(), rb.deliveries().get(0).result().props().prop());
        assertEquals(1, ra.deliveries().size());
        assertTrue(ra.deliveries().get(0).terminal());
        assertEquals(ra.analysis().mode(), RoundVerifier.verify(ra).mode());
    }

    @Test void realtimeLossIsFreshAndIndependentlyVerified() {
        GameRuleCore core = new GameRuleCore();
        GameResult first = core.generateIndependentLoss(new BigDecimal("0.01"), 10, new BigDecimal("1000"), "a");
        GameResult second = core.generateIndependentLoss(new BigDecimal("0.01"), 10, new BigDecimal("997"), "b");
        assertTrue(ResultUtil.isIndependentOrdinaryLoss(first));
        assertTrue(ResultUtil.isIndependentOrdinaryLoss(second));
        assertNotEquals(first.props().prop(), second.props().prop());
    }

    @Test void independentVerifierRejectsTamperedProjection() {
        RoundBundle round = new GameRuleCore(new Random(99)).generateRound(
                new BigDecimal("0.01"), 10, new BigDecimal("1000"));
        GameResult source = round.deliveries().get(0).result();
        GameResult tampered = new GameResult(source.bet(), source.bet_gold(), source.change_gold(),
                source.end_gold(), source.frees(), source.level(), source.odds(), source.oid(),
                source.props(), source.setting_id(), source.small_game_type(), source.start_gold(),
                source.total_win().add(BigDecimal.ONE), source.type());
        assertThrows(IllegalArgumentException.class, () -> ResultUtil.analyze(tampered));
    }

    @Test void minimalRedisMemberRoundTripsThroughIndependentResultUtil() {
        RoundBundle round = new GameRuleCore(new Random(7)).generateRound(
                new BigDecimal("0.01"), 10, new BigDecimal("1000"));
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        String member = codec.encode(round);
        RoundBundle rebuilt = codec.verify(member);
        assertEquals(round.roundKey(), rebuilt.roundKey());
        assertEquals(round.deliveries().get(0).deliveryIndex(), rebuilt.deliveries().get(0).deliveryIndex());
        assertEquals(0, round.analysis().stakeMultiplier().compareTo(rebuilt.analysis().stakeMultiplier()));
        assertFalse(member.contains("totalWin"));
        assertFalse(member.contains("start_gold"));
    }

    @Test void empiricalEntriesRespectObservedSymbolCaps() {
        RandomBoardCandidateGenerator sampler = new RandomBoardCandidateGenerator(new Random(2060));
        for(int i=0;i<10000;i++) {
            List<Integer> paid=sampler.nextBoard(), free=sampler.nextFreeBoard(15);
            ResultMath.validateBoardSymbols(paid); ResultMath.validateBoardSymbols(free);
            assertEquals(0,free.stream().filter(v->v==9).count());
            assertTrue(paid.stream().filter(v->v==10).count()<=2);
            assertEquals(0,sampler.nextFreeBoard(0).stream().filter(v->v==10).count());
        }
    }

    @Test void tenThousandGeneratedCompleteRoundsAndOneHundredSpecialRoundsValidate() {
        GameRuleCore core=new GameRuleCore();MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        int losses=0,wins=0;
        for(int i=0;i<10_000;i++){
            RoundBundle round=core.generateOrdinaryRound(new BigDecimal("0.01"),10,new BigDecimal("1000000"));
            RoundVerifier.verify(round);RoundBundle decoded=codec.verify(codec.encode(round));
            assertEquals(ResultUtil.integerMultiplier(round),ResultUtil.integerMultiplier(decoded));
            if(round.analysis().mode()==ResultMode.ORDINARY_PAID_LOSS)losses++;else wins++;
        }
        for(int i=0;i<100;i++){
            RoundBundle round=core.generateSpecialRound(new BigDecimal("0.01"),10,new BigDecimal("1000000"));
            assertEquals(ResultMode.FREE_SPINS,RoundVerifier.verify(round).mode());
            assertEquals(round.deliveries().size(),codec.verify(codec.encode(round)).deliveries().size());
        }
        assertTrue(losses>0);assertTrue(wins>0);
    }
}
