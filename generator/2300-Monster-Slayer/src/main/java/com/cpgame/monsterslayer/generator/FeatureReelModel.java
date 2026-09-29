package com.cpgame.monsterslayer.generator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
/** Aggregate whole-reel distribution, trained without the reserved complete rounds. */
final class FeatureReelModel {
    private record Reel(int[] cells,int cumulative){}
    private static final List<Reel>[][] MODEL=load();
    @SuppressWarnings("unchecked") private static List<Reel>[][] load(){
        List<Reel>[][] out=new List[2][5];for(int k=0;k<2;k++)for(int c=0;c<5;c++)out[k][c]=new ArrayList<>();
        try(var in=FeatureReelModel.class.getResourceAsStream("/monster-slayer-feature-reels.csv")){
            if(in==null)throw new IllegalStateException("missing aggregate feature reel model");
            for(String line:new String(in.readAllBytes(),StandardCharsets.US_ASCII).split("\\R")){
                if(line.isBlank()||line.startsWith("#"))continue;int[]v=Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray();
                if(v.length!=6||v[0]<0||v[0]>1||v[1]<0||v[1]>4||v[5]<1)throw new IllegalStateException("invalid feature model row");
                for(int i=2;i<5;i++)if(v[i]<1||v[i]>10)throw new IllegalStateException("nonpaying model symbol");
                var rows=out[v[0]][v[1]];rows.add(new Reel(Arrays.copyOfRange(v,2,5),v[5]+(rows.isEmpty()?0:rows.get(rows.size()-1).cumulative)));
            }
            for(int k=0;k<2;k++)for(int c=0;c<5;c++){if(out[k][c].isEmpty())throw new IllegalStateException("empty feature model reel");out[k][c]=List.copyOf(out[k][c]);}
            return out;
        }catch(IOException e){throw new IllegalStateException("cannot load feature model",e);}
    }
    int[] board(boolean opening,SecureRandom r){int[]b=new int[15];for(int c=0;c<5;c++){var rows=MODEL[opening?1:0][c];int target=r.nextInt(rows.get(rows.size()-1).cumulative);int lo=0,hi=rows.size()-1;while(lo<hi){int mid=(lo+hi)>>>1;if(target<rows.get(mid).cumulative)hi=mid;else lo=mid+1;}System.arraycopy(rows.get(lo).cells,0,b,c*3,3);}return b;}
}
