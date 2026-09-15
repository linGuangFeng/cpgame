package com.cpgame.g1910.core;

import java.util.List;

/** Independent assertions from one formal provider board plus codec/lifecycle invariants. */
public final class GameRuleCoreTest {
    public static void main(String[] args) {
        int[] providerBoard={4,1,10,11,12,1,1,10,3,10,1,1,3,1,5};
        var provider=GameRuleCore.evaluate(new GameRuleCore.CompleteRound(
            GameRuleCore.Mode.ORDINARY,new GameRuleCore.Step(providerBoard),List.of(),0,1));
        require(provider.paid().wins().size()==7,"formal-provider win-line count");
        require(provider.totalUnits()==2320,"formal-provider payout units");

        int[] trigger={13,1,2,3,4,13,5,6,7,8,13,9,10,11,12};
        int[] freeBoard={1,1,1,2,3,4,5,6,7,8,9,10,11,12,4};
        var freeSteps=new java.util.ArrayList<GameRuleCore.Step>();
        for(int i=0;i<8;i++)freeSteps.add(new GameRuleCore.Step(freeBoard));
        var free=new GameRuleCore.CompleteRound(GameRuleCore.Mode.FREE_REWARD,
            new GameRuleCore.Step(trigger),freeSteps,8,5);
        String encoded=MinimalRoundFactCodec.encode(free);
        var decoded=MinimalRoundFactCodec.decode(encoded);
        require(encoded.equals(MinimalRoundFactCodec.encode(decoded)),"codec round trip");
        require(decoded.freeSteps().size()==decoded.freeTimes(),"complete free lifecycle");
        GameRuleCore.evaluate(decoded);
        boolean rejectedDuplicateColumn=false;
        try { new GameRuleCore.Step(new int[]{13,13,1,2,3,4,5,6,7,8,9,10,11,12,1}); }
        catch(IllegalArgumentException expected) { rejectedDuplicateColumn=true; }
        require(rejectedDuplicateColumn,"provider evidence forbids multiple Scatter symbols in one column");
        var factory=new RoundFactory(new java.security.SecureRandom());
        for(int i=0;i<200;i++)assertDistinctScatterColumns(factory.freeReward());
        System.out.println("GAME_RULE_CORE_TEST_PASS oracleLines=7 oracleUnits=2320 codec=CH40");
    }
    private static void assertDistinctScatterColumns(GameRuleCore.CompleteRound round){
        assertDistinctScatterColumns(round.paid());
        for(var step:round.freeSteps())assertDistinctScatterColumns(step);
    }
    private static void assertDistinctScatterColumns(GameRuleCore.Step step){
        int[]symbols=step.symbols();
        for(int column=0;column<5;column++){
            int scatters=0;
            for(int row=0;row<3;row++)if(symbols[column*3+row]==GameRuleCore.SCATTER)scatters++;
            require(scatters<=1,"generated Scatter symbols must occupy distinct columns");
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
