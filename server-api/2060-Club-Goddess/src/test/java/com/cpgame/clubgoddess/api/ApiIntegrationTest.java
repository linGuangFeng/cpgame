package com.cpgame.clubgoddess.api;

import static org.junit.jupiter.api.Assertions.*;
import com.cpgame.clubgoddess.api.service.GameService;
import com.cpgame.clubgoddess.api.state.SessionState;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties={"api.signature.required=false","game.state-file=./target/test-state/session.json"})
class ApiIntegrationTest {
    @Autowired GameService service;

    @Test void paidSpinIsAtomicIdempotentAndResumable() {
        String runId=UUID.randomUUID().toString();
        SessionState s=service.bootstrap("test-launch-token-"+runId);
        BigDecimal before=s.balance;
        var first=service.spin(s.token,new BigDecimal("0.01"),10,"0","same-request-"+runId);
        var duplicate=service.spin(s.token,new BigDecimal("0.01"),10,"0","same-request-"+runId);
        assertEquals(first.roundKey(),duplicate.roundKey()); assertTrue(duplicate.idempotentReplay());
        assertEquals(first.result(),service.snapshot(s.token));
        assertEquals(before.add(first.result().change_gold()),service.session(s.token).balance);
        assertEquals(1,service.session(s.token).history.size());
        assertEquals(0,service.session(s.token).deliveryIndex);
    }

    @Test void independentRequestsDoNotCyclePreparedScenes() {
        String runId=UUID.randomUUID().toString();
        SessionState s=service.bootstrap("random-chain-token-"+runId);
        var a=service.spin(s.token,new BigDecimal("0.01"),10,"0","r1-"+runId,"loss");
        var b=service.spin(s.token,new BigDecimal("0.01"),10,"0","r2-"+runId,"loss");
        assertNotEquals(a.roundKey(),b.roundKey());
        assertNotEquals(a.result().props().prop(),b.result().props().prop());
    }
}
