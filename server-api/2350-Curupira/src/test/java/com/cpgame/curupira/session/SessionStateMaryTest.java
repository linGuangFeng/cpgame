package com.cpgame.curupira.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.cpgame.curupira.api.DemoSelectionPolicy;
import com.cpgame.curupira.api.RoundSource;
import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.EntryKind;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.FeatureStep;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionStateMaryTest {
    @Test
    void cachedTriggerCanContinueThroughEitherCompleteMaryWithoutLiveGeneration() {
        GameRuleCore core = new GameRuleCore();
        verifyMary(2, core.generateFreeExpandingWildCandidate());
        verifyMary(3, core.generateHoldAndSpinsCandidate());
    }

    private static void verifyMary(int gameType, CompleteRoundFact mary) {
        CompleteRoundFact trigger = trigger();
        CompleteRoundFact loss = new MinimalFactCodec().decode("CU1PL;S111222333444AAA");
        RoundSource source = new RoundSource() {
            @Override public CompleteRoundFact peekLoss() { return loss; }
            @Override public CompleteRoundFact claimPaidAtOrBelow(int targetMultiplier) { return trigger; }
            @Override public CompleteRoundFact claimMaryAtOrBelow(Kind kind, int targetMultiplier) {
                assertThat(kind).isEqualTo(mary.kind());
                return mary;
            }
        };
        DemoSelectionPolicy policy = new DemoSelectionPolicy(
                1,0,0,0,0,0, 1,0,0,0,0,0, 1,0,0,0,0,0);
        SessionState session = new SessionState("token", 123L, "opaque", new BigDecimal("1000.00"),
                500, policy);

        Map<String, Object> paid = session.play(1, 1, new BigDecimal("0.02"), 1, "paid", source);
        assertThat(((Map<?, ?>) paid.get("f")).get("t")).isEqualTo(1);

        Map<String, Object> step = null;
        for (int index = 0; index < mary.steps().size(); index++) {
            step = session.play(2, gameType, new BigDecimal("0.02"), 1, "mary-" + index, source);
        }
        assertThat(((Map<?, ?>) step.get("f")).get("st")).isEqualTo(0);
        assertThat(session.historyRounds()).hasSize(1);
        assertThat(session.historyRounds().getFirst().steps).hasSize(1 + mary.steps().size());
    }

    private static CompleteRoundFact trigger() {
        List<Integer> cells = List.of(31,1,2, 31,3,4, 31,11,12, 13,14,1, 2,3,4);
        var evaluated = new ResultUtil().evaluate(cells);
        return new CompleteRoundFact(10_000_000_000_000_001L, Kind.TRIGGER, EntryKind.PAID,
                List.of(FeatureStep.symbol(FeatureStep.Role.TRIGGER, cells, evaluated, 1, 1, 1, 1, 1)));
    }
}
