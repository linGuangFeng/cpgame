package com.cpgame.beachfun.core;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public final class GenerationValidationMain {
    public static void main(String[]args)throws Exception{
        if(args.length<2)throw new IllegalArgumentException("GenerationValidationMain <original-rounds.jsonl> <report.json> [count]");
        int count=args.length>2?Integer.parseInt(args[2]):10000;
        if(count<10000)throw new IllegalArgumentException("at least 10000 new complete Rounds required");
        var corpus=EvidenceCorpus.read(Path.of(args[0]));var core=new GameRuleCore();var verifier=new RoundVerifier();var codec=new MinimalRoundFactCodec();
        int holdout=100;for(int i=corpus.rounds().size()-holdout;i<corpus.rounds().size();i++){
            var original=corpus.rounds().get(i);verifier.verify(original.round());EvidenceCorpus.verifyIdentities(original,core);
        }
        var factory=new RoundFactory(core,new SecureRandom());var model=new DistributionModel();
        Map<String,Integer>counts=new TreeMap<>();Set<String>facts=new HashSet<>();long deliveries=0,steps=0;int maxMember=0;
        long started=System.nanoTime();
        for(int i=0;i<count;i++){
            var r=factory.generateNatural();verifier.verify(r);
            String encoded=codec.encode(r);var decoded=codec.decode(encoded);
            if(!encoded.equals(codec.encode(decoded)))throw new IllegalStateException("codec round trip");
            facts.add(encoded.substring(encoded.indexOf('|',4)));maxMember=Math.max(maxMember,encoded.length());
            String category=r.freeFeature()?"free":r.win()?"ordinaryWin":"ordinaryLoss";counts.merge(category,1,Integer::sum);
            if(r.goldFeature())counts.merge("gold",1,Integer::sum);if(r.cascadeFeature())counts.merge("cascade",1,Integer::sum);
            for(var d:r.deliveries()){
                deliveries++;if(d.cascades().size()>model.maxSteps(d.free()))throw new IllegalStateException("step cap");
                for(int ci=0;ci<d.cascades().size();ci++){model.check(d.cascades().get(ci),d.free(),ci);steps++;}
            }
            if(r.deliveries().size()>model.maxDeliveries())throw new IllegalStateException("delivery cap");
            if((i+1)%1000==0)System.out.println("VERIFIED newCompleteRounds="+(i+1));
        }
        Map<String,Integer>poolModes=new TreeMap<>();
        for(var requested:RoundFactory.Requested.values())for(int i=0;i<30;i++){
            var r=factory.generateForPool(requested);verifier.verify(r);codec.decode(codec.encode(r));
            ResultUtil.integerMultiplier(r);poolModes.merge(requested.name(),1,Integer::sum);
        }
        var sample=factory.generate(RoundFactory.Requested.FREE);
        var badTotal=new GameRuleCore.CompleteRound(sample.factId(),sample.win(),sample.freeFeature(),sample.cascadeFeature(),sample.goldFeature(),sample.totalUnits()+1,sample.deliveries());
        reject(()->ResultUtil.verify(badTotal));
        List<GameRuleCore.Delivery>badDs=new ArrayList<>(sample.deliveries());var d=badDs.get(0);
        badDs.set(0,new GameRuleCore.Delivery(d.free(),d.awardedFreeSpins(),d.remainingFreeSpins()+1,d.cascades()));
        var badFree=new GameRuleCore.CompleteRound(sample.factId(),sample.win(),sample.freeFeature(),sample.cascadeFeature(),sample.goldFeature(),sample.totalUnits(),badDs);
        reject(()->ResultUtil.verify(badFree));reject(()->codec.decode("BF1|legacy"));reject(()->codec.decode(codec.encode(sample).replaceFirst("BF2","BAD")));
        Map<String,Object>report=new LinkedHashMap<>();
        Map<String,Integer> originalCounts=new TreeMap<>();
        for(int i=0;i<corpus.rounds().size()-holdout;i++){var r=corpus.rounds().get(i).round();originalCounts.merge(r.freeFeature()?"free":r.win()?"ordinaryWin":"ordinaryLoss",1,Integer::sum);}
        Map<String,Object>comparison=new TreeMap<>();boolean distributionPass=true;
        for(String k:List.of("ordinaryWin","free")){
            int numerator=originalCounts.getOrDefault(k,0),denominator=corpus.rounds().size()-holdout;
            double expected=(double)numerator/denominator,actual=(double)counts.getOrDefault(k,0)/count,tolerance=k.equals("free")?0.015:0.035;
            boolean ok=Math.abs(expected-actual)<=tolerance;distributionPass&=ok;
            comparison.put(k,Map.of("sourceCount",numerator,"sourceDenominator",denominator,"sourcePercentage",100*expected,"generatedCount",counts.getOrDefault(k,0),"generatedDenominator",count,"generatedPercentage",100*actual,"absolutePercentagePointTolerance",100*tolerance,"pass",ok));
        }
        report.put("gameId",1940);report.put("status",distributionPass?"PASS":"DISTRIBUTION_DRIFT");report.put("structuralChecks","PASS");report.put("distributionComparison",comparison);report.put("rulesHash",GameRuleCore.RULES_HASH);
        report.put("sourceSha256",corpus.sourceHash());report.put("heldOutOriginalCompleteRounds",holdout);report.put("newGeneratedCompleteRounds",count);
        report.put("counts",counts);report.put("deliveries",deliveries);report.put("steps",steps);report.put("distinctStructuralRounds",facts.size());report.put("maxAsciiMemberBytes",maxMember);
        report.put("integerMultiplierPoolChecks",poolModes);report.put("independentNegativeChecks",4);report.put("rejectedCandidates",factory.rejectedCandidates());
        report.put("elapsedSeconds",(System.nanoTime()-started)/1e9);report.put("originalOracle","raw win_array, total_amout, total_win and symbol IDs; not generated expectations");
        report.put("independentVerifier","ResultUtil separately computes Ways, payouts, survivor order/gold and free lifecycle");
        report.put("retriggerCoverage","SAMPLE_INSUFFICIENT; zero observed retrigger Rounds; production model does not invent them");
        report.put("acceptanceReady",false);report.put("remainingAcceptance","Controller/Redis integration and original-page full-animation playtests remain separate gates");
        Path target=Path.of(args[1]);Files.writeString(target,EvidenceCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        System.out.println((distributionPass?"PASS":"DISTRIBUTION_DRIFT")+" heldOut="+holdout+" newCompleteRounds="+count+" counts="+counts+" comparison="+comparison);
        if(!distributionPass)System.exit(2);
    }
    private static void reject(Runnable operation){try{operation.run();}catch(IllegalArgumentException expected){return;}throw new IllegalStateException("invalid facts accepted");}
}
