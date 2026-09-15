package com.cpgame.g1910.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** State-conditioned rejection generator preserving observed outcome and complete-round joint features. */
public final class RoundFactory {
    private final SecureRandom random;
    private final int[] paidWeights;
    private final int[] freeWeights;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;
    public RoundFactory(SecureRandom random) { this(random,GenerationModel.DEFAULT_PAID,GenerationModel.DEFAULT_FREE); }
    public RoundFactory(SecureRandom random,int[] paidWeights,int[] freeWeights) { this.random=random;this.paidWeights=paidWeights.clone();this.freeWeights=freeWeights.clone();losses=new ZeroLossSupport<>(this::lossCandidate,r->GameRuleCore.evaluate(r).outcome()==GameRuleCore.Outcome.LOSS,r->r); }

    public GameRuleCore.CompleteRound ordinary(boolean win) {
        if(!win)return losses.generate(this::lossCandidate,random::nextInt);
        for(int attempt=0;attempt<100000;attempt++){
            var round=new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY, paidBoard(), List.of(),0,1);
            try {
                var outcome=GameRuleCore.evaluate(round).outcome();
                if(win ? outcome==GameRuleCore.Outcome.WIN : outcome==GameRuleCore.Outcome.LOSS)return round;
            } catch(IllegalArgumentException ignored) { }
        }
        throw new IllegalStateException("ordinary rejection budget exhausted");
    }

    public GameRuleCore.CompleteRound smallGame() {
        for(int attempt=0;attempt<100000;attempt++){
            var round=new GameRuleCore.CompleteRound(GameRuleCore.Mode.SMALL_GAME_1, paidBoard(), List.of(),0,1);
            try { GameRuleCore.evaluate(round); return round; } catch(IllegalArgumentException ignored) { }
        }
        throw new IllegalStateException("small-game rejection budget exhausted");
    }

    public GameRuleCore.CompleteRound freeReward() {
        int scatters=random.nextDouble()<0.1765?4:3;
        int[] options=scatters==3?new int[]{8,12,20}:new int[]{12,16,24};
        int times=options[random.nextInt(options.length)], multiplier=new int[]{2,5,8}[random.nextInt(3)];
        GameRuleCore.Step paid;
        for(int attempt=0;;attempt++){
            if(attempt>=100000)throw new IllegalStateException("free trigger rejection budget exhausted");
            int[] board=weightedBoard(false);
            forceScatterCount(board,scatters);
            paid=new GameRuleCore.Step(board);
            if(GameRuleCore.evaluateStep(paid,1).units()==0)break;
        }
        List<GameRuleCore.Step> free=new ArrayList<>();int total=times;
        while(free.size()<total){
            GameRuleCore.Step step=new GameRuleCore.Step(weightedBoard(true));
            free.add(step);
            if(GameRuleCore.evaluateStep(step,multiplier).scatterCount()>=3)total+=GameRuleCore.RETRIGGER_FREE_STEPS;
            if(total>256)throw new CandidateLimitException("free retrigger safety ceiling exceeded");
        }
        var round=new GameRuleCore.CompleteRound(GameRuleCore.Mode.FREE_REWARD,paid,free,times,multiplier);
        GameRuleCore.evaluate(round);
        return round;
    }

    public static final class CandidateLimitException extends RuntimeException {
        public CandidateLimitException(String message) { super(message); }
    }

    private GameRuleCore.Step paidBoard(){return new GameRuleCore.Step(weightedBoard(false));}
    private int[] weightedBoard(boolean free){
        int[]board=new int[15];int[]weights=free?freeWeights:paidWeights;
        for(int column=0;column<5;column++){
            boolean hasScatter=false;
            for(int row=0;row<3;row++){
                int symbol=GenerationModel.choose(random,weights);
                while(symbol==GameRuleCore.SCATTER&&hasScatter)symbol=GenerationModel.choose(random,weights);
                board[column*3+row]=symbol;
                hasScatter|=symbol==GameRuleCore.SCATTER;
            }
        }
        return board;
    }
    private void forceScatterCount(int[]board,int count){
        for(int i=0;i<board.length;i++)if(board[i]==GameRuleCore.SCATTER)board[i]=1;
        List<Integer>columns=new ArrayList<>(List.of(0,1,2,3,4));
        java.util.Collections.shuffle(columns,random);
        for(int placed=0;placed<count;placed++)board[columns.get(placed)*3+random.nextInt(3)]=GameRuleCore.SCATTER;
    }

    public GameRuleCore.CompleteRound lossCandidate(){
        int[] b=new int[15];boolean[] first=new boolean[14];int scatter=0;
        for(int c=0;c<5;c++){
            boolean colScatter=false;
            for(int r=0;r<3;r++){
                int[] w=paidWeights.clone();if(c<2)w[11]=0;
                if(c==1)for(int v=1;v<=11;v++)if(first[v])w[v-1]=0;
                if(scatter>=2||colScatter)w[12]=0;
                int symbol=GenerationModel.choose(random,w);b[c*3+r]=symbol;
                if(c==0&&symbol<=11)first[symbol]=true;
                if(symbol==13){scatter++;colScatter=true;}
            }
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY,new GameRuleCore.Step(b),List.of(),0,1);
    }
}
