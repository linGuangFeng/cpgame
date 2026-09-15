package com.cpgame.monsterslayer.server;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleEntryTest {
    @Test void entryWithoutRedisDoesNotChargeOrCreateHistory() throws Exception {
        var service = new MonsterSlayerService(null, new BigDecimal("10000"));
        var form = Map.of("token", "idle-entry");
        var before = service.status(form).deepCopy();
        for (int i = 0; i < 10; i++) {
            var data = service.init(form).path("data");
            assertEquals(0, data.path("tw").decimalValue().signum());
            assertEquals(0, data.path("cg").decimalValue().signum());
            assertEquals(0, data.path("eg").decimalValue().compareTo(new BigDecimal("10000")));
            assertEquals(15, data.path("res").path("ps").size());
        }
        assertEquals(before, service.status(form));
        assertEquals(0, service.historyDetail(form).path("data").path("list").size());
    }
}
