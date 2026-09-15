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
            String member=codec.encode(round);assertEquals(member,codec.encode(codec.decode(member)));
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
    @Test void specialGenerationFailsBeforeInventingState(){
        assertThrows(IllegalStateException.class,()->new CompleteRoundFactory().generateSpecial(new SecureRandom()));
    }
    @Test void buyTemplatesRoundTripAndStayInPrefixPools(){
        CompleteRoundFactory factory=new CompleteRoundFactory();MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        SecureRandom random=new SecureRandom();
        assertEquals("PerKeyList_000002300", com.cpgame.monsterslayer.redis.RedisKeyContract.normalIndex(2300));
        assertEquals("BetLog:000002300:000350", com.cpgame.monsterslayer.redis.RedisKeyContract.normalList(2300, 350));
        assertEquals("MaryKeyList_000002300", com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(3));
        assertEquals("MaryLog:000002300:000350", com.cpgame.monsterslayer.redis.RedisKeyContract.buyList(3, 350));
        assertEquals("PerKeyList_100002300", com.cpgame.monsterslayer.redis.RedisKeyContract.buyPerKeyIndex(4));
        assertEquals("MaryKeyList_100002300", com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(4));
        assertEquals("MaryLog:100002300:000350", com.cpgame.monsterslayer.redis.RedisKeyContract.buyList(4, 350));
        assertEquals("PerKeyList_200002300", com.cpgame.monsterslayer.redis.RedisKeyContract.buyPerKeyIndex(5));
        assertEquals("MaryKeyList_200002300", com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(5));
        assertEquals("MaryLog:200002300:000350", com.cpgame.monsterslayer.redis.RedisKeyContract.buyList(5, 350));
        assertFalse(com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndexesToWrite(3)
                .contains("PerKeyList_000002300"));
        for(int type:new int[]{3,4,5}){
            boolean sawGt4=false;
            boolean sawStoredRoles=false;
            for(int i=0;i<80;i++){
                GameRuleCore.CompleteRound round=factory.generateBuy(type,random);
                assertEquals(type,round.buyType());
                assertEquals(GameRuleCore.RoundClass.BUY_FEATURE,ResultUtil.evaluate(round).roundClass());
                assertEquals(0,round.steps().get(round.steps().size()-1).nextType());
                String member=codec.encode(round);
                assertEquals(type,codec.decode(member).buyType());
                for(GameRuleCore.Step step:round.steps()) {
                    if(step.gameType()==4) sawGt4=true;
                    if(step.feature().roles()!=null && step.feature().roles().startsWith("[")) sawStoredRoles=true;
                }
            }
            assertTrue(sawStoredRoles,"buy type="+type+" must replay captured f.r");
            if(type==4){
                for(int i=0;i<1000 && !sawGt4;i++){
                    for(GameRuleCore.Step step:factory.generateBuy(4,random).steps()) if(step.gameType()==4) sawGt4=true;
                }
                assertTrue(sawGt4,"buy type=4 corpus must include captured gt=4");
            }
        }
    }
    @Test void featureAwardsBeginWithNaturalPayingSymbol(){
        int[]board={1,0,10,0,2,3,0,4,5,6,7,8,9,10,2};
        for(ResultUtil.Award a:ResultUtil.evaluateStep(new GameRuleCore.Step(board,1,1)).awards())
            assertEquals(a.symbol(),board[a.cells().get(0)]);
    }
}
