package com.cpgame.g2110.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Original holdout and History oracle plus new generation checks; no network/Redis actions. */
public final class DealModelEvidenceCheck {
    private static String read(Path path) throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        try{return io.submit(()->Files.readString(path)).get(10,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){throw new IllegalStateException("IO_TIMEOUT: bounded read 10s: "+path,timeout);}
        finally{io.shutdownNow();}
    }
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static GameRuleCore.Step step(JsonNode d,String resultKey,ObjectMapper json) throws Exception {
        int[] board=json.treeToValue(d.path(resultKey).path("prop"),int[].class);
        List<Integer> positions=new ArrayList<>();d.path("spe_pos").forEach(p->positions.add(p.asInt()));
        return new GameRuleCore.Step(board,positions);
    }
    private static long expectedUnits(JsonNode result){long units=0;for(JsonNode win:result.path("win_arr"))units+=win.path("odd").asLong();return units;}

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("expected workspace root and output report");
        Path root=Path.of(args[0]),output=Path.of(args[1]);
        ObjectMapper json=new ObjectMapper();GameRuleCore rules=new GameRuleCore();ResultUtil util=new ResultUtil(rules);
        JsonNode fit=json.readTree(read(root.resolve("reports/2110-Bee-Workshop/deal-model-fit.json")));
        check(fit.path("modelSha256").asText().equals(EmpiricalDealModel.SHA256),"fit/runtime model mismatch");
        int heldOut=0;Map<String,Integer>holdoutKinds=new TreeMap<>();
        for(JsonNode sample:fit.path("holdout")){
            String id=sample.path("roundId").asText();check(id.matches("round-\\d+"),"bad source id");
            JsonNode d=json.readTree(read(root.resolve("fixtures/2110-Bee-Workshop/spin/"+id+"/step-001.response.json"))).path("data");
            var kind=GameRuleCore.RoundKind.valueOf(sample.path("classification").asText());
            var round=new GameRuleCore.CompleteRound(kind,List.of(step(d,"props",json)));
            rules.validate(round);
            check(util.totalUnits(round)==expectedUnits(d.path("props")),"holdout payout "+id);
            heldOut++;holdoutKinds.merge(kind.name(),1,Integer::sum);
        }
        check(heldOut==100,"expected 100 held-out complete rounds");
        JsonNode history=json.readTree(read(root.resolve("fixtures/2110-Bee-Workshop/history-view/response.json"))).path("data").path("list");
        int completeFree=0;
        for(JsonNode row:history){
            if(row.path("results").size()<=1)continue;
            List<GameRuleCore.Step>steps=new ArrayList<>();int remaining=row.path("results").get(0).path("frees").path("st").asInt();long expected=0;
            for(JsonNode d:row.path("results")){
                check(d.path("frees").path("st").asInt()==remaining--,"History counter sequence");
                var s=step(d,"result",json);steps.add(s);expected+=expectedUnits(d.path("result"));
            }
            check(remaining==-1,"History lacks terminal step");
            var round=new GameRuleCore.CompleteRound(GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS,steps);
            rules.validate(round);check(util.totalUnits(round)==expected,"History complete payout");completeFree++;
        }
        check(completeFree==1,"unexpected History complete free coverage");
        check(rules.awardedFreeSpins(3)==8&&rules.awardedFreeSpins(4)==10&&rules.awardedFreeSpins(5)==15,"original help Game2110_15");
        System.out.println("Original holdout: 100 PASS; complete History free chain: 1 PASS; 3/4/5 Scatter awards: PASS");

