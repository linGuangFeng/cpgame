package com.cpgame.sharpshooter.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/** Aggregated column and refill-block frequencies, excluding the reserved complete rounds. */
final class DealingModel {
    private final JsonNode data;
    private final Map<String,Table> tables=new java.util.concurrent.ConcurrentHashMap<>();
    DealingModel(){
        try(InputStream in=DealingModel.class.getResourceAsStream("/dealing-model.json")){
            if(in==null)throw new IllegalStateException("missing fitted dealing model");
            data=new ObjectMapper().readTree(in);
            if(data.path("schemaVersion").asInt()!=2||data.path("gameId").asInt()!=1090)
                throw new IllegalStateException("invalid dealing model");
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    record Deal(int[] symbols,boolean[] gold){}
    Deal initial(String profile,SecureRandom random){
        JsonNode p=data.path("initial").path(profile);
        String[] shape=pick(profile+":shape",p.path("shapes"),random).split("\\.");
        int[] symbols=new int[20];boolean[] gold=new boolean[20];
        for(int col=0;col<5;col++){
            JsonNode candidates=p.path("columns").get(col).path(shape[col]);
            String[] tuple=pick(profile+":"+col+":"+shape[col],candidates,random).split("\\.");
            for(int row=0;row<4;row++){
                int value=Integer.parseInt(tuple[row]),pos=col*4+row;
                gold[pos]=value>10;symbols[pos]=gold[pos]?value-10:value;
            }
        }
        return new Deal(symbols,gold);
    }
    int[] refill(boolean free,int col,int length,SecureRandom random){
        if(length==0)return new int[0];
        String key=(free?"free":"paid")+":"+col+":"+length;
        JsonNode node=data.path("refill").path(key);
        if(node.isMissingNode())throw new RejectedDeal("unobserved refill width "+key);
        String[] block=pick("refill:"+key,node,random).split("\\.");
        return Arrays.stream(block).mapToInt(Integer::parseInt).toArray();
    }
    boolean freeWin(SecureRandom random){
        JsonNode profiles=data.path("initial");
        int wins=profiles.path("free_win").path("samples").asInt();
        int losses=profiles.path("free_loss").path("samples").asInt();
        if(wins+losses==0)throw new IllegalStateException("no free entry evidence");
        return random.nextInt(wins+losses)<wins;
    }
    record Target(int pages,int minimumUnits,int maximumUnits){}
    Target target(String profile,SecureRandom random){
        String[] key=pick("target:"+profile,data.path("spinTargets").path(profile),random).split(":");
        int[] limits={0,10,25,50,100,250,500,1000,2500,10000,Integer.MAX_VALUE};
        int bin=Integer.parseInt(key[1]);
        return new Target(Integer.parseInt(key[0]),bin==0?0:limits[bin-1]+1,limits[bin]);
    }
    int maximumPages(boolean free){return bounds(free).path("cascadePages").asInt();}
    void validatePage(int[] board,boolean[] gold,boolean free,int page){
        JsonNode cap=bounds(free);int scatters=0,wilds=0,golds=0;
        for(int col=0;col<5;col++){
            int sc=0,gc=0;
            for(int row=0;row<4;row++){
                int p=col*4+row;
                if(board[p]==9)sc++;
                if(board[p]==10)wilds++;
                if(gold[p])gc++;
                if(gold[p]&&(board[p]>8||col==0||col==4))
                    throw new RejectedDeal("illegal gold location");
            }
            if(sc>cap.path("scatterColumn").get(col).asInt()||gc>cap.path("goldColumn").get(col).asInt())
                throw new RejectedDeal("column evidence cap");
            scatters+=sc;golds+=gc;
        }
        if(scatters>cap.path("scatter").asInt()||wilds>(page==0?0:cap.path("wild").asInt())||
           golds>cap.path(page==0?"goldInitial":"goldRefill").asInt())
            throw new RejectedDeal("page evidence cap");
    }
    private JsonNode bounds(boolean free){return data.path("bounds").path(free?"free":"paid");}
    private String pick(String key,JsonNode node,SecureRandom random){
        return tables.computeIfAbsent(key,k->new Table(node)).pick(random);
    }
    private static final class Table {
        final String[] values;final int[] cumulative;final int total;
        Table(JsonNode node){
            if(!node.isObject()||node.size()==0)throw new IllegalStateException("empty empirical table");
            values=new String[node.size()];cumulative=new int[node.size()];
            int i=0,sum=0;
            for(var it=node.fields();it.hasNext();){
                var entry=it.next();int count=entry.getValue().asInt();
                if(count<=0)throw new IllegalStateException("nonpositive empirical count");
                values[i]=entry.getKey();sum=Math.addExact(sum,count);cumulative[i++]=sum;
            }
            total=sum;
        }
        String pick(SecureRandom random){
            int target=random.nextInt(total)+1,pos=Arrays.binarySearch(cumulative,target);
            return values[pos>=0?pos:-pos-1];
        }
    }
    static final class RejectedDeal extends RuntimeException {RejectedDeal(String reason){super(reason);}}
}
