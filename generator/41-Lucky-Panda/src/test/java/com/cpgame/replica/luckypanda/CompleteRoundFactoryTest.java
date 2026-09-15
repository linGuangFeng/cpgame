package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaSymbol;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompleteRoundFactoryTest {
    private static final BigDecimal BS = new BigDecimal("0.02");
    private static final int BL = 1;

    @Test
    void legacyCodecRoundTripIsAsciiNotJsonAndRestoresBoards() {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundFactory.GeneratedRound generated = factory.generate(
                new Random(7), 10, 30, weights(), true);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String member = codec.encodeFull(generated.fact());
        assertTrue(member.startsWith("lp1|"));
        assertFalse(member.contains("{"));
        assertFalse(member.contains("["));
        assertEquals(StandardCharsets.US_ASCII.newEncoder().canEncode(member), true);
        CompleteRoundFact decoded = codec.decode(member);
        assertEquals(generated.fact().paid().size(), decoded.paid().size());
        assertEquals(generated.fact().paid().get(0).board().toRskl(), decoded.paid().get(0).board().toRskl());
        RoundVerification verification = codec.verify(member, 10);
        assertEquals(RoundClass.ORDINARY_LOSS, verification.roundClass());
        assertEquals(0, verification.actualMultiplier());
        assertFalse(verification.special());
        assertEquals(generated.fact().paid().get(0).gfl(), decoded.paid().get(0).gfl());
        assertEquals(generated.fact().paid().get(0).sfl(), decoded.paid().get(0).sfl());
        for (var page : generated.fact().paid()) {
            for (int coord : page.gfl()) {
                var token = page.board().tokenAt(coord);
                assertTrue(token != null && token.height() >= 2 && !token.top());
            }
            for (int coord : page.sfl()) {
                var token = page.board().tokenAt(coord);
                assertTrue(token != null && token.height() >= 2 && !token.top());
            }
        }
    }

    @Test
    void longFramesReceiveGoldOrSilverAndCascadeCanRefillStacks() {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Random random = new Random(41041);
        int framed = 0;
        int cascadeStacks = 0;
        int pages = 0;
        for (int i = 0; i < 80; i++) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, 10, 30, weights(), false);
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
                continue;
            }
            codec.verify(codec.encode(generated.fact()), 10);
            for (var page : generated.fact().paid()) {
                pages++;
                if (!page.gfl().isEmpty() || !page.sfl().isEmpty()) framed++;
            }
            if (generated.fact().paid().size() >= 2) {
                var refill = generated.fact().paid().get(1).board();
                for (int reel = 1; reel <= 4; reel++) {
                    for (var token : refill.reel(reel)) {
                        if (!token.top() && token.height() >= 2) cascadeStacks++;
                    }
                }
            }
        }
        assertTrue(pages > 0);
        assertTrue(framed > 0, "gfl/sfl long-frame overlay must appear");
        assertTrue(cascadeStacks > 0, "cascade refill must still emit height 2-4 long frames");
    }

    @Test
    void panPagesCarryEvenRpxAndNeverDecrease() {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        Random random = new Random(41042);
        int panPages = 0;
        int rpxOnPan = 0;
        boolean decreased = false;
        for (int i = 0; i < 30; i++) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, 10, 30, weights(), false);
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
                continue;
            }
            int prev = 0;
            for (var page : generated.fact().paid()) {
                if (page.rpx() < prev) decreased = true;
                prev = page.rpx();
                int pans = page.board().tokens(LuckyPandaSymbol.PAN);
                if (pans > 0) {
                    panPages++;
                    if (page.rpx() >= 2 && page.rpx() % 2 == 0) rpxOnPan++;
                }
            }
        }
        assertTrue(panPages > 0);
        assertEquals(panPages, rpxOnPan);
        assertTrue(!decreased);
    }

    @Test
    void independentLossIsZeroAndNotScatterFree() {
        LuckyPandaBoardGenerator boards = new LuckyPandaBoardGenerator(new Random(11), weights());
        LuckyPandaIndependentLossGenerator losses = new LuckyPandaIndependentLossGenerator(boards, BS, BL);
        for (int i = 0; i < 200; i++) {
            var board = losses.generate(WeightScene.PAID_START);
            var evaluation = LuckyPandaResultUtil.evaluate(board, BS, BL, 0);
            assertFalse(evaluation.hasWaysWin());
            assertTrue(evaluation.scatterTokens() < 4);
            assertEquals(1, evaluation.ss());
            assertTrue(GameRuleCore.withinCapturedCaps(board));
        }
    }

    @Test
    void generatedRoundsPartitionWithoutElse() {
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Set<RoundClass> seen = EnumSet.noneOf(RoundClass.class);
        Random random = new Random(41);
        Map<WeightScene, int[]> special = RedisDirectLoader.boostedScatter(weights());
        for (int i = 0; i < 40; i++) {
            boolean specialEntry = i % 2 == 1;
            CompleteRoundFactory.GeneratedRound generated = factory.generate(
                    random, 10, 30, specialEntry ? special : weights(), i == 0);
            RoundVerification verification = codec.verify(codec.encode(generated.fact()), 10);
            seen.add(verification.roundClass());
            boolean specialPool = switch (verification.roundClass()) {
                case SCATTER_FREE -> true;
                case ORDINARY_LOSS, ORDINARY_WIN -> false;
            };
            assertEquals(specialPool, verification.special());
            if (verification.roundClass() == RoundClass.ORDINARY_LOSS) {
                assertEquals(0, verification.actualMultiplier());
            } else {
                assertTrue(verification.actualMultiplier() >= 0);
            }
            assertEquals(generated.actualMultiplier(), verification.actualMultiplier());
            for (var page : generated.fact().paid()) {
                assertTrue(GameRuleCore.withinCapturedCaps(page.board()));
            }
        }
        assertTrue(seen.contains(RoundClass.ORDINARY_LOSS));
    }

    @Test
    void loaderConfigReadsEveryFormalKey() throws Exception {
        Path file = Path.of("src/main/dist/generator.properties");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String[] keys = {
                "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
                "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
                "generation.normal-count", "generation.special-count", "generation.batch-size",
                "generation.max-members-per-multiplier", "generation.max-consecutive-wins",
                "generation.max-mary-spins", "generation.normal-min-win-multiplier",
                "generation.normal-max-win-multiplier", "generation.mary-min-win-multiplier",
                "generation.mary-max-win-multiplier", "generation.bet-size", "generation.bet-level"
        };
        for (String key : keys) {
            assertTrue(properties.containsKey(key), key);
        }
        for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) {
            for (String scene : new String[]{"paid-start", "cascade-refill", "free-start", "free-cascade-refill"}) {
                String key = "generation.symbol." + symbol.wireName() + "." + scene + "-weight";
                assertTrue(properties.containsKey(key), key);
            }
        }
        RedisDirectLoader.LoaderConfig config = RedisDirectLoader.LoaderConfig.load(file.toAbsolutePath());
        assertEquals(Long.parseLong(properties.getProperty("redis.game-id")), config.redisGameId());
        assertEquals(Integer.parseInt(properties.getProperty("generation.normal-min-win-multiplier")), config.normalMinWinMultiplier());
        assertEquals(30, config.maxMarySpins());
        assertTrue(config.maxMarySpins() >= LuckyPandaResultUtil.scatterFreeAwarded(5));
    }

    static Map<WeightScene, int[]> weights() {
        EnumMap<WeightScene, int[]> map = new EnumMap<>(WeightScene.class);
        map.put(WeightScene.PAID_START, new int[]{652, 4254, 4182, 3987, 3995, 3897, 3962, 4003, 4075, 3966, 3882, 182, 783});
        map.put(WeightScene.CASCADE_REFILL, new int[]{287, 1181, 1125, 1045, 1001, 1034, 965, 972, 1001, 942, 899, 337, 237});
        map.put(WeightScene.FREE_START, new int[]{212, 1133, 1244, 1126, 1067, 1102, 1017, 1065, 1083, 1140, 1092, 70, 209});
        map.put(WeightScene.FREE_CASCADE_REFILL, new int[]{85, 297, 291, 283, 312, 285, 289, 274, 266, 272, 288, 46, 59});
        return map;
    }
}
