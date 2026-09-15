package com.cpgame.beachfun.core;

import java.util.*;

/** Original 1024-Ways rules. Sampling belongs to DistributionModel, never to payout evaluation. */
public final class GameRuleCore {
    public static final int GAME_ID=1940, REELS=5, ROWS=4, CELLS=20, SCATTER=9, WILD=10;
    public static final String GAME_NAME="Beach Fun";
    public static final String RULES_HASH="sha256:4332cba4e18f7a2dfb8c99d2c5f8969e7650edc3d109f97204af38fb4d1a2af2";
    public static final int[] BASE_MULTIPLIERS={1,2,3,5}, FREE_MULTIPLIERS={2,4,6,10};
    private static final int[][] PAY={{},{2,5,10},{2,5,10},{4,10,20},{4,10,20},{6,15,40},{8,20,60},{10,40,80},{15,60,100}};

    public List<Win> evaluate(int[] board,int multiplier) {
        requireBoard(board);
        if(multiplier<1) throw new IllegalArgumentException("positive multiplier required");
        List<Win> wins=new ArrayList<>();
        // Scatter never pays. Wild substitutes for regular symbols; it has no separate paytable.
        for(int symbol=1;symbol<=8;symbol++) {
            int reels=0,ways=1;
            List<Integer> positions=new ArrayList<>();
            for(int reel=0;reel<REELS;reel++) {
                int matches=0;
                for(int row=0;row<ROWS;row++) {
                    int pos=reel*ROWS+row;
                    if(board[pos]==symbol||board[pos]==WILD) {matches++;positions.add(pos);}
                }
                if(matches==0) break;
                reels++;ways*=matches;
            }
            if(reels>=3) wins.add(new Win(symbol,reels,ways,PAY[symbol][reels-3],multiplier,positions));
        }
        return List.copyOf(wins);
    }
    public long payoutUnits(List<Win> wins) {
        long total=0;
        for(Win w:wins) total=Math.addExact(total,Math.multiplyExact((long)w.odds,Math.multiplyExact(w.ways,w.multiplier)));
        return total;
    }
    public int scatterCount(int[] board) {requireBoard(board);int n=0;for(int v:board)if(v==SCATTER)n++;return n;}
    public int awardedFreeSpins(int count) {if(count<0||count>CELLS)throw new IllegalArgumentException("scatter count");return count<3?0:12+2*(count-3);}
    public int cascadeMultiplier(boolean free,int index) {
        if(index<0)throw new IllegalArgumentException("cascade index");
        int[] values=free?FREE_MULTIPLIERS:BASE_MULTIPLIERS;return values[Math.min(index,values.length-1)];
    }
    public boolean[] winningPositions(List<Win> wins) {
        boolean[] hit=new boolean[CELLS];
        for(Win w:wins)for(int p:w.positions)hit[p]=true;
        return hit;
    }
    public int[] refillCounts(Cascade prior) {
        boolean[] hit=winningPositions(prior.wins);
        int[] counts=new int[REELS];
        for(int p=0;p<CELLS;p++)if(hit[p]&&!prior.gold[p])counts[p/ROWS]++;
        return counts;
    }
    /** Keeps original bottom-to-top survivor order. New symbols are never gold. */
    public Cascade transition(Cascade prior,int[][] refill,boolean free,int nextIndex) {
        requireBoard(prior.board);requireGold(prior.board,prior.gold);
        if(prior.wins.isEmpty())throw new IllegalArgumentException("cannot refill terminal step");
        if(refill.length!=REELS)throw new IllegalArgumentException("refill reels");
        boolean[] hit=winningPositions(prior.wins);int[] counts=refillCounts(prior);
        int[] board=new int[CELLS];boolean[] gold=new boolean[CELLS];
        for(int reel=0;reel<REELS;reel++) {
            if(refill[reel].length!=counts[reel])throw new IllegalArgumentException("refill count");
            int row=0;
            for(int oldRow=0;oldRow<ROWS;oldRow++) {
                int old=reel*ROWS+oldRow;
                if(hit[old]&&!prior.gold[old])continue;
                int pos=reel*ROWS+row++;
                board[pos]=hit[old]?WILD:prior.board[old];
                gold[pos]=!hit[old]&&prior.gold[old];
            }
            for(int symbol:refill[reel]) {
                if(symbol<1||symbol>SCATTER)throw new IllegalArgumentException("refill cannot create Wild");
                board[reel*ROWS+row++]=symbol;
            }
        }
        return new Cascade(board,gold,evaluate(board,cascadeMultiplier(free,nextIndex)));
    }
    /** Identity projection for the untouched frontend: starting IDs 2..21, then monotonic refill IDs. */
    public List<int[]> cellIds(Delivery delivery) {
        List<int[]> out=new ArrayList<>();int[] ids=new int[CELLS];int next=2;
        for(int p=0;p<CELLS;p++)ids[p]=next++;
        out.add(ids.clone());
        for(int ci=1;ci<delivery.cascades.size();ci++) {
            Cascade prior=delivery.cascades.get(ci-1);boolean[] hit=winningPositions(prior.wins);
            int[] current=new int[CELLS];
            for(int reel=0;reel<REELS;reel++) {
                int row=0;
                for(int r=0;r<ROWS;r++){int p=reel*ROWS+r;if(!hit[p]||prior.gold[p])current[reel*ROWS+row++]=ids[p];}
                while(row<ROWS)current[reel*ROWS+row++]=next++;
            }
            ids=current;out.add(ids.clone());
        }
        return List.copyOf(out);
    }
    public void validate(CompleteRound round) {
        if(round==null||round.deliveries.isEmpty())throw new IllegalArgumentException("empty Round");
        long total=0;int pending=0;boolean hasCascade=false,hasGold=false,hasFree=false;
        for(int di=0;di<round.deliveries.size();di++) {
            Delivery d=round.deliveries.get(di);
            if(d.free!=(di>0)|| (di>0&&pending<=0))throw new IllegalArgumentException("delivery lifecycle");
            if(d.cascades.isEmpty())throw new IllegalArgumentException("empty delivery");
            for(int ci=0;ci<d.cascades.size();ci++) {
                Cascade c=d.cascades.get(ci);requireBoard(c.board);requireGold(c.board,c.gold);
                List<Win> expected=evaluate(c.board,cascadeMultiplier(d.free,ci));
                if(!expected.equals(c.wins))throw new IllegalArgumentException("win facts mismatch");
                total=Math.addExact(total,payoutUnits(expected));
                if((ci==d.cascades.size()-1)!=expected.isEmpty())throw new IllegalArgumentException("terminal cascade boundary");
                for(boolean g:c.gold)hasGold|=g;
                if(ci>0) {
                    Cascade prev=d.cascades.get(ci-1);int[] count=refillCounts(prev);int[][] fill=new int[REELS][];
                    for(int reel=0;reel<REELS;reel++)fill[reel]=Arrays.copyOfRange(c.board,reel*ROWS+ROWS-count[reel],(reel+1)*ROWS);
                    Cascade expectedNext=transition(prev,fill,d.free,ci);
                    if(!Arrays.equals(expectedNext.board,c.board)||!Arrays.equals(expectedNext.gold,c.gold))
                        throw new IllegalArgumentException("survivor/gold/refill transition mismatch");
                }
            }
            int award=awardedFreeSpins(scatterCount(d.cascades.get(d.cascades.size()-1).board));
            if(d.awardedFreeSpins!=award)throw new IllegalArgumentException("free award mismatch");
            pending=(di==0?0:pending-1)+award;
            if(d.remainingFreeSpins!=pending)throw new IllegalArgumentException("free remaining mismatch");
            hasFree|=d.free||award>0;hasCascade|=d.cascades.size()>1;
        }
        if(pending!=0)throw new IllegalArgumentException("unfinished free Round");
        if(round.totalUnits!=total||round.win!=(total>0)||round.freeFeature!=hasFree||round.cascadeFeature!=hasCascade||round.goldFeature!=hasGold)
            throw new IllegalArgumentException("Round summary mismatch");
    }
    private void requireBoard(int[] b) {
        if(b==null||b.length!=CELLS)throw new IllegalArgumentException("board must be 5x4");
        for(int v:b)if(v<1||v>WILD)throw new IllegalArgumentException("symbol range");
    }
    private void requireGold(int[] b,boolean[] g) {
        if(g==null||g.length!=CELLS)throw new IllegalArgumentException("gold size");
        for(int p=0;p<CELLS;p++)if(g[p]&&(p/ROWS<1||p/ROWS>3||b[p]>=SCATTER))throw new IllegalArgumentException("illegal gold");
    }
    public record Win(int symbol,int reels,int ways,int odds,int multiplier,List<Integer>positions) {
        public Win {positions=List.copyOf(positions);}
    }
    public record Cascade(int[]board,boolean[]gold,List<Win>wins) {
        public Cascade {board=board.clone();gold=gold.clone();wins=List.copyOf(wins);}
        @Override public int[]board(){return board.clone();}
        @Override public boolean[]gold(){return gold.clone();}
    }
    public record Delivery(boolean free,int awardedFreeSpins,int remainingFreeSpins,List<Cascade>cascades) {
        public Delivery {cascades=List.copyOf(cascades);}
    }
    public record CompleteRound(String factId,boolean win,boolean freeFeature,boolean cascadeFeature,boolean goldFeature,long totalUnits,List<Delivery>deliveries) {
        public CompleteRound {deliveries=List.copyOf(deliveries);}
    }
}
