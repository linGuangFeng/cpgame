package com.cpgame.batchd.treasurehunt.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class RoundFactory {
    private final SecureRandom secureRandom;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;
    public RoundFactory(SecureRandom secureRandom){this.secureRandom=secureRandom;losses=new ZeroLossSupport<>(this::lossCandidate,r->GameRuleCore.evaluate(r).outcome()==GameRuleCore.Outcome.LOSS,r->r);}
    public GameRuleCore.CompleteRound ordinary(){return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY,List.of(new GameRuleCore.Step(sampleBoard())));}
    public GameRuleCore.CompleteRound ordinary(GameRuleCore.Outcome requested){if(requested==GameRuleCore.Outcome.LOSS)return losses.generate(this::lossCandidate,secureRandom::nextInt);if(requested!=GameRuleCore.Outcome.LOSS&&requested!=GameRuleCore.Outcome.WIN)throw new IllegalArgumentException("ordinary outcome required");for(int i=0;i<20000;i++){var r=ordinary();if(GameRuleCore.evaluate(r).outcome()==requested)return r;}throw new IllegalStateException("ordinary rejection budget exhausted");}
    public GameRuleCore.CompleteRound treasureHunt(){int steps=GenerationModel.treasureLength(secureRandom),target=1+secureRandom.nextInt(6);List<GameRuleCore.Step> list=new ArrayList<>();for(int i=0;i<steps-1;i++)list.add(new GameRuleCore.Step(treasureBoard(target,false)));list.add(new GameRuleCore.Step(treasureBoard(target,true)));return new GameRuleCore.CompleteRound(GameRuleCore.Mode.TREASURE_HUNT,list);}
    public GameRuleCore.CompleteRound allReelsMultiplier(){int target=1+secureRandom.nextInt(6);int[] board=new int[10];for(int i=0;i<10;i++)board[i]=target;if(secureRandom.nextBoolean())board[secureRandom.nextInt(10)]=GameRuleCore.WILD;return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ALL_REELS_MULTIPLIER,List.of(new GameRuleCore.Step(board)));}
    private int[] sampleBoard(){int[] b=new int[10];int previous=1+secureRandom.nextInt(6);for(int i=0;i<10;i++){b[i]=GenerationModel.symbol(secureRandom,i,previous);previous=b[i];}return b;}
    private int[] treasureBoard(int target,boolean terminal){int[] b=new int[10];for(int i=0;i<3;i++)b[i]=target;for(int i=7;i<10;i++)b[i]=target;for(int i=3;i<7;i++){if(terminal&&i==4)b[i]=target;else{int v;do{v=1+secureRandom.nextInt(6);}while(v==target);b[i]=v;}}return b;}

    public GameRuleCore.CompleteRound lossCandidate(){
        int[] b=new int[10];boolean[] none=new boolean[8],first=new boolean[8];int previous=1;
        for(int i=0;i<10;i++){
            b[i]=GenerationModel.lossSymbol(secureRandom,i,previous,i>=3&&i<7?first:none);
            if(i<3)first[b[i]]=true;previous=b[i];
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY,List.of(new GameRuleCore.Step(b)));
    }
}
