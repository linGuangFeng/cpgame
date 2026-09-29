package com.cpgame.fiesta;

import java.security.SecureRandom;
import java.util.*;

/** Loader-only exhaustive coverage of the 6^9 boards; no provider records or frequency tables. */
final class OrdinaryBoardCatalog {
    private final int[][] boards;
    private final double[][] cumulative;
    final int boardCount;
    private final int[] ratios;
    OrdinaryBoardCatalog(int[] weights){
        List<Ints> bins=new ArrayList<>();for(int i=0;i<=250;i++)bins.add(new Ints());
        int[] d=new int[9];int count=0;
        for(int code=0;code<10_077_696;code++){
            int n=code;for(int i=0;i<9;i++){d[i]=n%6;n/=6;}
            int payout=0;
            if(d[1]==d[4]&&d[4]==d[7])payout+=GameRuleCore.SYMBOLS[d[1]];
            if(d[0]==d[3]&&d[3]==d[6])payout+=GameRuleCore.SYMBOLS[d[0]];
            if(d[2]==d[5]&&d[5]==d[8])payout+=GameRuleCore.SYMBOLS[d[2]];
            if(d[0]==d[4]&&d[4]==d[8])payout+=GameRuleCore.SYMBOLS[d[0]];
            if(d[2]==d[4]&&d[4]==d[6])payout+=GameRuleCore.SYMBOLS[d[2]];
            if(payout==0)continue;
            boolean full=true;for(int i=1;i<9;i++)full&=d[i]==d[0];if(full)continue;
            bins.get(payout).add(code);count++;
        }
        boardCount=count;
        int size=(int)bins.stream().filter(b->b.size>0).count();ratios=new int[size];boards=new int[size][];cumulative=new double[size][];
        int b=0;double max=Arrays.stream(weights).max().orElseThrow();
        for(int ratio=0;ratio<bins.size();ratio++){Ints bin=bins.get(ratio);if(bin.size==0)continue;
            ratios[b]=ratio;
            boards[b]=Arrays.copyOf(bin.values,bin.size);cumulative[b]=new double[bin.size];double sum=0;
            for(int i=0;i<bin.size;i++){int code=boards[b][i];double w=1;for(int p=0;p<9;p++){w*=weights[code%6]/max;code/=6;}sum+=w;cumulative[b][i]=sum;}
            b++;
        }
    }
    int[] next(SecureRandom random){
        // Equal coverage of reachable payout buckets, then configured symbol weights within the bucket.
        int b=random.nextInt(boards.length);double[] c=cumulative[b];double target=random.nextDouble()*c[c.length-1];
        int lo=0,hi=c.length-1;while(lo<hi){int mid=(lo+hi)>>>1;if(target<c[mid])hi=mid;else lo=mid+1;}
        int code=boards[b][lo];int[] board=new int[9];for(int i=0;i<9;i++){board[i]=GameRuleCore.SYMBOLS[code%6];code/=6;}return board;
    }
    int bucketCount(){return boards.length;}
    int ratio(int bucket){return ratios[bucket];}
    int size(int bucket){return boards[bucket].length;}
    int[] board(int bucket,int index){
        int code=boards[bucket][index];int[] b=new int[9];
        for(int i=0;i<9;i++){b[i]=GameRuleCore.SYMBOLS[code%6];code/=6;}return b;
    }
    private static final class Ints {int[] values=new int[32];int size;void add(int v){if(size==values.length)values=Arrays.copyOf(values,size*2);values[size++]=v;}}
}
