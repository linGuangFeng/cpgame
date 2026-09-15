package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Offline validation only. Original expected values are read from fixed, excluded raw holdouts. */
public final class ModelValidationMain {
    public static void main(String[] args) throws Exception {
        Map<Integer,List<String[]>> original = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] f = line.split("\\|", -1);
            original.computeIfAbsent(Integer.parseInt(f[0]), k -> new ArrayList<>()).add(f);
        }
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 18, 25);
        int holdoutSteps = 0;
        for (var entry : original.entrySet()) {
            List<Step> facts = new ArrayList<>();
            for (String[] f : entry.getValue()) facts.add(Step.fact(
                Integer.parseInt(f[1]),new BigDecimal(f[2]),new BigDecimal(f[3]),Integer.parseInt(f[4]),
                List.of(f[11].split(",")),Integer.parseInt(f[5]),Integer.parseInt(f[6]),
                Integer.parseInt(f[7]),Integer.parseInt(f[8])));
            Step first = facts.getFirst();
            CompleteRound round = GameRuleCore.materialize(first.betAmount(),first.betSize(),first.betLevel(),facts);
            verifier.verify(round);
            for (int i=0;i<round.steps().size();i++) {
                Step step=round.steps().get(i);String[] raw=entry.getValue().get(i);
                check(step.winAmount().compareTo(new BigDecimal(raw[9]))==0, "raw win amount "+entry.getKey()+":"+i);
                check(step.roundWinAmount().compareTo(new BigDecimal(raw[10]))==0, "raw segment total "+entry.getKey()+":"+i);
                check(String.join(",",step.winMatches().stream().map(WinMatch::symbolKey).sorted().toList()).equals(raw[12]),"raw winner set");
                holdoutSteps++;
            }
        }
        System.out.println("ORIGINAL_HOLDOUT_PASS rounds="+original.size()+" steps="+holdoutSteps);
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(18,25);
        MemberCodec codec = new MemberCodec();
        Set<String> members = new HashSet<>();
        Map<String,Integer> modeCounts=new TreeMap<>(), entryCounts=new TreeMap<>(), symbolCounts=new TreeMap<>();
        int steps=0,maxSteps=0,duplicates=0;BigDecimal total=BigDecimal.ZERO;
        long started=System.nanoTime();
        for (int n=0;n<10000;n++) {
            RoundMode mode=n<4000?RoundMode.LOSS:n<7000?RoundMode.MARY:RoundMode.FREE;
            CompleteRound round=factory.generate(mode,random,new BigDecimal("0.05"),1);
            verifier.verifyCodecRoundTrip(round,codec);
            if(!members.add(new String(codec.encode(round), java.nio.charset.StandardCharsets.US_ASCII)))duplicates++;
            modeCounts.merge(round.mode().name(),1,Integer::sum);steps+=round.steps().size();
            maxSteps=Math.max(maxSteps,round.steps().size());total=total.add(round.payout());
            for(int i=0;i<round.steps().size();i++){
                Step step=round.steps().get(i);
                String e=i==0?(step.freeSpinNum()>0?"PAID_FREE":step.spinStatus()==0?"PAID_CASCADE":"PAID_LOSS"):
                    round.steps().get(i-1).spinStatus()==1?"FREE_INITIAL":step.freeSpinNum()>0?"FREE_REFILL":"BASE_REFILL";
                entryCounts.merge(e,1,Integer::sum);
                for(String symbol:step.symbols())symbolCounts.merge(e+"."+symbol,1,Integer::sum);
            }
            if((n+1)%1000==0)System.out.println("GENERATED_VALIDATED "+(n+1));
        }
        String report="{\n"+
            "\"status\":\"PASS_CORE_CONSTRAINTS_EMPIRICAL_GENERATOR\",\n"+
            "\"gameId\":16,\"modelSha256\":\""+EmpiricalColumnModel.SHA256+"\",\n"+
            "\"holdoutRounds\":"+original.size()+",\"holdoutSteps\":"+holdoutSteps+",\"holdoutMismatches\":0,\n"+
            "\"generatedRounds\":10000,\"generatedSteps\":"+steps+",\"generatedFailures\":0,\n"+
            "\"generatedDuplicateMembers\":"+duplicates+",\"maximumSteps\":"+maxSteps+",\n"+
            "\"generatedModeCounts\":"+mapJson(modeCounts)+",\n"+
            "\"generatedEntryDenominators\":"+mapJson(entryCounts)+",\n"+
            "\"generatedBoardSymbolCountsByEntry\":"+mapJson(symbolCounts)+",\n"+
            "\"elapsedMs\":"+(System.nanoTime()-started)/1000000+",\n"+
            "\"runtimeFixtures\":false,\"completeRoundReplay\":false,\"independentCellRng\":false,\n"+
            "\"notes\":[\"Fixed validation mix is not an origin mode-probability claim.\",\"Symbol counts use full visible boards per entry; refill block probabilities are recorded separately in distribution-model.json.\",\"Exclusive ordinary WIN is unobserved and is not fabricated.\",\"Browser, Redis and protocol acceptance remain separate requirements.\"]\n}\n";
        Files.writeString(Path.of(args[1]),report);
        System.out.println(report);
    }
    private static String mapJson(Map<String,Integer> m){
        List<String> entries=new ArrayList<>();m.forEach((k,v)->entries.add("\""+k+"\":"+v));
        return "{"+String.join(",",entries)+"}";
    }
    private static void check(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
}
