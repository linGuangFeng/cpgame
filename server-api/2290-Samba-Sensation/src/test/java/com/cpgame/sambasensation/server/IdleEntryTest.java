package com.cpgame.sambasensation.server;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleEntryTest {
    @Test void entryWithoutRedisDoesNotChangeBalanceOrCollection() throws Exception {
        var service = new SambaSensationService(null, new BigDecimal("10000"), new SecureRandom());
        var form = Map.of("token", "idle-entry");
        var before = service.status(form).deepCopy();
        for (int i = 0; i < 10; i++) {
            var data = service.init(form).path("data");
            assertEquals(0, data.path("total_win").decimalValue().signum());
            assertEquals(0, data.path("change_gold").decimalValue().signum());
            assertEquals(0, data.path("end_gold").decimalValue().compareTo(new BigDecimal("10000")));
            assertTrue(data.has("props"));
        }
        assertEquals(before, service.status(form));
        assertEquals(0, service.historyDetail(form).path("data").path("list").size());
    }
}
