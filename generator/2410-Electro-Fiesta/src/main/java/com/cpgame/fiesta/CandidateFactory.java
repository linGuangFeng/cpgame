package com.cpgame.fiesta;

import java.security.SecureRandom;
import java.util.*;

/** 基于已观测联合结构的完整局候选工厂；所有候选仍由 ResultUtil 独立反推。 */
public final class CandidateFactory {
    private final SecureRandom random;
    private final GameRuleCore rules;
    private final ZeroLossSupport<GameRound> losses;
    private final int[] initialWeights, respinWeights, materialWeights;
    public CandidateFactory(SecureRandom random, GameRuleCore rules, Properties p){
        this.random=random; this.rules=rules;
        initialWeights=weights(p,"generation.symbol.initial.");
        respinWeights=weights(p,"generation.symbol.respin-reel.");
        materialWeights=weights(p,"generation.material.multiplier.");
        if(Arrays.stream(initialWeights).sum()<=0||Arrays.stream(respinWeights).sum()<=0||Arrays.stream(materialWeights).sum()<=0)throw new IllegalArgumentException("同一入口权重不能全为0");
        losses=new ZeroLossSupport<>(this::lossCandidate,r->new ResultUtil(rules).analyze(r).outcome()==RoundOutcome.ORDINARY_LOSS,r->r);
    }
    private int[] weights(Properties p,String prefix){int[] values=prefix.contains("material")?new int[]{2,3,5,10}:GameRuleCore.SYMBOLS;int[] out=new int[values.length];for(int i=0;i<out.length;i++){String key=prefix+values[i];String raw=p.getProperty(key);if(raw==null||raw.isBlank())throw new IllegalArgumentException("missing "+key);out[i]=Integer.parseInt(raw.trim());if(out[i]<=0)throw new IllegalArgumentException(key+" must be positive");}return out;}
    private int pick(int[] values,int[] weights){int total=Arrays.stream(weights).sum(), n=random.nextInt(total);for(int i=0;i<weights.length;i++){n-=weights[i];if(n<0)return values[i];}throw new IllegalStateException();}
    private int initial(){return pick(GameRuleCore.SYMBOLS,initialWeights);}
    private int respin(){return pick(GameRuleCore.SYMBOLS,respinWeights);}
    private int material(){return pick(new int[]{2,3,5,10},materialWeights);}
    private int[] randomBoard(){int[] b=new int[9];for(int i=0;i<9;i++)b[i]=initial();return b;}
    private RoundState state(RoundState.Mode m,int[] b,int[] mul,int[] add,int remaining,int col,int base){return new RoundState(m,b,mul,add,remaining,col,base);}

    public GameRound ordinary(boolean wantWin){
        if(!wantWin)return losses.generate(this::lossCandidate,random::nextInt);
        for(int attempt=0;attempt<100000;attempt++){int[] b=randomBoard(); if(!structuralFeature(b) && (rules.payoutUnits(b)>0)==wantWin)return new GameRound(List.of(state(RoundState.Mode.NORMAL,b,new int[9],new int[0],0,-1,0)));}
        throw new IllegalStateException("ordinary candidate exhausted");
    }
    static boolean structuralFeature(int[] b){
        boolean all=true;for(int i=1;i<9;i++)all&=b[i]==b[0];if(all)return true;
        for(int target:GameRuleCore.SYMBOLS){int full=0;for(int c=0;c<3;c++)if(b[c*3]==target&&b[c*3+1]==target&&b[c*3+2]==target)full++;if(full>=2)return true;}return false;
    }
    public GameRound respinRound(int maxSteps){
        int target=initial(), col=random.nextInt(3); int[] board=new int[9]; Arrays.fill(board,target); for(int r=0;r<3;r++){int v;do{v=respin();}while(v==target);board[col*3+r]=v;}
        int steps=Math.min(maxSteps,pick(new int[]{2,3,4,5,6,7,8,9,10,11},new int[]{24,5,8,2,3,6,3,4,1,1})); List<RoundState> out=new ArrayList<>(); out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,board,new int[9],new int[0],1,col,target));
        for(int i=1;i<steps;i++){board=board.clone();for(int r=0;r<3;r++)board[col*3+r]=(i==steps-1?target:respin());if(i<steps-1&&board[col*3]==target&&board[col*3+1]==target&&board[col*3+2]==target)board[col*3+random.nextInt(3)]=different(target);out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,board,new int[9],new int[0],i==steps-1?0:1,col,target));}
        return new GameRound(out);
    }
    private int different(int target){int v;do{v=respin();}while(v==target);return v;}
    public GameRound multiplierRound(int maxSteps){
        int base=pick(new int[]{2,3,5,10,50},new int[]{4,2,4,1,1}); int[] board=new int[9];Arrays.fill(board,base);int[] mul=new int[9];List<RoundState> out=new ArrayList<>();out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[0],1,0,base));
        int additions=Math.min(Math.min(9,maxSteps-1),pick(new int[]{4,5,6,7,9,10},new int[]{1,2,1,1,1,1})-1);List<Integer> free=new ArrayList<>();for(int i=0;i<9;i++)free.add(i);Collections.shuffle(free,random);
        for(int i=0;i<additions;i++){mul=mul.clone();int pos=free.get(i);mul[pos]=material();out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[]{pos},i==additions-1?0:1,0,base));}
        return new GameRound(out);
    }

    public GameRound lossCandidate(){
        int[] b=randomBoard();Set<Integer> first=new HashSet<>();for(int i=0;i<3;i++)first.add(b[i]);
        for(int i=3;i<6;i++){
            int[] w=initialWeights.clone();for(int j=0;j<w.length;j++)if(first.contains(GameRuleCore.SYMBOLS[j]))w[j]=0;
            b[i]=pick(GameRuleCore.SYMBOLS,w);
        }
        // A pair of equal full reels triggers a feature even without a payline win.
        if(structuralFeature(b)) {
            int[] w=initialWeights.clone();for(int j=0;j<w.length;j++)if(GameRuleCore.SYMBOLS[j]==b[6])w[j]=0;
            b[6]=pick(GameRuleCore.SYMBOLS,w);
        }
        return new GameRound(List.of(state(RoundState.Mode.NORMAL,b,new int[9],new int[0],0,-1,0)));
    }
}
