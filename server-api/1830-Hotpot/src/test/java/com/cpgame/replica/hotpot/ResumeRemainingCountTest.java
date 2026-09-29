package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResumeRemainingCountTest {
    @Test void tenAwardedThreeConsumedRestoresSevenWithoutAdvancing() throws Exception {
        var random = new Random(183021);
        var factory = new CompleteRoundFactory();
        CompleteRoundFactory.GeneratedRound round = null;
        int[] opening=RedisDirectLoader.specialEntryOpeningWeights(HotpotBoardGenerator.defaultPaidStartWeights());
        for(int i=0;i<10000 && round==null;i++) {
            try {
                var candidate=factory.generate(random,10,30,opening,HotpotBoardGenerator.defaultCascadeWeights(),HotpotBoardGenerator.defaultFreeStartWeights());
                if(candidate.fact().spins().size()==11)round=candidate;
            } catch(CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        assertNotNull(round);
        var codec=new CompleteRoundCodec();var fake=new FakeRedisCommands();
        fake.seed(true,round.multiplier(),codec.encode(round.fact()));
        var store=new RedisRoundStore(fake,1830L);
        var claimed=store.claimKind(HotpotRoundKind.SCATTER_FREE_SPINS,new SecureRandom());
        var controller=new HotpotController(Path.of("../../publish/1830-Hotpot"),new Properties(),store,new SecureRandom());
        var state=new HotpotController.SessionState("resume-count-test",new BigDecimal("1000.00"));
        state.active=new HotpotController.ActiveRound("resume-test-round",claimed,new BigDecimal("0.02"),1);
        var settle=HotpotController.class.getDeclaredMethod("settleNext",HotpotController.SessionState.class);settle.setAccessible(true);
        var init=HotpotController.class.getDeclaredMethod("initResponse",HotpotController.SessionState.class);init.setAccessible(true);
        var resume=HotpotController.class.getDeclaredMethod("resumeActiveRound",HotpotController.SessionState.class);resume.setAccessible(true);
        for(int i=0;i<4;i++)settle.invoke(controller,state);
        assertEquals(7,state.active.remaining);assertEquals(10,state.active.totalAwarded);
        BigDecimal balance=state.balance;
        for(int i=0;i<2;i++) {
            ObjectNode initial=(ObjectNode)init.invoke(controller,state);
            assertTrue(initial.path("data").path("_resumeAvailable").asBoolean());
            ObjectNode replay=(ObjectNode)resume.invoke(controller,state);
            assertEquals(7,replay.path("frees").path("st").asInt());
            assertEquals(10,replay.path("frees").path("tt").asInt());
            assertEquals(balance,state.balance);assertEquals(4,state.active.nextSpin);
            assertEquals(4,state.active.deliveries.size());
            assertEquals(10,state.active.deliveries.get(0).path("frees").path("st").asInt());
        }
        ObjectNode next=(ObjectNode)settle.invoke(controller,state);
        assertEquals(6,next.path("frees").path("st").asInt());
    }
}
