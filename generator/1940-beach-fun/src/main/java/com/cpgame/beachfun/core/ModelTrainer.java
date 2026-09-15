package com.cpgame.beachfun.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Builds statistical entry distributions from training Rounds; the final 100 Rounds remain untouched. */
public final class ModelTrainer {
    private static final Map<String,Map<String,Long>> groups=new TreeMap<>();
    private static final Map<String,Limit> limits=new TreeMap<>();
    private static final Map<String,Integer>caps=new TreeMap<>();
    private static final class Limit {
        int scatter,wild;int[]sr=new int[5],wr=new int[5];long sp,wp;
        void add(JsonNode p){
            int st=0,wt=0;
            for(int reel=0;reel<5;reel++){
                int s=0,w=0;
                for(int row=0;row<4;row++){int sym=p.path("props_value").path(reel).path(row).path("prop").asInt(),pos=reel*4+row;
                    if(sym==9){s++;sp|=1L<<pos;}if(sym==10){w++;wp|=1L<<pos;}
                }st+=s;wt+=w;sr[reel]=Math.max(sr[reel],s);wr[reel]=Math.max(wr[reel],w);
            }scatter=Math.max(scatter,st);wild=Math.max(wild,wt);
        }
        String row(String key){return "LIMIT\t"+key+"\t"+scatter+"\t"+wild+"\t"+join(sr)+"\t"+join(wr)+"\t"+Long.toHexString(sp)+"\t"+Long.toHexString(wp)+"\n";}
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("ModelTrainer <original-rounds.jsonl> <model.tsv> <distribution-report.json>");
        var corpus=EvidenceCorpus.read(Path.of(args[0]));int holdout=100,train=corpus.rounds().size()-holdout;
        if(train<100)throw new IllegalArgumentException("insufficient original complete Rounds");
        GameRuleCore core=new GameRuleCore();RoundVerifier verifier=new RoundVerifier();
        for(int ri=0;ri<train;ri++){
            var original=corpus.rounds().get(ri);verifier.verify(original.round());EvidenceCorpus.verifyIdentities(original,core);
            caps.merge("deliveriesPerRound",original.rawDeliveries().size(),Math::max);
            for(JsonNode d:original.rawDeliveries()){
                String mode=d.path("type").asInt()==2?"F":"P";JsonNode props=d.path("props");
                caps.merge(mode.equals("F")?"freeSteps":"paidSteps",props.size(),Math::max);
                for(int ci=0;ci<props.size();ci++){
                    JsonNode p=props.path(ci);limits.computeIfAbsent(mode+(ci==0?"I":"C"),k->new Limit()).add(p);
                    if(ci==0){
                        long sm=0,gm=0;int prefix=255;
                        for(int reel=0;reel<5;reel++){
                            int sc=0,gc=0,columnMask=0;StringBuilder vector=new StringBuilder();
                            for(int row=0;row<4;row++){
                                JsonNode cell=p.path("props_value").path(reel).path(row);int sym=cell.path("prop").asInt();
                                vector.append(Character.forDigit(sym,36));if(sym<9)columnMask|=1<<(sym-1);
                                if(sym==9){sc|=1<<row;sm|=1L<<(reel*4+row);}
                                if(cell.path("is_gold").asInt()==1){gc|=1<<row;gm|=1L<<(reel*4+row);}
                            }
                            add(mode+":M:"+reel+":"+sc+":"+prefix,vector.toString());prefix&=columnMask;
                        }
                        add(mode+":L",Long.toHexString(sm)+"."+Long.toHexString(gm));
                    }else{
                        for(int reel=0;reel<5;reel++){
                            Set<Integer>old=new HashSet<>();for(JsonNode cell:props.path(ci-1).path("props_value").path(reel))old.add(cell.path("id").asInt());
                            StringBuilder vector=new StringBuilder();
                            for(JsonNode cell:p.path("props_value").path(reel))if(!old.contains(cell.path("id").asInt())){
                                if(cell.path("is_gold").asInt()!=0||cell.path("prop").asInt()==10)throw new IllegalArgumentException("unexpected original refill Wild/gold");
                                vector.append(Character.forDigit(cell.path("prop").asInt(),36));
                            }
                            if(vector.length()>0)add(mode+":R:"+reel+":"+vector.length(),vector.toString());
                        }
                    }
                }
            }
        }
        StringBuilder tsv=new StringBuilder("BFMODEL2\nRULES\t"+GameRuleCore.RULES_HASH+"\nSOURCE\t"+corpus.sourceHash()+"\nTRAIN\t"+train+"\nHOLDOUT\t"+holdout+"\n");
        caps.forEach((k,v)->tsv.append("CAP\t").append(k).append('\t').append(v).append('\n'));
        limits.forEach((k,v)->tsv.append(v.row(k)));
        List<Map<String,Object>> distribution=new ArrayList<>();
        groups.forEach((key,counts)->{
            long denominator=counts.values().stream().mapToLong(Long::longValue).sum();
            List<Map<String,Object>> values=new ArrayList<>();
            counts.forEach((value,count)->{
                tsv.append("D\t").append(key).append('\t').append(value).append('\t').append(count).append('\n');
                values.add(Map.of("value",value,"count",count,"denominator",denominator,"percentage",100.0*count/denominator));
            });
            distribution.add(Map.of("entry",key,"samples",denominator,"values",values));
        });
        write(Path.of(args[1]),tsv.toString());
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("gameId",1940);report.put("rulesHash",GameRuleCore.RULES_HASH);report.put("source",args[0]);report.put("sourceSha256",corpus.sourceHash());
        report.put("trainingCompleteRounds",train);report.put("heldOutCompleteRounds",holdout);report.put("holdoutBoundary","last 100 original Rounds excluded from all weights and generation bounds");
        report.put("model","joint Scatter/gold layout; 4-cell reel vectors conditioned on Scatter rows and the prefix candidate-symbol mask; refill vectors conditioned on reel and removed-cell count");
        report.put("noStoredPayoutsOrRounds",true);report.put("caps",caps);report.put("distribution",distribution);
        report.put("retrigger","SAMPLE_INSUFFICIENT; no retrigger enabled by observed generation bounds");
        write(Path.of(args[2]),EvidenceCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        System.out.println("MODEL_TRAINED trainingRounds="+train+" heldOut="+holdout+" groups="+groups.size()+" sourceHash="+corpus.sourceHash());
    }
    private static void add(String key,String value){groups.computeIfAbsent(key,k->new TreeMap<>()).merge(value,1L,Long::sum);}
    private static String join(int[]v){StringJoiner j=new StringJoiner(",");for(int n:v)j.add(Integer.toString(n));return j.toString();}
    private static void write(Path p,String s)throws Exception{
        Files.createDirectories(p.toAbsolutePath().getParent());Path temp=p.resolveSibling(p.getFileName()+".tmp");
        Files.writeString(temp,s,StandardCharsets.UTF_8);Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);
    }
}
