package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

public final class GameRuleCoreSelfTest {
    private static final BigDecimal START = new BigDecimal("1000000.00");

    public static void main(String[] args) {
        verifyRuleOracleExamples();
        verifyExactRoundBranches();
        verifyRandomCompleteRounds();
        verifyNaturalIndependentLoss();
        verifyBetThresholdAndRedisCodec();
        verifyTargetedCatalogRounds();
        System.out.println("完整局与独立结果反推自检通过，rulesHash=" + GameRuleCore.RULES_HASH);
    }

    private static void verifyRuleOracleExamples() {
        require(ResultUtil.independentScore(List.of("H4", "H1")).compareTo(new BigDecimal("50")) == 0,
                "原始规则 oracle H4,H1 => 50 失败");
        require(ResultUtil.independentScore(List.of("H5", "H4")).compareTo(new BigDecimal("105")) == 0,
                "原始规则 oracle H5,H4 => 105 失败");
    }

    private static void verifyExactRoundBranches() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        RoundRequest request = new RoundRequest(1, 1, START);
        assertRound(factory.create(request, RoundFacts.ordinary(List.of("H0", "H1")), "T-loss"),
                OutcomeType.ORDINARY_LOSS, "0", 0);
        assertRound(factory.create(request, RoundFacts.ordinary(List.of("H4", "H1")), "T-win"),
                OutcomeType.ORDINARY_WIN, "50", 0);
        assertRound(factory.create(request, RoundFacts.multiplier(List.of("H4", "H1"), 5), "T-md1"),
                OutcomeType.MULTIPLIER_MD1, "250", 1);
        assertRound(factory.create(request, RoundFacts.respin(List.of("H5", "H4"), List.of("H3", "H1")), "T-md2"),
                OutcomeType.RESPIN_MD2, "115", 2);
        RoundRequest unlocked = new RoundRequest(5, 1, START);
        assertRound(factory.create(unlocked, RoundFacts.luckyWheel(List.of("H4", "H4", "H3"), 150), "T-md3"),
                OutcomeType.SCATTER_LUCKY_WHEEL_MD3, "701", 3);
    }

    private static void assertRound(GameRound round, OutcomeType outcome, String award, int mode) {
        ResultAnalysis analysis = IndependentRoundVerifier.verify(round, START);
        require(analysis.outcome() == outcome, "反推结果类型错误: " + outcome);
        require(analysis.totalAward().compareTo(new BigDecimal(award)) == 0, "反推派奖错误: " + award);
        require(round.deliveries().size() == 1 && round.deliveries().get(0).deliveryIndex() == 0, "完整局 Delivery 边界错误");
        require(round.deliveries().get(0).result().md() == mode, "协议模式错误: md=" + mode);
    }

    private static void verifyRandomCompleteRounds() {
        GameRuleCore core = new GameRuleCore(new SplittableRandom(430043L));
        EnumSet<OutcomeType> seen = EnumSet.noneOf(OutcomeType.class);
        Set<String> keys = new HashSet<>();
        BigDecimal balance = START;
        for (int i = 0; i < 10000 && (seen.size() < OutcomeType.values().length || keys.size() < 1000); i++) {
            int betLevel = switch (i % 5) { case 0 -> 1; case 1 -> 5; case 2 -> 10; case 3 -> 50; default -> 100; };
            GameRound round = core.generateRound(new RoundRequest(betLevel, 1, balance));
            ResultAnalysis analysis = IndependentRoundVerifier.verify(round, balance);
            require(analysis.outcome() == round.outcome(), "随机完整局分类不一致");
            balance = new BigDecimal(round.deliveries().get(0).result().pb());
            seen.add(analysis.outcome());
            if (betLevel < 5) require(analysis.outcome() != OutcomeType.SCATTER_LUCKY_WHEEL_MD3,
                    "MD3不得混入bet<5");
            if (keys.size() < 1000) require(keys.add(round.roundKey()), "roundKey 重复");
        }
        require(seen.size() == OutcomeType.values().length, "未覆盖全部已启用结果: " + seen);
        require(keys.size() == 1000, "未完成1000个唯一 roundKey 验证");
    }

    private static void verifyTargetedCatalogRounds() {
        require(LuckyWheelMultiplierCatalog.mode(1) == 1, "bl=1 应走 1 档");
        require(LuckyWheelMultiplierCatalog.mode(5) == 5, "bl=5 应走 5 档");
        require(LuckyWheelMultiplierCatalog.mode(10) == 10, "bl=10 应走 10 档");
        require(LuckyWheelMultiplierCatalog.mode(49) == 10, "bl=49 应走 10 档");
        require(LuckyWheelMultiplierCatalog.mode(50) == 50, "bl=50 应走 50 档");
        require(LuckyWheelMultiplierCatalog.mode(100) == 50, "bl>=50 应走 50 档");
        require(LuckyWheelMultiplierCatalog.floorOdd(5, 15) == 15, "15 在表内应保持 15");
        require(LuckyWheelMultiplierCatalog.floorOdd(5, 1001) == 1000, "1001 应下取 1000");
        require(LuckyWheelStake.ba(5).compareTo(BigDecimal.ONE) == 0, "押注金额必须是 1");
        require(LuckyWheelStake.cs(5).multiply(BigDecimal.valueOf(5)).compareTo(BigDecimal.ONE) == 0, "cs*bl 必须是 1");
        GameRound capped = new GameRuleCore(new SplittableRandom(430101L))
                .generateRound(new RoundRequest(5, 1, START), 1001);
        ResultAnalysis cappedWin = IndependentRoundVerifier.verify(capped, START);
        require(cappedWin.totalAward().compareTo(new BigDecimal("1000")) == 0, "1001 下取后实际=" + cappedWin.totalAward());
        require(capped.deliveries().get(0).result().ba().compareTo(BigDecimal.ONE) == 0, "ba 必须是 1");
        GameRuleCore core = new GameRuleCore(new SplittableRandom(430100L));
        BigDecimal balance = START;
        java.util.Set<String> fifteenBoards = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            GameRound fifteen = core.generateRound(new RoundRequest(5, 1, balance), 15);
            ResultAnalysis win15 = IndependentRoundVerifier.verify(fifteen, balance);
            require(win15.totalAward().compareTo(new BigDecimal("15")) == 0, "15 实际=" + win15.totalAward());
            List<String> board = fifteen.deliveries().get(0).result().rskl();
            if (fifteen.deliveries().get(0).result().md() == 0) {
                require(ResultUtil.independentScore(board).compareTo(new BigDecimal("15")) == 0,
                        "普通 15 倍盘面拼分错误 " + board);
            }
            fifteenBoards.add(board.toString());
        }
        require(fifteenBoards.size() > 1, "15 倍空白位置应随机: " + fifteenBoards);
        for (int bl : new int[]{1, 5, 10, 50, 100}) {
            require(LuckyWheelMultiplierCatalog.mode(bl) == (bl >= 50 ? 50 : bl), "模式映射错误 bl=" + bl);
            for (int i = 0; i < 50; i++) {
                GameRound zero = core.generateRound(new RoundRequest(bl, 1, balance), 0);
                ResultAnalysis loss = IndependentRoundVerifier.verify(zero, balance);
                require(loss.totalAward().signum() == 0, "目标 0 倍不是独立零奖");
                require(loss.outcome() == OutcomeType.ORDINARY_LOSS, "目标 0 倍不是普通零奖");
                int want = LuckyWheelMultiplierCatalog.floorOdd(bl, 15);
                GameRound hit = core.generateRound(new RoundRequest(bl, 1, balance), 15);
                ResultAnalysis win = IndependentRoundVerifier.verify(hit, balance);
                require(win.totalAward().compareTo(new BigDecimal(want)) == 0, "16 下取后实际=" + win.totalAward());
            }
        }
    }

    private static void verifyNaturalIndependentLoss() {
        GameRuleCore core = new GameRuleCore(new SplittableRandom(430044L));
        BigDecimal balance = START;
        for (int i = 0; i < 100000; i++) {
            GameRound round = core.generateIndependentLoss(new RoundRequest(1, 1, balance));
            ResultAnalysis analysis = IndependentRoundVerifier.verify(round, balance);
            require(analysis.outcome() == OutcomeType.ORDINARY_LOSS, "联合模型自然无奖反推失败");
            require(!analysis.continuationRequired(), "自然无奖不得带后续状态");
            balance = new BigDecimal(round.deliveries().get(0).result().pb());
        }
    }

    private static void verifyBetThresholdAndRedisCodec() {
        GameRuleCore core = new GameRuleCore(new SplittableRandom(430045L));
        expectUnsupported(() -> core.generateSs0ContinuationDisabled(new RoundRequest(1, 1, START)), "ss=0 未拒绝");
        MinimalFactCodec codec = new MinimalFactCodec();
        assertCodec(codec, RoundFacts.ordinary(List.of("H0", "H1")), "#", "0");
        assertCodec(codec, RoundFacts.multiplier(List.of("H4", "H1"), 5), "1541", "250");
        assertCodec(codec, RoundFacts.respin(List.of("H5", "H4"), List.of("H3", "H1")), "25431", "115");
        assertCodec(codec, RoundFacts.ordinary(5, List.of("H0", "H0", "H1")), "#", "0");
        assertCodec(codec, RoundFacts.respin(5, List.of("H5", "H0", "H1"), List.of("H3", "H4", "H1")),
                "52501341", "250");
        assertCodec(codec, RoundFacts.luckyWheel(List.of("H4", "H1", "H1"), 50), "53411050", "550");
        assertCodec(codec, RoundFacts.luckyWheel(List.of("H4", "H4", "H3"), 150), "53443150", "701");
    }

    private static void assertCodec(MinimalFactCodec codec, RoundFacts facts, String encoded, String award) {
        byte[] member = codec.encodeRedisMember(facts);
        require(new String(member, java.nio.charset.StandardCharsets.US_ASCII).equals(encoded), "member 编码错误");
        RoundFacts decoded = codec.decodeRedisMember(member, facts.betProfile());
        if (!"#".equals(encoded)) require(decoded.equals(facts), "member 解码事实不一致");
        else require(ResultUtil.analyze(decoded).outcome() == OutcomeType.ORDINARY_LOSS,
                "压缩 LOSS 物化后不是独立零奖");
        require(ResultUtil.analyze(decoded).totalAward().compareTo(new BigDecimal(award)) == 0,
                "member 独立反推派奖不一致");
    }

    private static void expectUnsupported(Runnable runnable, String message) {
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // 符合能力清单的安全拒绝。
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
