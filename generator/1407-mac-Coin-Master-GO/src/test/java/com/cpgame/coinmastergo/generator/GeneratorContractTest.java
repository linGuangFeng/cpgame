package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.CardMaterialState;
import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;
import com.cpgame.coinmastergo.service.GameProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorContractTest {
    private final Path project = Path.of("").toAbsolutePath().normalize();
    private final Path propertiesPath = project.resolve("src/main/resources/generator.properties");

    @Test
    void formalConfigurationUsesEvidenceCountsAndKeepsWildTransformationOnly() throws Exception {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(propertiesPath);
        assertEquals(100_000_000, configuration.normalCount);
        assertEquals(100_000_000, configuration.specialCount);
        assertEquals(300, configuration.maxMembersPerMultiplier);
        assertEquals(1, configuration.normalMinWinMultiplier);
        assertEquals(100, configuration.specialMinWinMultiplier);
        assertEquals(1500, configuration.normalMaxWinMultiplier);
        assertEquals(2000, configuration.specialMaxWinMultiplier);
        assertEquals(Set.copyOf(GeneratorConfiguration.symbolOrder()), configuration.symbolWeights.keySet());
        assertEquals(GameRules.EVIDENCE_START_SYMBOL_WEIGHTS, configuration.symbolWeights);
        assertEquals(0, configuration.symbolWeights.get("WILD"));
        assertEquals(GameRules.EVIDENCE_SILVER_CARD_WEIGHT, configuration.silverCardWeight);
        assertEquals(GameRules.EVIDENCE_GOLD_CARD_WEIGHT, configuration.goldCardWeight);
        assertTrue(configuration.symbolWeights.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("WILD"))
                .allMatch(entry -> entry.getValue() > 0));
        String text = Files.readString(propertiesPath).toLowerCase(Locale.ROOT);
        assertFalse(text.contains("seed=") || text.contains("redis.enabled") || text.contains("write-enabled")
                || text.contains("output.file") || text.contains("local_jsonl") || text.contains("probability=0"));

        Properties excessiveCount = loadProperties();
        excessiveCount.setProperty("generation.normal-count", "2147483648");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(excessiveCount));
        Properties directWild = loadProperties();
        directWild.setProperty("generation.symbol.WILD.weight", "1");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(directWild));
        Properties forbiddenSeed = loadProperties();
        forbiddenSeed.setProperty("seed", "1");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(forbiddenSeed));
        Properties forbiddenZeroProbability = loadProperties();
        forbiddenZeroProbability.setProperty("generation.zero-multiplier.probability", "0.5");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(forbiddenZeroProbability));
        Properties noCardMaterial = loadProperties();
        noCardMaterial.setProperty("generation.card.silver.weight", "0");
        noCardMaterial.setProperty("generation.card.gold.weight", "0");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(noCardMaterial));
        Properties invertedRange = loadProperties();
        invertedRange.setProperty("generation.normal-min-win-multiplier", "20001");
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfiguration.from(invertedRange));
    }

    @Test
    void specialEntryOnlyMultipliesScatterOpeningWeightByTen() {
        Map<String, Integer> ordinary = Map.of(
                "H1", 1595, "H2", 1569, "H3", 1542, "H4", 1489,
                "H5", 1508, "H6", 1547, "H7", 1500, "H8", 1459,
                "WILD", 0, "SC", 316);
        Map<String, Integer> special = RedisDirectLoader.specialEntryOpeningWeights(ordinary);
        assertEquals(3160, special.get("SC"));
        ordinary.forEach((symbol, weight) -> {
            if (!"SC".equals(symbol)) assertEquals(weight, special.get(symbol));
        });
        assertEquals(1000, RedisDirectLoader.ENTRY_SWITCH_EVERY);
        assertEquals(10, RedisDirectLoader.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
    }

    @Test
    void configuredWeightedSecureRandomPassesDistributionValidation() {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(propertiesPath);
        WeightedGameRuleRandom random = new WeightedGameRuleRandom(new SecureRandom(), configuration.symbolWeights);
        int samples = 200_000;
        Map<String, Integer> observed = new HashMap<>();
        for (int i = 0; i < samples; i++) observed.merge(random.drawSymbolForDistributionCheck(), 1, Integer::sum);
        int totalWeight = configuration.symbolWeights.values().stream().mapToInt(Integer::intValue).sum();
        for (String symbol : GeneratorConfiguration.symbolOrder()) {
            double expected = configuration.symbolWeights.get(symbol) / (double) totalWeight;
            double actual = observed.getOrDefault(symbol, 0) / (double) samples;
            assertEquals(expected, actual, 0.005, symbol + " 分布偏离配置权重");
        }
    }

    @Test
    void naturalCoreProducesAndIndependentlyVerifiesPositiveNormalAndCompleteSpecialRounds() {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(propertiesPath);
        GameRuleCore core = weightedCore(configuration);
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(new RoundResultUtil(), configuration);
        boolean normal = false, special = false;
        for (int attempt = 0; attempt < 5_000 && !(normal && special); attempt++) {
            RoundPlan round = core.generateRuntimeRound(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    1, new BigDecimal("0.02"), BigDecimal.ZERO, System.currentTimeMillis());
            RoundResultUtil.RoundAnalysis analysis = verifyEligible(round, verifier, configuration);
            if (analysis == null) continue;
            assertEquals(RedisDirectLoader.exactRatio(analysis.multiplier()),
                    analysis.multiplier().intValueExact());
            if (analysis.multiplier().signum() > 0) {
                if (analysis.special()) {
                    special = true;
                    assertTrue(analysis.freeStepCount() >= 12);
                    assertTrue(analysis.terminal());
                } else normal = true;
            }
        }
        assertTrue(normal, "未自然产生正倍数普通完整 Round");
        assertTrue(special, "未自然产生已确认免费模式完整 Round");
    }

    @Test
    void minimalMemberRoundTripIsRecomputedByIndependentResultUtil() {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(propertiesPath);
        GameRuleCore core = weightedCore(configuration);
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(new RoundResultUtil(), configuration);
        MinimalFactCodec codec = new MinimalFactCodec();
        for (int attempt = 0; attempt < 2_000; attempt++) {
            RoundPlan round = core.generateRuntimeRound("r", "t", 1, new BigDecimal("0.02"),
                    BigDecimal.ZERO, System.currentTimeMillis());
            RoundResultUtil.RoundAnalysis before = verifyEligible(round, verifier, configuration);
            if (before == null) continue;
            if (before.multiplier().signum() == 0) continue;
            String member = codec.encode(round);
            RoundResultUtil.RoundAnalysis after = verifier.verify(codec.rebuild(member));
            assertEquals(before.pool(), after.pool());
            assertEquals(0, before.multiplier().compareTo(after.multiplier()));
            assertFalse(member.startsWith("{") || member.startsWith("["));
            assertFalse(member.contains("roundKey") || member.contains("balance") || member.contains("totalWin"));
            return;
        }
        fail("未获得可验证的正倍数 member");
    }

    @Test
    void codecAndIndependentVerifierRejectIllegalDeliveryOrderAndBrokenCellContinuity() throws Exception {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(propertiesPath);
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(new RoundResultUtil(), configuration);
        MinimalFactCodec codec = new MinimalFactCodec();
        GameProperties properties = new GameProperties();
        properties.setDemoSeed(1407L);
        GameRuleCore core = new GameRuleCore(properties);

        RoundPlan free = core.generateCompleteRound(RoundScenario.FREE_SPINS, "r-free", "t-free", 1,
                new BigDecimal("0.02"), BigDecimal.ZERO, 1L);
        ObjectMapper mapper = new ObjectMapper();
        MinimalFactCodec.MinimalRoundFacts extracted = codec.extract(free);
        List<MinimalFactCodec.DeliveryFacts> swapped = new java.util.ArrayList<>(extracted.d());
        assertTrue(swapped.size() > 1);
        MinimalFactCodec.DeliveryFacts freeDelivery = swapped.get(1);
        swapped.set(1, new MinimalFactCodec.DeliveryFacts("BASE", freeDelivery.s()));
        String illegalOrder = mapper.writeValueAsString(new MinimalFactCodec.MinimalRoundFacts(1, swapped));
        assertThrows(IllegalArgumentException.class, () -> codec.rebuild(illegalOrder));

        RoundPlan baseWin = core.generateCompleteRound(RoundScenario.BASE_WIN, "r-win", "t-win", 1,
                new BigDecimal("0.02"), BigDecimal.ZERO, 1L);
        String compact = codec.encode(baseWin);
        assertFalse(compact.startsWith("{"));
        assertTrue(compact.length() >= 80, "BASE_WIN must contain a cascade step");
        char[] brokenChars = compact.toCharArray();
        brokenChars[40 + 4] = brokenChars[40 + 4] == '1' ? '2' : '1';
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(codec.rebuild(new String(brokenChars))));
    }

    @Test
    void codecRebuildAwardsMaryFromCascadeCompletedTerminalScatter() {
        RoundPlan round = cascadeCompletedScatterRound();
        new RoundValidator().validate(round);
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundPlan rebuilt = codec.rebuild(codec.encode(round));
        SpinStep opening = rebuilt.deliveries.getFirst().steps.getFirst();
        SpinStep terminal = rebuilt.deliveries.getFirst().steps.getLast();
        assertEquals(2, CoinMasterResultUtil.evaluate(opening.rskl, 1, BigDecimal.ONE, 1).scatterCount());
        assertEquals(3, CoinMasterResultUtil.evaluate(terminal.rskl, 1, BigDecimal.ONE, 1).scatterCount());
        assertEquals(0, opening.fsn);
        assertEquals(12, terminal.fsn);
        assertEquals(13, rebuilt.deliveries.size());
        assertEquals(RoundScenario.FREE_SPINS.name(), rebuilt.scenario);
    }

    @Test
    void redisKeysUseCommonPlatformContractAndActualIntegerMultiplier() {
        assertEquals("PerKeyList_000001407", RedisDirectLoader.normalIndex(1_407));
        assertEquals("MaryKeyList_000001407", RedisDirectLoader.specialIndex(1_407));
        assertEquals("BetLog:000001407:000002", RedisDirectLoader.normalList(1_407, 2));
        assertEquals("MaryLog:000001407:000250", RedisDirectLoader.specialList(1_407, 250));
        assertEquals(2, RedisDirectLoader.exactRatio(new BigDecimal("2.0")));
        assertTrue(RedisDirectLoader.shouldPersist(BigDecimal.ZERO));
        assertTrue(RedisDirectLoader.shouldPersist(BigDecimal.ONE));
        assertFalse(RedisDirectLoader.shouldPersist(new BigDecimal("-1")));
        assertEquals(GameRules.RULES_HASH, "08727f7e5a890dcd1b3cf5ae9559474ef9ea98c6e9a09ac34b7d53598ba1662f");
    }

    private RoundResultUtil.RoundAnalysis verifyEligible(RoundPlan round, CompleteRoundVerifier verifier,
                                                        GeneratorConfiguration configuration) {
        RoundResultUtil.RoundAnalysis result = new RoundResultUtil().analyze(round);
        if (result.multiplier().compareTo(BigDecimal.valueOf(configuration.maximumMultiplier(result.special()))) > 0
                || result.freeStepCount() > configuration.maxFreeSpins
                || result.longestConsecutiveWins() > configuration.maxConsecutiveWins) {
            assertThrows(IllegalArgumentException.class, () -> verifier.verify(round));
            return null;
        }
        return verifier.verify(round);
    }

    private GameRuleCore weightedCore(GeneratorConfiguration configuration) {
        GameRuleCore core = new GameRuleCore(new GameProperties());
        WeightedGameRuleRandom.install(core, configuration.symbolWeights,
                configuration.silverCardWeight, configuration.goldCardWeight);
        return core;
    }

    private Properties loadProperties() throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(propertiesPath)) { properties.load(reader); }
        return properties;
    }

    private RoundPlan cascadeCompletedScatterRound() {
        RoundPlan round = new RoundPlan();
        round.roundKey = "cascade-sc";
        round.transferId = "cascade-sc";
        round.paidBid = GameRules.GAME_PROTOCOL_ID + "-cascade-sc";
        round.betLevel = 1;
        round.betSize = new BigDecimal("0.02");
        round.betAmount = GameRules.betAmount(round.betLevel, round.betSize);
        round.postDebitBalance = BigDecimal.ZERO;
        round.createdAt = 1L;
        round.scenario = RoundScenario.FREE_SPINS.name();
        List<String> opening = List.of("H4","H1","H1","H2","H3","H8","H1","H5","H6","H7","H8","H1","H5","H6","H7","H4","SC","H2","H3","H4","H5","H6","SC","H7","H8");
        List<String> terminal = List.of("H6","SC","H4","H2","H3","H2","H8","H5","H6","H7","H2","H8","H5","H6","H7","H4","SC","H2","H3","H4","H5","H6","SC","H7","H8");
        List<String> idle = List.of("H8","H1","H2","H3","H4","H8","H5","H6","H7","H8","H8","H1","H5","H6","H7","H8","H2","H3","H7","H8","H8","H1","H4","H6","H8");
        SpinStep paid = fill(round, opening, 1, 0, 1, 0, 0, 0, round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO);
        SpinStep baseEnd = fill(round, terminal, 1, 1, 2, 1, 12, 0, BigDecimal.ZERO, paid.rwa, BigDecimal.ZERO);
        round.deliveries.add(new RoundDelivery("BASE", List.of(paid, baseEnd)));
        BigDecimal cumulative = baseEnd.rwa;
        for (int nfsc = 1; nfsc <= 12; nfsc++) {
            SpinStep free = fill(round, idle, 2, 2, 2, 1, 12, nfsc, BigDecimal.ZERO, cumulative, BigDecimal.ZERO);
            if (nfsc == 12) free.pb = round.postDebitBalance.add(cumulative).setScale(2).toPlainString();
            round.deliveries.add(new RoundDelivery("FREE", List.of(free)));
        }
        round.totalWin = CoinMasterResultUtil.money(cumulative);
        return round;
    }

    private SpinStep fill(RoundPlan round, List<String> cells, int gt, int smallGameType, int rpx, int ss,
                          int fsn, int nfsc, BigDecimal ba, BigDecimal priorRwa, BigDecimal priorFrwa) {
        CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                cells, round.betLevel, round.betSize, rpx);
        SpinStep step = new SpinStep();
        step.ba = CoinMasterResultUtil.money(ba);
        step.gt = gt;
        step.small_game_type = smallGameType;
        step.rpx = rpx;
        step.ss = ss;
        step.fsn = fsn;
        step.nfsc = nfsc;
        step.pb = round.postDebitBalance.setScale(2).toPlainString();
        step.rskl = new ArrayList<>(cells);
        step.gfl = new ArrayList<>();
        step.silverCardCoordinates = new ArrayList<>(
                CardMaterialState.decodeSilverCoordinates(step.rskl, step.gfl));
        step.wa = evaluation.totalWin();
        step.rwa = CoinMasterResultUtil.money(priorRwa.add(step.wa));
        step.frwa = gt == 2 ? CoinMasterResultUtil.money(priorFrwa.add(step.wa)) : priorFrwa;
        for (WinMatch match : evaluation.matches()) {
            step.wskl.add(match.symbol);
            step.wmkl.add(match.coordinates);
        }
        return step;
    }
}
