package com.cpgame.batchc.cybergo;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StrictPoolMultiplierTest {
    private static final String WIN_15 = "245267201444555";

    @Test
    void currentBucketsAreAcceptedAndOldPaidBetBucketsRejected() throws Exception {
        var core = new GameRuleCore();
        var codec = new MinimalFactCodec();
        var normal = core.rebuild(codec.decodeFromRedis(WIN_15.getBytes(StandardCharsets.US_ASCII)));
        RedisRoundPool.requireMultiplier(normal, ResultUtil.reverse(normal), "15");
        assertThrows(IOException.class, () -> RedisRoundPool.requireMultiplier(normal, ResultUtil.reverse(normal), "16"));
        String member = "945967901444555|" + String.join("|", Collections.nCopies(12, WIN_15));
        var free = core.rebuild(codec.decodeFromRedis(member.getBytes(StandardCharsets.US_ASCII)));
        RedisRoundPool.requireMultiplier(free, ResultUtil.reverse(free), "360");
        assertThrows(IOException.class, () -> RedisRoundPool.requireMultiplier(free, ResultUtil.reverse(free), "12"));
    }

    @Test
    void zeroMarkerRemainsZeroAtAllSupportedBetLevels() throws Exception {
        var core = new GameRuleCore();
        var codec = new MinimalFactCodec();
        for (String size : new String[]{"0.02", "0.2"}) {
            for (int level : new int[]{1, 3, 10}) {
                var round = core.atBet(core.rebuild(codec.decodeFromRedis(new byte[]{'#'})), level, new BigDecimal(size));
                var result = ResultUtil.reverse(round);
                RedisRoundPool.requireMultiplier(round, result, "0");
                assertEquals(0, result.totalWin().signum());
                assertEquals(1, round.deliveries().size());
                assertEquals(CyberGoModels.RoundKind.ORDINARY_LOSS, result.inferredKind());
            }
        }
    }
}
