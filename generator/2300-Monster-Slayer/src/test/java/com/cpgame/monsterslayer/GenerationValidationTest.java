package com.cpgame.monsterslayer;

import com.cpgame.monsterslayer.core.*;
import com.cpgame.monsterslayer.generator.CompleteRoundFactory;
import com.cpgame.monsterslayer.generator.GeneratorConfig;
import com.cpgame.monsterslayer.generator.MonsterSlayerBoardGenerator;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.*;

final class GenerationValidationTest {
    @Test void reservedCapturedNormalRoundsMatchOriginalAmounts() throws Exception {
        Path evidence=Path.of(System.getProperty("monsterSlayer.evidence",
                "../../reports/2300-Monster-Slayer/canonical-round-evidence-20260908.json"));
        JsonNode root=new ObjectMapper().readTree(evidence.toFile());
        Set<String> reserved=new HashSet<>();
        root.path("holdoutRoundIds").forEach(n->reserved.add(n.asText()));
        int checked=0;
        for(JsonNode r:root.path("rounds")){
            if(!reserved.contains(r.path("id").asText())||r.path("kind").asText().equals("special"))continue;
            JsonNode s=r.path("steps").get(0);int[] board=new int[15];
            for(int i=0;i<15;i++)board[i]=s.path("board").get(i).asInt();
            int expected=new BigDecimal(s.path("tw").asText()).multiply(BigDecimal.valueOf(100))
                    .divide(new BigDecimal(s.path("bet").asText())).intValueExact();
            assertEquals(expected,ResultUtil.evaluate(new GameRuleCore.CompleteRound(false,
                    List.of(new GameRuleCore.Step(board,0,0)))).multiplierCenti(),s.path("source").asText());
            checked++;
        }
        assertEquals(94,checked,"remaining 6 reserved complete Rounds are special and still blocked");
    }
    @Test void generatedNormalRoundsRespectObservedSymbolConstraintsAndCodec(){
        CompleteRoundFactory factory=new CompleteRoundFactory();MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        SecureRandom random=new SecureRandom();
        for(boolean winning:new boolean[]{false,true})for(int i=0;i<1000;i++){
            GameRuleCore.CompleteRound round=winning?factory.generateWin(random):factory.generateLoss(random);
            assertEquals(winning,ResultUtil.evaluate(round).multiplierCenti()>0);
            int scatters=0;int[] board=round.steps().get(0).board();
            for(int cell=0;cell<15;cell++){
                if(board[cell]==100){scatters++;assertTrue(cell>=3&&cell<12);}
                else assertTrue(board[cell]>=1&&board[cell]<=10);
            }
            assertTrue(scatters<=1);
            byte[] member=codec.encode(round);
            assertTrue(startsWithAscii(member, "MS3N;"));
            assertEquals(20,member.length);
            assertFalse(containsAscii(member, ","));
            assertArrayEquals(member,codec.encode(codec.decode(member)));
        }
    }
    @Test void generatorPropertiesExpose1809StyleSymbolWeights() throws Exception {
        GeneratorConfig c=GeneratorConfig.load(Path.of("generator.properties"));
        assertEquals(2701,c.normalWeights[0]);
        assertEquals(405,c.normalWeights[10]);
        assertEquals(10,c.specialTriggerWeightMultiplier);
        assertEquals(10,c.maxConsecutiveWins);
        assertEquals(32,c.maxMarySpins);
        assertEquals(MonsterSlayerBoardGenerator.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER,c.specialTriggerWeightMultiplier);
    }
    @Test void perCellWeightsHonorConfigAndDoNotCopyWholeReels(){
        int[] onlyLow={0,0,0,0,0,0,0,0,0,1,0};
        var boards=new MonsterSlayerBoardGenerator(new SecureRandom(),onlyLow);
        for(int i=0;i<30;i++){
            int[] board=boards.generateOrdinary();
            for(int symbol:board) assertEquals(10,symbol);
        }
        CompleteRoundFactory factory=new CompleteRoundFactory();
        SecureRandom random=new SecureRandom();
        int mixedReels=0;
        for(int i=0;i<400;i++){
            int[] board=factory.generateLoss(random).steps().get(0).board();
            for(int col=0;col<5;col++){
                if(board[col*3]!=board[col*3+1]||board[col*3]!=board[col*3+2]) mixedReels++;
            }
        }
        assertTrue(mixedReels>200,"independent cells must mix symbols inside a reel");
    }
    @Test void normalWireConstraintsAlsoApplyWhenDecodingExternalMembers(){
        int[] base={2,6,6,6,10,10,4,3,7,3,7,8,9,7,3};
        int[] placeholder=base.clone();placeholder[4]=0;
        int[] edge=base.clone();edge[0]=100;
        int[] multiple=base.clone();multiple[4]=100;multiple[7]=100;
        for(int[] board:List.of(placeholder,edge,multiple))
            assertThrows(IllegalArgumentException.class,()->new GameRuleCore.CompleteRound(false,
                    List.of(new GameRuleCore.Step(board,0,0))));
    }
    @Test void huntingRoundsAreGeneratedFromRulesAndCrossOldTemplateCeilings(){
        CompleteRoundFactory factory=new CompleteRoundFactory(); MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        SecureRandom random=new SecureRandom();
        for(int type:new int[]{0,3,4,5}){
            Set<String> members=new HashSet<>(); Set<Integer> multipliers=new HashSet<>(); int high=0;
            for(int i=0;i<1000;i++){
                var round=type==0?factory.generateSpecial(random):factory.generateBuy(type,random);
                int multiplier=ResultUtil.redisMultiplierCenti(round);if(multiplier>=10000&&multiplier<=30000)high++;
                byte[] member=codec.encode(round);members.add(java.util.HexFormat.of().formatHex(member));multipliers.add(multiplier);
                assertTrue(startsWithAscii(member, "MS4B"));assertFalse(containsAscii(member, "{\""));
                assertEquals(multiplier,ResultUtil.redisMultiplierCenti(codec.decode(member)));
                assertEquals(0,round.steps().get(round.steps().size()-1).nextType());
            }
            assertEquals(1000,members.size());assertTrue(multipliers.size()>100,"not restricted to 20 captured results");
            assertTrue(high>0,"new rule-valid results must cross 10000 inside the configured 30000 cap");
        }
    }
    @Test void sharedFactoryKeepsConcurrentHuntingRoundsIndependent() throws Exception {
        var factory=new CompleteRoundFactory();var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Callable<Set<String>>> jobs=new ArrayList<>();
            for(int worker=0;worker<4;worker++) jobs.add(()->{var random=new SecureRandom();var codec=new MinimalRoundFactCodec();Set<String> out=new HashSet<>();
                for(int i=0;i<100;i++){var round=factory.generateBuy(3+i%3,random);GameRuleCore.validate(round);out.add(java.util.HexFormat.of().formatHex(codec.encode(round)));}return out;});
            Set<String> all=new HashSet<>();for(var future:pool.invokeAll(jobs))all.addAll(future.get());assertEquals(400,all.size());
        } finally {pool.shutdownNow();}
    }
    @Test void corruptedWeaponTraceCannotBeAcceptedAsAValidRound(){
        var factory=new CompleteRoundFactory();var round=factory.generateBuy(3,new SecureRandom());
        var steps=new ArrayList<>(round.steps());var step=steps.get(1);var f=step.feature();
        var trace=HuntTrace.decode(f.roles());var actions=new ArrayList<>(trace.actions());var a=actions.get(0);
        actions.set(0,new HuntTrace.Action(a.weapon(),a.direction(),a.row(),a.blocked()==0?1:0,a.health(),a.multiplier(),a.next(),a.before(),a.after(),a.hearts(),a.animals(),a.split(),a.wild1(),a.wild2()));
        byte[] changed=HuntTrace.encode(new HuntTrace.Trace(trace.spin(),trace.mode(),trace.buy(),actions));
        steps.set(1,new GameRuleCore.Step(step.board(),step.gameType(),step.nextType(),new GameRuleCore.FeatureFacts(f.hearts(),f.locCell(),f.locId(),f.bl(),f.iu(),f.t(),f.rbs(),changed)));
        var invalid=new GameRuleCore.CompleteRound(true,3,steps);assertThrows(IllegalArgumentException.class,()->ResultUtil.evaluate(invalid));
    }
    @Test void everyBuyCorpusMemberUsesCompactLosslessStateFacts() throws Exception {
        MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        for(int type:new int[]{3,4,5}){
            String resource="/monster-slayer-buy-"+type+".txt";
            String raw;
            try(var in=GenerationValidationTest.class.getResourceAsStream(resource)){
                assertNotNull(in,resource);
                raw=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            for(String legacy:raw.split("\\R")){
                GameRuleCore.CompleteRound original=codec.decodeBuyTemplate(type,legacy);
                byte[] compact=codec.encode(original);
                GameRuleCore.CompleteRound decoded=codec.decode(compact);
                assertRoundFactsEqual(original,decoded);
                assertTrue(compact.length<legacy.length(),
                        ()->"compact state framing must remain smaller: compact="+compact.length+" legacy="+legacy.length());
                assertArrayEquals(compact,codec.encode(decoded));
            }
        }
    }
    @Test void legacyMs1AndMs2RemainReadableDuringRollingUpgrade(){
        MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        var oldNormal=codec.decode("MS1N;0.0.2,6,6,6,A,A,4,3,7,3,7,8,9,7,3");
        assertEquals(20,codec.encode(oldNormal).length);
        var factory=new CompleteRoundFactory();
        var buy=factory.generateBuy(3,new SecureRandom());
        String legacyStep=buy.steps().stream().map(GenerationValidationTest::legacyFeatureStep)
                .reduce((a,b)->a+"/"+b).orElseThrow();
        var decoded=codec.decode("MS2B1;"+legacyStep);
        assertEquals(3,decoded.buyType());
        assertEquals(buy.steps().size(),decoded.steps().size());
    }

    private static void assertRoundFactsEqual(GameRuleCore.CompleteRound expected,GameRuleCore.CompleteRound actual){
        assertEquals(expected.special(),actual.special());
        assertEquals(expected.buyType(),actual.buyType());
        assertEquals(expected.steps().size(),actual.steps().size());
        for(int i=0;i<expected.steps().size();i++){
            var a=expected.steps().get(i);var b=actual.steps().get(i);
            assertEquals(a.gameType(),b.gameType());assertEquals(a.nextType(),b.nextType());
            assertArrayEquals(a.board(),b.board());
            assertArrayEquals(a.feature().hearts(),b.feature().hearts());
            assertArrayEquals(a.feature().locCell(),b.feature().locCell());
            assertArrayEquals(a.feature().locId(),b.feature().locId());
            assertArrayEquals(a.feature().bl(),b.feature().bl());
            assertArrayEquals(a.feature().iu(),b.feature().iu());
            assertArrayEquals(a.feature().t(),b.feature().t());
            assertArrayEquals(a.feature().rbs(),b.feature().rbs());
            assertArrayEquals(a.feature().roles(),b.feature().roles(),"f.r animation facts must round-trip exactly");
        }
    }

    private static String legacyFeatureStep(GameRuleCore.Step step){
        StringBuilder out=new StringBuilder().append(step.gameType()).append('.').append(step.nextType()).append('.');
        int[] board=step.board();for(int i=0;i<board.length;i++){if(i>0)out.append(',');out.append(board[i]);}
        var f=step.feature();out.append("|H");appendInts(out,f.hearts(),',');out.append("|L");
        int[] lc=f.locCell(),li=f.locId();for(int i=0;i<lc.length;i++){if(i>0)out.append(';');out.append(lc[i]).append(':').append(li[i]);}
        out.append("|A");int[] bl=f.bl(),iu=f.iu(),t=f.t();for(int i=0;i<bl.length;i++){if(i>0)out.append(';');out.append(bl[i]).append('.').append(iu[i]).append('.').append(t[i]);}
        out.append("|R");appendInts(out,f.rbs(),',');out.append("|G").append(java.util.HexFormat.of().formatHex(f.roles()));return out.toString();
    }
    private static void appendInts(StringBuilder out,int[] values,char separator){
        for(int i=0;i<values.length;i++){if(i>0)out.append(separator);out.append(values[i]);}
    }
    @Test void featureAwardsBeginWithNaturalPayingSymbol(){
        int[]board={1,0,10,0,2,3,0,4,5,6,7,8,9,10,2};
        for(ResultUtil.Award a:ResultUtil.evaluateStep(new GameRuleCore.Step(board,1,1)).awards())
            assertEquals(a.symbol(),board[a.cells().get(0)]);
    }

    private static boolean startsWithAscii(byte[] data, String prefix) {
        byte[] p = prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (data.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (data[i] != p[i]) return false;
        return true;
    }
    private static boolean containsAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + n.length <= data.length; i++) {
            for (int j = 0; j < n.length; j++) if (data[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

}
