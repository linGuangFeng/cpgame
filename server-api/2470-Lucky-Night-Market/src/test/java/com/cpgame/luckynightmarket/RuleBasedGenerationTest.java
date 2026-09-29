package com.cpgame.luckynightmarket;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 正式算法的规则、一次尝试语义和牌面多样性验证；不连接 Redis。 */
public final class RuleBasedGenerationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("range.normal-min", "0");
        properties.setProperty("range.normal-max", "16000");
        properties.setProperty("range.special-min", "0");
        properties.setProperty("range.special-max", "16000");
        DealingModel model = new DealingModel(properties);
        require(((Number) model.metadata().get("sampleBoardCandidates")).intValue() == 0, "运行时不得把样本牌面作为候选");
        require(((Number) model.metadata().get("sampleColumnCandidates")).intValue() == 0, "运行时不得把样本整列作为候选");
        require(((Number) model.metadata().get("sampleWinningCombinations")).intValue() == 0, "运行时不得把样本中奖组合作为候选");
        require(((Number) model.metadata().get("sampleMultiplierTriples")).intValue() == 0, "运行时不得从样本倍率三元组抽取");

        Set<List<Integer>> ordinaryBoards = new HashSet<>();
        Set<List<Integer>> ordinaryMultiplierTriples = new HashSet<>();
        Set<Long> ordinaryPayouts = new HashSet<>();
        Map<String, Integer> ordinaryModes = new TreeMap<>();
        int[][] ordinaryPositionSymbols = new int[9][7];
        for (int i = 0; i < 20000; i++) {
            DealingModel.Attempt attempt = model.attemptScenario(DealingModel.Scenario.ORDINARY, false);
            require(attempt.accepted(), "普通规则候选应直接完成");
            RoundFact round = attempt.round();verify(round);
            ordinaryModes.merge(round.mode().name(), 1, Integer::sum);
            RoundFact.Step step = round.steps().get(0);
            ordinaryBoards.add(step.ps());ordinaryMultiplierTriples.add(step.muls());ordinaryPayouts.add(attempt.units());
            for (int position = 0; position < 9; position++) ordinaryPositionSymbols[position][step.ps().get(position)]++;
        }
        require(ordinaryModes.containsKey("ORDINARY_LOSS") && ordinaryModes.containsKey("ORDINARY_WIN"), "普通牌面必须先结算再自然分为输赢");
        require(ordinaryBoards.size() > 19000, "普通牌面必须真正随机生成");
        require(ordinaryMultiplierTriples.size() > 150, "倍率三元组不得局限于样本组合");
        require(ordinaryPayouts.size() > 40, "普通实际倍率覆盖不足");
        allSymbolsAtEveryPosition(ordinaryPositionSymbols, "普通");

        // 样本观察到的 WILD 上限不是规则：在 WILD 强化批次验证生成空间没有被该样本上限截断。
        model.useBatch(1);
        boolean moreThanFiveWilds = false, threeWildsInAColumn = false;
        for (int i = 0; i < 50000 && !(moreThanFiveWilds && threeWildsInAColumn); i++) {
            RoundFact.Step step = model.attemptScenario(DealingModel.Scenario.ORDINARY, false).round().steps().get(0);
            long wilds = step.ps().stream().filter(symbol -> symbol == 0).count();
            if (wilds > 5) moreThanFiveWilds = true;
            for (int column = 0; column < 3; column++) {
                if (step.ps().get(column * 3) == 0 && step.ps().get(column * 3 + 1) == 0 && step.ps().get(column * 3 + 2) == 0) {
                    threeWildsInAColumn = true;
                }
            }
        }
        require(moreThanFiveWilds, "不得把样本普通 WILD 最大值 5 当规则上限");
        require(threeWildsInAColumn, "不得把样本列 WILD 最大值当规则上限");
        model.useBatch(0);

        Set<List<Integer>> wheelBoards = new HashSet<>();
        Set<List<Integer>> wheelMultiplierPairs = new HashSet<>();
        Set<Long> wheelPayouts = new HashSet<>();
        Set<Integer> wheelPrizes = new HashSet<>();
        int[][] wheelPositionSymbols = new int[9][7];
        for (int i = 0; i < 12000; i++) {
            DealingModel.Attempt attempt = model.attemptScenario(DealingModel.Scenario.LUCKY_WHEEL, false);
            require(attempt.accepted(), "转盘规则候选应直接完成");
            RoundFact round = attempt.round();verify(round);
            RoundFact.Step step = round.steps().get(0);
            wheelBoards.add(step.ps());wheelMultiplierPairs.add(step.muls());wheelPayouts.add(attempt.units());wheelPrizes.add(step.wheelMultiplier());
            for (int position = 0; position < 9; position++) wheelPositionSymbols[position][step.ps().get(position)]++;
        }
        require(wheelBoards.size() > 11500, "转盘牌面不得局限于两个样本组合");
        require(wheelMultiplierPairs.size() > 30, "转盘两侧倍率不得局限于样本组合");
        require(wheelPayouts.size() > 40, "转盘实际倍率不得退化成四档");
        require(wheelPrizes.equals(Arrays.stream(RuleBasedBoardGenerator.WHEEL_PRIZES).boxed().collect(Collectors.toSet())), "转盘必须覆盖帮助页规则奖项");
        allSymbolsAtEveryPosition(wheelPositionSymbols, "转盘");

        Set<String> featureFacts = new HashSet<>();
        Set<List<Integer>> featureBoards = new HashSet<>();
        Set<Long> featurePayouts = new HashSet<>();
        Map<String, Integer> featureRejects = new TreeMap<>();
        int featureAttempts = 5000, featureValid = 0;
        for (int i = 0; i < featureAttempts; i++) {
            DealingModel.Attempt attempt = model.attemptScenario(DealingModel.Scenario.LUCKY_FEATURE, false);
            if (!attempt.accepted()) { featureRejects.merge(attempt.rejectionReason(), 1, Integer::sum);continue; }
            featureValid++;RoundFact round = attempt.round();verify(round);
            require(round.steps().size() == 8, "玛丽固定八步");
            require(ResultUtil.evaluate(round.steps().get(0), true).units() == 0, "玛丽首步按规则必须零奖");
            featureFacts.add(RoundCodec.encodeFull(round));featurePayouts.add(attempt.units());
            for (RoundFact.Step step : round.steps()) featureBoards.add(step.ps());
        }
        require(featureValid > 3000, "玛丽有效随机局过少");
        require(featureRejects.getOrDefault("FEATURE_START_NOT_ZERO", 0) > 0, "玛丽首步自然中奖候选必须整局拒绝且不重抽");
        require(featureFacts.size() == featureValid, "玛丽完整事实不得由有限样本模板重复");
        require(featureBoards.size() > 20000, "玛丽步骤牌面必须真正随机生成");
        require(featurePayouts.size() > 100, "玛丽完整局实际倍率覆盖不足");

        long before = model.attemptedRounds();
        model.attemptScenario(DealingModel.Scenario.LUCKY_FEATURE, false);
        require(model.attemptedRounds() == before + 1, "一个生成调用只能计一次尝试，失败不得在内部补抽");

        System.out.println(Json.stringify(Json.map(
                "status", "PASS",
                "checks", checks,
                "generator", "weighted-symbol-random-facts-then-settlement",
                "ordinaryAttempts", 20000,
                "ordinaryModes", ordinaryModes,
                "ordinaryDistinctBoards", ordinaryBoards.size(),
                "ordinaryDistinctMultiplierTriples", ordinaryMultiplierTriples.size(),
                "ordinaryDistinctPayouts", ordinaryPayouts.size(),
                "sampleWildCapsNotApplied", moreThanFiveWilds && threeWildsInAColumn,
                "wheelAttempts", 12000,
                "wheelDistinctBoards", wheelBoards.size(),
                "wheelDistinctMultiplierPairs", wheelMultiplierPairs.size(),
                "wheelDistinctPayouts", wheelPayouts.size(),
                "wheelPrizes", wheelPrizes,
                "featureAttempts", featureAttempts,
                "featureValid", featureValid,
                "featureRejected", featureAttempts - featureValid,
                "featureRejectReasons", featureRejects,
                "featureDistinctCompleteFacts", featureFacts.size(),
                "featureDistinctStepBoards", featureBoards.size(),
                "featureDistinctPayouts", featurePayouts.size(),
                "redisWrites", 0)));
    }

    private static void allSymbolsAtEveryPosition(int[][] counts, String scene) {
        for (int position = 0; position < 9; position++) {
            for (int symbol = 0; symbol < 7; symbol++) {
                require(counts[position][symbol] > 0, scene + "位置 " + position + " 未生成符号 " + symbol);
            }
        }
    }

    private static void verify(RoundFact round) {
        checks++;
        GameRuleCore.validate(round);
        if (GameRuleCore.totalUnits(round) != ResultUtil.totalUnits(round)) throw new AssertionError("规则核心与独立结算不一致");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
