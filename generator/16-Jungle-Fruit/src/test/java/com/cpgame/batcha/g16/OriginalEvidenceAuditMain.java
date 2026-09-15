package com.cpgame.batcha.g16;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
/** Offline original-evidence audit. Never called by Loader or API. */
public final class OriginalEvidenceAuditMain {
 public static void main(String[] args)throws Exception{
  Map<Integer,List<String[]>> groups=new TreeMap<>();
  for(String line:Files.readAllLines(Path.of(args[0]))){
   String[] f=line.split("\\|",-1);groups.computeIfAbsent(Integer.parseInt(f[0]),k->new ArrayList<>()).add(f);
  }
  IndependentVerifier verifier=new IndependentVerifier(new BigDecimal("20000"),100,100);
  Map<String,Integer> counts=new TreeMap<>();List<String> rejected=new ArrayList<>();Set<String> seen=new HashSet<>();int duplicates=0;
  for(var g:groups.entrySet())try{
   List<Step> facts=new ArrayList<>();
   for(String[] f:g.getValue())facts.add(Step.fact(Integer.parseInt(f[1]),new BigDecimal(f[2]),new BigDecimal(f[3]),Integer.parseInt(f[4]),List.of(f[11].split(",")),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8])));
   Step start=facts.getFirst();
   CompleteRound r=GameRuleCore.materialize(start.betAmount(),start.betSize(),start.betLevel(),facts);
   verifier.verify(r);
   for(int i=0;i<r.steps().size();i++){
    Step step=r.steps().get(i);String[] raw=g.getValue().get(i);
    if(step.winAmount().compareTo(new BigDecimal(raw[9]))!=0||step.roundWinAmount().compareTo(new BigDecimal(raw[10]))!=0)throw new IllegalArgumentException("raw settlement mismatch at Step "+i);
    if(!String.join(",",step.winMatches().stream().map(WinMatch::symbolKey).sorted().toList()).equals(raw[12]))throw new IllegalArgumentException("raw winner set mismatch");
   }
   String fingerprint=String.join(";",g.getValue().stream().map(f->f[5]+":"+f[6]+":"+f[7]+":"+f[8]+":"+f[11]).toList());
   if(!seen.add(fingerprint)){duplicates++;continue;}
   String corpus=g.getKey()<1000000?"earlier":"newer";
   counts.merge(corpus+"."+r.mode(),1,Integer::sum);counts.merge("combined."+r.mode(),1,Integer::sum);
  }catch(Exception e){rejected.add(g.getKey()+":"+e.getMessage());}
  String json="{\"status\":\"ORIGINAL_ROUND_AUDIT_COMPLETE\",\"rulesHash\":\""+GameRuleCore.RULES_HASH+"\",\"candidates\":"+groups.size()+",\"deduplicatedIdenticalSequences\":"+duplicates+",\"counts\":"+map(counts)+",\"rejectedCandidateCount\":"+rejected.size()+",\"rejectedCandidates\":["+String.join(",",rejected.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList())+"],\"note\":\"MARY is the legacy small_game_type=1 label for non-free paying tumble Rounds; ordinary non-free WIN counting includes this group. Exclusive single-Step paying terminal is not observed or generated.\"}\n";
  Files.writeString(Path.of(args[1]),json);System.out.println(json);
 }
 static String map(Map<String,Integer> m){return "{"+String.join(",",m.entrySet().stream().map(e->"\""+e.getKey()+"\":"+e.getValue()).toList())+"}";}
}
