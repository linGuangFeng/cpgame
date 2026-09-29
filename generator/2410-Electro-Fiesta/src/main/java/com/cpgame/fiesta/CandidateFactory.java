package com.cpgame.fiesta;

import java.security.SecureRandom;
import java.util.*;

/** 基于已观测联合结构的完整局候选工厂；所有候选仍由 ResultUtil 独立反推。 */
public final class CandidateFactory {
    private final SecureRandom random;
    private final GameRuleCore rules;
    private final ZeroLossSupport<GameRound> losses;
    private OrdinaryBoardCatalog ordinaryCatalog;
    private final int[] initialWeights, respinWeights, materialWeights;
    public CandidateFactory(SecureRandom random, GameRuleCore rules, Properties p){
        this.random=random; this.rules=rules;
        initialWeights=weights(p,"generation.symbol.initial.");
        respinWeights=weights(p,"generation.symbol.respin-reel.");
        materialWeights=weights(p,"generation.material.multiplier.");
        if(Arrays.stream(initialWeights).sum()<=0||Arrays.stream(respinWeights).sum()<=0||Arrays.stream(materialWeights).sum()<=0)throw new IllegalArgumentException("同一入口权重不能全为0");
        losses=new ZeroLossSupport<>(this::lossCandidate,r->new ResultUtil(rules).analyze(r).outcome()==RoundOutcome.ORDINARY_LOSS,r->r);
    }
    private int[] weights(Properties p,String prefix){int[] values=prefix.contains("material")?new int[]{2,3,5,10}:GameRuleCore.SYMBOLS;int[] out=new int[values.length];for(int i=0;i<out.length;i++){String key=prefix+values[i];String raw=p.getProperty(key,"1");out[i]=Integer.parseInt(raw.trim());if(out[i]<=0)throw new IllegalArgumentException(key+" must be positive");}return out;}
    private int pick(int[] values,int[] weights){int total=Arrays.stream(weights).sum(), n=random.nextInt(total);for(int i=0;i<weights.length;i++){n-=weights[i];if(n<0)return values[i];}throw new IllegalStateException();}
    private int initial(){return pick(GameRuleCore.SYMBOLS,initialWeights);}
    private int respin(){return pick(GameRuleCore.SYMBOLS,respinWeights);}
    private int material(){return pick(new int[]{2,3,5,10},materialWeights);}
    private int[] randomBoard(){int[] b=new int[9];for(int i=0;i<9;i++)b[i]=initial();return b;}
    private RoundState state(RoundState.Mode m,int[] b,int[] mul,int[] add,int remaining,int col,int base){return new RoundState(m,b,mul,add,remaining,col,base);}

