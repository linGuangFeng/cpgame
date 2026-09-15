package com.cpgame.g1910.core;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free executable checks using provider-observed expected values. */
public final class GameRuleCoreTest {
    private GameRuleCoreTest() { }

    public static void main(String[] args) {
        if (GameRuleCore.GAME_ID != 1910L || GameRuleCore.GAME_ID >= 8_000_000L) {
            throw new AssertionError("raw gid invariant failed");
        }
        if (!GameRuleCore.runtimeIndependentLossSupported()) throw new AssertionError("preloaded LOSS must be supported");
        int[] providerBoard={4,1,10,11,12,1,1,10,3,10,1,1,3,1,5};
        var provider=GameRuleCore.evaluateStep(new GameRuleCore.Step(providerBoard),1);
        if(provider.units()!=2320||provider.wins().size()!=7)throw new AssertionError("provider payline oracle mismatch: "+provider);
        int[] trigger={2,2,7,13,8,10,13,2,3,7,9,2,13,7,3};
        int[] neutral={1,3,7,4,8,10,2,5,9,6,11,3,7,4,8};List<GameRuleCore.Step>free=new ArrayList<>();for(int i=0;i<8;i++)free.add(new GameRuleCore.Step(neutral));
        var round=new GameRuleCore.CompleteRound(GameRuleCore.Mode.FREE_REWARD,new GameRuleCore.Step(trigger),free,8,5);
        var evaluation=GameRuleCore.evaluate(round);String fact=MinimalRoundFactCodec.encode(round);
        if(evaluation.outcome()!=GameRuleCore.Outcome.FREE_REWARD||ResultUtil.totalUnits(fact)!=evaluation.totalUnits())throw new AssertionError("complete-round codec mismatch");
        if(MinimalRoundFactCodec.decode(fact).freeSteps().size()!=8)throw new AssertionError("codec lost continuation steps");
        System.out.println("gid 1910 GameRuleCore provider-oracle and codec PASS");
    }
}
