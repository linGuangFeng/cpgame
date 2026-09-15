package com.cpgame.beachfun.core;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Analysis/validation input only. Production generation and API never call this class. */
public final class EvidenceCorpus {
    public static final ObjectMapper JSON=new ObjectMapper();
    public record Original(GameRuleCore.CompleteRound round,List<JsonNode>rawDeliveries){}
    public record Corpus(String sourceHash,List<Original>rounds){}
    public static Corpus read(Path path)throws IOException {
        byte[] bytes=Files.readAllBytes(path);List<Original> out=new ArrayList<>();
        for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank())out.add(parse(JSON.readTree(line)));
        try{return new Corpus(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),List.copyOf(out));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static Original parse(JsonNode root)throws IOException {
        List<GameRuleCore.Delivery> deliveries=new ArrayList<>();List<JsonNode> raw=new ArrayList<>();
        long units=0;boolean gold=false,cascade=false;
        for(JsonNode source:root.path("steps")){
            JsonNode d=JSON.readTree(source.path("responseText").asText()).path("data");raw.add(d);
            List<GameRuleCore.Cascade> steps=new ArrayList<>();long deliveryUnits=0;
            for(JsonNode p:d.path("props")){
                int[] b=new int[20];boolean[] g=new boolean[20],marked=new boolean[20];
                for(int reel=0;reel<5;reel++)for(int row=0;row<4;row++){
                    JsonNode cell=p.path("props_value").path(reel).path(row);int pos=reel*4+row;
                    b[pos]=cell.path("prop").asInt();g[pos]=cell.path("is_gold").asInt()==1;marked[pos]=cell.path("is_win").asInt()==1;gold|=g[pos];
                }
                List<GameRuleCore.Win> wins=new ArrayList<>();long stepUnits=0;boolean[] covered=new boolean[20];
                for(JsonNode w:p.path("win_array")){
                    int symbol=w.path("prop").asInt(),reels=w.path("reel").asInt()+1;List<Integer> positions=new ArrayList<>();
                    for(int pos=0;pos<reels*4;pos++)if(b[pos]==symbol||b[pos]==10){positions.add(pos);covered[pos]=true;}
                    int ways=w.path("way").asInt(),odd=w.path("odd").asInt(),factor=w.path("multiple").asInt();
                    long winUnits=(long)ways*odd*factor;stepUnits+=winUnits;
                    amount(winUnits,d,w.path("win_amout"),"win_array");
                    wins.add(new GameRuleCore.Win(symbol,reels,ways,odd,factor,positions));
                }
                if(!Arrays.equals(marked,covered))throw new IllegalArgumentException("original win flag mismatch "+root.path("roundId"));
                wins.sort(Comparator.comparingInt(GameRuleCore.Win::symbol));
                amount(stepUnits,d,p.path("total_amout"),"cascade");
                deliveryUnits+=stepUnits;steps.add(new GameRuleCore.Cascade(b,g,wins));
            }
            amount(deliveryUnits,d,d.path("total_win"),"delivery");units+=deliveryUnits;cascade|=steps.size()>1;
            deliveries.add(new GameRuleCore.Delivery(d.path("type").asInt()==2,d.path("new_free").path("new_times").asInt(),d.path("frees").path("surplus_times").asInt(),steps));
        }
        var round=new GameRuleCore.CompleteRound(root.path("roundId").asText(),units>0,deliveries.size()>1,cascade,gold,units,deliveries);
        return new Original(round,List.copyOf(raw));
    }
    private static void amount(long units,JsonNode d,JsonNode expected,String label){
        BigDecimal actual=d.path("bet").decimalValue().multiply(d.path("level").decimalValue()).multiply(BigDecimal.valueOf(units));
        if(actual.subtract(expected.decimalValue()).abs().compareTo(new BigDecimal("0.000001"))>0)
            throw new IllegalArgumentException("raw "+label+" amount mismatch");
    }
    public static void verifyIdentities(Original original,GameRuleCore core){
        for(int di=0;di<original.round.deliveries().size();di++){
            List<int[]> expected=core.cellIds(original.round.deliveries().get(di));
            JsonNode props=original.rawDeliveries.get(di).path("props");
            for(int ci=0;ci<expected.size();ci++)for(int p=0;p<20;p++)
                if(expected.get(ci)[p]!=props.path(ci).path("props_value").path(p/4).path(p%4).path("id").asInt())
                    throw new IllegalArgumentException("original symbol identity mismatch "+original.round.factId()+" delivery="+di+" step="+ci+" position="+p);
        }
    }
}