        RoundFactory factory=new RoundFactory(rules);
        MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        Map<String,Object>distributions=new LinkedHashMap<>();int generated=0;long started=System.nanoTime();
        for(var kind:GameRuleCore.RoundKind.values()){
            long units=0,before=factory.attemptedCandidates();Set<String>unique=new HashSet<>();Map<Integer,Integer>lengths=new TreeMap<>();
            for(int i=0;i<2500;i++){
                var round=factory.generate(kind);rules.validate(round);
                long expected=round.steps().stream().mapToLong(s->rules.payoutUnits(s.board())).sum();
                check(expected==util.totalUnits(round),"independent ResultUtil disagreement");
                check(util.integerMultiplier(round)==expected,"integer pool multiplier must preserve exact payout units");
                String member=codec.encode(round);
                check(member.chars().allMatch(c->c>=32&&c<=126),"non-ASCII member");
                var decoded=codec.decode(member);
                check(codec.encode(decoded).equals(member)&&util.totalUnits(decoded)==expected,"member cannot restore complete round");
                unique.add(member);units+=expected;lengths.merge(round.steps().size(),1,Integer::sum);generated++;
                if((i+1)%250==0)System.out.println(kind+" generated/verified: "+(i+1));
            }
            distributions.put(kind.name(),Map.of("count",2500,"uniqueMembers",unique.size(),"averagePayoutUnits",units/2500.0,
                "completeRoundStepCounts",lengths,"candidateAttempts",factory.attemptedCandidates()-before));
            if(kind==GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS){
                Map<Integer,Integer> counts=EmpiricalDealModel.shared().triggerCounts();
                double probability=counts.get(4)/(double)counts.values().stream().mapToInt(Integer::intValue).sum();
                double expected=2500*probability,sigma=Math.sqrt(2500*probability*(1-probability));
                check(Math.abs(lengths.getOrDefault(11,0)-expected)<=6*sigma,"four-Scatter frequency differs from fitted original count");
                check(lengths.getOrDefault(9,0).equals(factory.selectedFreeScatterCounts().get(3))&&
                      lengths.getOrDefault(11,0).equals(factory.selectedFreeScatterCounts().get(4)),"rejection changed a selected free entry");
            }
        }
        Map<String,Object>report=new LinkedHashMap<>();
        report.put("schemaVersion",2);report.put("gameId",2110);report.put("observedAt",Instant.now().toString());
        report.put("rulesHash",GameRuleCore.RULES_HASH);report.put("modelSha256",EmpiricalDealModel.SHA256);
        report.put("verdict","CORE_CHECKS_PASS_WITH_SAMPLE_INSUFFICIENCY");
        report.put("readyForAcceptance",false);report.put("originalHoldoutCompleteRounds",heldOut);report.put("holdoutClassification",holdoutKinds);
        report.put("historyCompleteFreeChains",completeFree);report.put("generatedCompleteRounds",generated);report.put("generatedDistribution",distributions);
        report.put("elapsedGenerationSeconds",(System.nanoTime()-started)/1e9);report.put("generationSampling","2500 per kind for invariant stress testing; not the production kind mixture");
        report.put("integerMultiplierBasis","bet-size-times-level; exact integer payout units; divide by 20 for total-stake odds");
        report.put("originalTriggerCounts",EmpiricalDealModel.shared().triggerCounts());
        report.put("selectedFreeScatterCounts",factory.selectedFreeScatterCounts());
        report.put("triggerFrequencyCheck","Selected versus delivered entry counts match exactly; frequency checked against model source counts with six-sigma sampling tolerance");
        report.put("sampleInsufficient",List.of("Ordinary wins: 101/200","Complete free chains: 1/30","Five-Scatter trigger and complete 15-free-spin round: no observed sample","Initial free emission: one observed sample"));
        report.put("limitations",fit.path("limitations"));report.put("sourceModelFit","reports/2110-Bee-Workshop/deal-model-fit.json");
        String rendered=json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n";
        ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        try{
            io.submit(()->{
                Path backup=output.resolveSibling(output.getFileName()+".before-recovery");
                if(Files.exists(output)&&!Files.exists(backup))Files.copy(output,backup);
                Files.writeString(output,rendered);
                if(!Files.readString(output).equals(rendered))throw new IllegalStateException("report readback mismatch");
                return true;
            }).get(10,TimeUnit.SECONDS);
        }catch(TimeoutException timeout){throw new IllegalStateException("IO_TIMEOUT: report write/readback 10s: "+output,timeout);}
        finally{io.shutdownNow();}
        System.out.println(rendered);
    }
}
