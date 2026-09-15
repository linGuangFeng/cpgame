package com.hd.cpgame.jungleparty;

import java.math.BigDecimal;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

public final class GameRuleCoreTest {
    @Test
    void generatedRoundsAreIndependentlyVerifiedAndCodecSafe() { verifyRounds(); }

    public static void main(String[] args) { verifyRounds(); }

    private static void verifyRounds() {
        SecureRandom random = new SecureRandom();
        for (GameRuleCore.Scenario scenario : new GameRuleCore.Scenario[]{GameRuleCore.Scenario.ORDINARY_LOSS,GameRuleCore.Scenario.ORDINARY_WIN,GameRuleCore.Scenario.SCATTER_FREE_ROUNDS}) {
            for (int i=0;i<100;i++) {
                GameRuleCore.Round round=GameRuleCore.generate(random,scenario,1,new BigDecimal("0.02"));
                IndependentVerifier.Verification result=IndependentVerifier.verify(round);
                if(!result.pass())throw new AssertionError(result.errors());
                GameRuleCore.Round decoded=MemberCodec.decode(MemberCodec.encode(round));
                if(decoded.rawGameId()!=round.rawGameId() || decoded.scenario()!=round.scenario() ||
                    decoded.totalAward().compareTo(round.totalAward())!=0 || decoded.deliveries().size()!=round.deliveries().size())
                    throw new AssertionError("codec mismatch");
                if(scenario==GameRuleCore.Scenario.ORDINARY_LOSS && round.totalAward().signum()!=0)throw new AssertionError("loss paid");
                if(scenario==GameRuleCore.Scenario.ORDINARY_WIN && round.totalAward().signum()<=0)throw new AssertionError("win did not pay");
                if(scenario==GameRuleCore.Scenario.SCATTER_FREE_ROUNDS) {
                    GameRuleCore.Delivery last=round.deliveries().get(round.deliveries().size()-1);
                    if(round.deliveries().size()!=last.fsn()+1 || last.nfsc()!=last.fsn())throw new AssertionError("free Round is not complete");
                }
                GameRuleCore.Round repriced = GameRuleCore.reprice(round, 3, new BigDecimal("0.12"));
                if (!IndependentVerifier.verify(repriced).pass()) throw new AssertionError("reprice verification failed");
            }
        }
        System.out.println("GameRuleCoreTest PASS 300 complete Rounds");
    }
}