    public synchronized GameRound ordinary(boolean wantWin){
        if(!wantWin)return losses.generate(this::lossCandidate,random::nextInt);
        if(ordinaryCatalog==null)ordinaryCatalog=new OrdinaryBoardCatalog(initialWeights);
        int[] b=ordinaryCatalog.next(random);
        return new GameRound(List.of(state(RoundState.Mode.NORMAL,b,new int[9],new int[0],0,-1,0)));
    }
    static boolean structuralFeature(int[] b){
        return GameRuleCore.fullScreen(b)||new GameRuleCore().respinColumn(b)>=0;
    }
    public GameRound respinRound(int maxSteps){
        if(maxSteps<2)throw new IllegalArgumentException("respin requires at least 2 pages");
        int target=initial(),col=random.nextInt(3),cap=Math.min(11,maxSteps);
        int[] board=new int[9];Arrays.fill(board,target);
        for(int r=0;r<3;r++)board[col*3+r]=different(target);
        List<RoundState> out=new ArrayList<>();
        out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,board,new int[9],new int[0],1,col,target));
        for(int step=1;step<cap;step++){
            board=board.clone();
            // Enumerate all 216 reel outcomes. The last allowed attempt is conditioned on a win.
            int[] reel=nextReel(target,step==cap-1);
            System.arraycopy(reel,0,board,col*3,3);
            if(GameRuleCore.fullScreen(board)){
                appendMultiplier(out,board,Math.min(11,19-out.size()));
                return new GameRound(out);
            }
            boolean win=rules.payoutUnits(board)>0;
            out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,board,new int[9],new int[0],win?0:1,col,target));
            if(win)return new GameRound(out);
        }
        throw new IllegalStateException("conditioned reel did not win");
    }
    private int[] nextReel(int target,boolean mustWin){
        double total=0;double[] weights=new double[216];
        for(int code=0;code<216;code++){
            int a=code%6,b=code/6%6,c=code/36;
            if(mustWin&&GameRuleCore.SYMBOLS[a]!=target&&GameRuleCore.SYMBOLS[b]!=target&&GameRuleCore.SYMBOLS[c]!=target)continue;
            weights[code]=(double)respinWeights[a]*respinWeights[b]*respinWeights[c];total+=weights[code];
        }
        double n=random.nextDouble()*total;
        for(int code=0;code<216;code++){n-=weights[code];if(n<0)return new int[]{GameRuleCore.SYMBOLS[code%6],GameRuleCore.SYMBOLS[code/6%6],GameRuleCore.SYMBOLS[code/36]};}
        throw new IllegalStateException("reel selection");
    }
    private int different(int target){int[] w=respinWeights.clone();for(int i=0;i<w.length;i++)if(GameRuleCore.SYMBOLS[i]==target)w[i]=0;return pick(GameRuleCore.SYMBOLS,w);}
    public GameRound multiplierRound(int maxSteps){
        int[] board=new int[9];Arrays.fill(board,initial());List<RoundState> out=new ArrayList<>();
        appendMultiplier(out,board,Math.min(11,maxSteps));return new GameRound(out);
    }
    private void appendMultiplier(List<RoundState> out,int[] board,int maxSteps){
        if(maxSteps<3)throw new IllegalArgumentException("multiplier requires entry, addition, and no-addition settlement");
        int[] mul=new int[9];int base=board[0];
        out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[0],1,0,base));
        int maxAdditions=Math.min(9,maxSteps-2),minAdditions=Math.min(3,maxAdditions);
        int additions=minAdditions+random.nextInt(maxAdditions-minAdditions+1);
        List<Integer> free=new ArrayList<>();for(int i=0;i<9;i++)free.add(i);Collections.shuffle(free,random);
        int[] materials=materialAssignment(additions);
        for(int i=0;i<additions;i++){
            mul=mul.clone();int pos=free.get(i);mul[pos]=materials[i];
            boolean maximum=rules.payoutUnits(board)*Arrays.stream(mul).sum()>=22500;
            out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[]{pos},maximum?0:1,0,base));
            if(maximum)return;
        }
        out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[0],0,0,base));
    }

    /** Enumerate reachable multiplier sums, then use configured weights conditional on the selected sum. */
    private int[] materialAssignment(int count){
        int[] values={2,3,5,10};double[][] mass=new double[count+1][count*10+1];mass[0][0]=1;
        for(int n=1;n<=count;n++)for(int sum=0;sum<=count*10;sum++)
            for(int v=0;v<values.length;v++)if(sum>=values[v])mass[n][sum]+=materialWeights[v]*mass[n-1][sum-values[v]];
        List<Integer> sums=new ArrayList<>();for(int sum=1;sum<=count*10;sum++)if(mass[count][sum]>0)sums.add(sum);
        return materialAssignment(count,sums.get(random.nextInt(sums.size())),mass);
    }
    private int[] materialAssignment(int count,int remaining,double[][] mass){
        int[] values={2,3,5,10};int[] out=new int[count];
        for(int n=count;n>0;n--){
            double pick=random.nextDouble()*mass[n][remaining];
            for(int v=0;v<values.length;v++){
                if(remaining<values[v])continue;
                pick-=materialWeights[v]*mass[n-1][remaining-values[v]];
                if(pick<0){out[count-n]=values[v];remaining-=values[v];break;}
            }
            if(out[count-n]==0)throw new IllegalStateException("multiplier selection");
        }
        return out;
    }

    // Exact legal entry construction for the finite enumeration Loader. No configured weights.
    static boolean materialSumReachable(int count,int sum){return materialMass(count,sum)!=null;}
    private static double[][] materialMass(int count,int sum){
        if(count<1||sum<0||sum>count*10)return null;
        double[][] mass=new double[count+1][count*10+1];mass[0][0]=1;
        for(int n=1;n<=count;n++)for(int total=0;total<=count*10;total++)
            for(int v:new int[]{2,3,5,10})if(total>=v)mass[n][total]+=mass[n-1][total-v];
        return mass[count][sum]>0?mass:null;
    }
    GameRound enumeratedMultiplier(int base,int count,int sum,boolean fromRespin){
        double[][] mass=materialMass(count,sum);if(mass==null)throw new IllegalArgumentException("unreachable multiplier sum");
        int[] board=new int[9];Arrays.fill(board,base);List<RoundState> out=new ArrayList<>();
        if(fromRespin){int col=random.nextInt(3);int[] entry=board.clone();for(int r=0;r<3;r++)entry[col*3+r]=different(base);
            out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,entry,new int[9],new int[0],1,col,base));}
        int[] mul=new int[9];out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[0],1,0,base));
        List<Integer> positions=new ArrayList<>();for(int i=0;i<9;i++)positions.add(i);Collections.shuffle(positions,random);
        int[] materials=materialAssignment(count,sum,mass);
        for(int i=0;i<count;i++){
            mul=mul.clone();int pos=positions.get(i);mul[pos]=materials[i];
            boolean maximum=base*5*Arrays.stream(mul).sum()>=22500;
            out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[]{pos},maximum?0:1,0,base));
            if(maximum)return new GameRound(out);
        }
        out.add(state(RoundState.Mode.MULTIPLIER_STICKY,board,mul,new int[0],0,0,base));return new GameRound(out);
    }
    GameRound enumeratedRespin(int base,int col,int mask,int maxSteps){
        if(maxSteps<2||mask<1||mask>6)throw new IllegalArgumentException("invalid respin structure");
        List<RoundState> out=new ArrayList<>();int pages=2+random.nextInt(Math.min(11,maxSteps)-1);
        for(int i=0;i<pages;i++){
            int[] board=new int[9];Arrays.fill(board,base);
            for(int r=0;r<3;r++)board[col*3+r]=i==pages-1&&(mask&(1<<r))!=0?base:different(base);
            out.add(state(RoundState.Mode.RESPIN_UNTIL_WIN,board,new int[9],new int[0],i==pages-1?0:1,col,base));
        }
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
