package com.cpgame.fiesta;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRulesRegressionTest {
    final GameRuleCore rules=new GameRuleCore();
    @Test void locked520ProviderRoundsAndWinningLinesMatch() throws Exception {
        Path capture=Path.of("../../captures/2410-Electro-Fiesta");ObjectMapper json=new ObjectMapper();
        Map<String,List<String>> files=new HashMap<>();int tested=0,transitions=0;
        for(String line:Files.readAllLines(capture.resolve("round-index.jsonl")).subList(0,520)){
            JsonNode index=json.readTree(line);List<RoundState> states=new ArrayList<>();JsonNode first=null,last=null;
            for(JsonNode ref:index.get("evidence")){
                String name=ref.get("file").asText();if(!files.containsKey(name))files.put(name,Files.readAllLines(capture.resolve(name)));
                JsonNode record=json.readTree(files.get(name).get(ref.get("line").asInt()-1));
                JsonNode d=json.readTree(record.at("/response/bodyText").asText()).get("data");
                if(first==null)first=d;last=d;JsonNode f=d.get("f");
                RoundState.Mode mode=f.isArray()?RoundState.Mode.NORMAL:f.get("t").asInt()==1?RoundState.Mode.RESPIN_UNTIL_WIN:RoundState.Mode.MULTIPLIER_STICKY;
                int[] b=ints(d.at("/res/ps")),mul=mode==RoundState.Mode.MULTIPLIER_STICKY?ints(f.get("pcp")):new int[9];
                int[] added=mode==RoundState.Mode.MULTIPLIER_STICKY?ints(f.get("pcn")):new int[0];
                states.add(new RoundState(mode,b,mul,added,f.isArray()?0:f.get("cf").asInt(),f.isArray()?-1:f.get("pr").asInt()-1,f.isArray()?0:f.get("ps").asInt()));
                List<GameRuleCore.Win> expected=new ArrayList<>();for(JsonNode w:d.at("/res/wa"))expected.add(new GameRuleCore.Win(w.get("l").asInt(),w.get("s").asInt(),w.get("c").asInt(),w.get("o").asInt()));
                assertEquals(expected,rules.evaluateBoard(b),name+":"+ref.get("line"));
            }
            GameRound round=new GameRound(states);String context="provider round "+index.get("roundNumber");
            ResultUtil.Analysis analysis=assertDoesNotThrow(()->new ResultUtil(rules).analyze(round),context);
            assertEquals(last.get("tw").asDouble(),first.get("b").asDouble()*first.get("l").asDouble()*analysis.payoutUnits(),.00001,context);
            assertEquals(analysis,new ResultUtil(rules).analyze(new RoundCodec().decode(new RoundCodec().encode(round))),context);
            if(states.get(0).mode()==RoundState.Mode.RESPIN_UNTIL_WIN&&states.get(states.size()-1).mode()==RoundState.Mode.MULTIPLIER_STICKY)transitions++;
            tested++;
        }
        assertTrue(tested>=520);assertTrue(transitions>=13);
        System.out.println("PROVIDER_ORACLE rounds="+tested+" respinToMultiplier="+transitions);
    }
    @Test void screenshotRowsAndDiagonalsUseProviderLineNumbers(){
        // Screenshot: top row drums, middle/bottom DJ. No diagonal win.
        int[] board={3,50,50,3,50,50,3,50,50};
        assertEquals(List.of(new GameRuleCore.Win(1,50,3,50),new GameRuleCore.Win(2,3,3,3),new GameRuleCore.Win(3,50,3,50)),rules.evaluateBoard(board));
        assertEquals(103,rules.payoutUnits(board));
        assertEquals(List.of(new GameRuleCore.Win(4,50,3,50)),rules.evaluateBoard(new int[]{50,2,3,5,50,10,2,3,50}));
        assertEquals(List.of(new GameRuleCore.Win(5,50,3,50)),rules.evaluateBoard(new int[]{2,3,50,5,50,10,50,2,3}));
    }
    @Test void twoFullReelsWithPaylineWinRemainNormal(){
        int[] board={50,50,50,50,50,20,50,50,50};
        assertEquals(-1,rules.respinColumn(board));assertFalse(CandidateFactory.structuralFeature(board));
        assertEquals(200,rules.payoutUnits(board));
    }
    @Test void generatedRoundsAndZeroCandidatesAreValid() throws Exception {
        Properties p=new Properties();try(var reader=Files.newBufferedReader(Path.of("dist/generator.properties"))){p.load(reader);}
        CandidateFactory factory=new CandidateFactory(new SecureRandom(),rules,p);RoundCodec codec=new RoundCodec();ResultUtil util=new ResultUtil(rules);
        int transitions=0,partial=0,max=0;Set<Integer> ordinaryBuckets=new HashSet<>();
        for(int i=0;i<10000;i++)for(GameRound round:List.of(factory.ordinary(true),factory.respinRound(11),factory.multiplierRound(11))){
            var a=util.analyze(round);assertEquals(a,util.analyze(codec.decode(codec.encode(round))));max=Math.max(max,a.payoutUnits());
            if(round.states().get(0).mode()==RoundState.Mode.NORMAL)ordinaryBuckets.add(a.payoutUnits());
            if(round.states().get(0).mode()==RoundState.Mode.RESPIN_UNTIL_WIN){if(a.outcome()==RoundOutcome.MULTIPLIER_STICKY)transitions++;else partial++;}
        }
        for(int i=0;i<100000;i++)assertEquals(RoundOutcome.ORDINARY_LOSS,util.analyze(factory.lossCandidate()).outcome());
        assertTrue(transitions>0);assertTrue(partial>0);assertTrue(ordinaryBuckets.contains(200));assertTrue(max>2500);
        System.out.println("GENERATED_ORACLE rounds=30000 zeroCandidates=100000 normalBuckets="+ordinaryBuckets.size()+" transitions="+transitions+" maxUnits="+max);
    }
    private int[] ints(JsonNode a){int[] out=new int[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).asInt();return out;}
}
