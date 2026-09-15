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
            int betLevel = (i & 1) == 0 ? 1 : 5;
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
        assertCodec(codec, RoundFacts.ordinary(List.of("H0", "H1")), "001", "0");
        assertCodec(codec, RoundFacts.multiplier(List.of("H4", "H1"), 5), "1541", "250");
        assertCodec(codec, RoundFacts.respin(List.of("H5", "H4"), List.of("H3", "H1")), "25431", "115");
        assertCodec(codec, RoundFacts.ordinary(5, List.of("H0", "H0", "H1")), "50001", "0");
        assertCodec(codec, RoundFacts.respin(5, List.of("H5", "H0", "H1"), List.of("H3", "H4", "H1")),
                "52501341", "250");
        assertCodec(codec, RoundFacts.luckyWheel(List.of("H4", "H1", "H1"), 50), "53411050", "550");
        assertCodec(codec, RoundFacts.luckyWheel(List.of("H4", "H4", "H3"), 150), "53443150", "701");
    }

    private static void assertCodec(MinimalFactCodec codec, RoundFacts facts, String encoded, String award) {
        byte[] member = codec.encodeRedisMember(facts);
        require(new String(member, java.nio.charset.StandardCharsets.US_ASCII).equals(encoded), "member 编码错误");
        RoundFacts decoded = codec.decodeRedisMember(member);
        require(decoded.equals(facts), "member 解码事实不一致");
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
