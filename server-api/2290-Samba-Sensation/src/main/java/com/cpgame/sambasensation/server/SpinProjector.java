package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.ResultUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Redis 完整局事实投影成原厂字段。所有中奖线、Wild 替代、金币奖励和倍率都调用共享 ResultUtil；本类不判奖。
 * 反推硬边界：Wild=0；Scatter=10；自然页按 bet_type 执行列/轴/页上限；购买页固定三轴合计30个；Free入口Scatter为0。
 * 上述边界已经由 GameRuleCore.validateStructure/BoardCaps 在 decode 与每次投影前强制复核。
 */
final class SpinProjector {
    static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.02"), new BigDecimal("0.04"), new BigDecimal("0.20"), new BigDecimal("0.40"));
    static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    static final int LINES = 25;
    static final int BUY_MULTIPLIER = 80;
    private static final BigDecimal TWENTY_FIVE = BigDecimal.valueOf(25);

    private SpinProjector() { }

    static boolean legalBet(BigDecimal bet, int level, int betType) {
        return BET_SIZES.stream().anyMatch(value -> value.compareTo(bet) == 0)
                && BET_LEVELS.contains(level) && betType >= 1 && betType <= 3;
    }

    static BigDecimal unitBetGold(BigDecimal bet, int level) {
        return money(bet.multiply(BigDecimal.valueOf(level)).multiply(TWENTY_FIVE));
    }

    static BigDecimal charge(BigDecimal bet, int level, int betType, boolean featureBuy) {
        BigDecimal unit = unitBetGold(bet, level);
        return money(unit.multiply(BigDecimal.valueOf(featureBuy ? BUY_MULTIPLIER : betType)));
    }

    static List<Delivery> project(GameRuleCore.CompleteRoundFact fact, BigDecimal bet, int level,
                                  boolean requestedFeatureBuy) {
        GameRuleCore.validateStructure(fact);
        if (requestedFeatureBuy != (fact.entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL)) {
            throw new IllegalStateException("Redis entry kind does not match request type");
        }
        List<Delivery> result = new ArrayList<>();
        BigDecimal cumulativeFree = BigDecimal.ZERO.setScale(2);
        for (int index = 0; index < fact.steps().size(); index++) {
            ResultUtil.StepEvaluation evaluation = ResultUtil.evaluateDelivery(fact, index);
            BigDecimal win = money(BigDecimal.valueOf(evaluation.multiplier()).multiply(bet)
                    .multiply(BigDecimal.valueOf(level)));
            // 原厂自然 Free 六步证据（spin-raw#line-20..25）从付费触发步就累计 frees.twa；
            // 购买触发步本身为0奖，所以同一写法也保持购买协议不变。这里仅投影共享 ResultUtil 的结果。
            cumulativeFree = money(cumulativeFree.add(win));
            result.add(new Delivery(index, fact.steps().get(index).boards(), evaluation, win, cumulativeFree));
        }
        int projectedMultiplier = result.stream().mapToInt(d -> d.evaluation().multiplier()).sum();
        int verifiedMultiplier = ResultUtil.evaluate(fact).multiplier();
        if (projectedMultiplier != verifiedMultiplier) throw new IllegalStateException("per-step projection differs from shared ResultUtil");
        return List.copyOf(result);
    }

    static ObjectNode responseData(ObjectMapper json, GameRuleCore.CompleteRoundFact fact, Delivery delivery,
                                   GameRuleCore.CollectionProjection collection,
                                   BigDecimal bet, int level, int requestType, BigDecimal charge,
                                   BigDecimal startBalance, BigDecimal endBalance, String oid, String parentOid) {
        boolean freeStep = delivery.index() > 0;
        BigDecimal unitBetGold = unitBetGold(bet, level);
        ObjectNode data = json.createObjectNode();
        data.put("bet", bet);
        // 免费响应不扣款，但原厂 bet_gold 仍回显基准下注；余额结算继续使用 charged=0。
        data.put("bet_gold", freeStep ? unitBetGold : charge);
        data.put("bet_type", fact.betType());
        data.put("big", 0);
        data.put("change_gold", money(delivery.win().subtract(freeStep ? BigDecimal.ZERO : charge)));
        data.put("end_gold", endBalance);
        data.put("level", level);
        data.put("odds", odds(delivery.evaluation().multiplier()));
        data.put("oid", oid);
        if (freeStep) data.put("forder_id", parentOid);
        data.set("props", props(json, fact, delivery, collection, bet, level));
        data.put("small_game_type", freeStep ? 2 : 0);
        data.put("start_gold", startBalance);
        data.put("total_win", delivery.win());
        data.put("type", requestType);
        data.set("frees", frees(json, fact, delivery, collection, bet, level));
        return data;
    }

    static ObjectNode idleData(ObjectMapper json, GameRuleCore.CompleteRoundFact fact,
                               GameRuleCore.CollectionState state, BigDecimal balance, BigDecimal bet, int level, String oid) {
        ResultUtil.StepEvaluation evaluation = ResultUtil.evaluateDelivery(fact, 0);
        Delivery idle = new Delivery(0, fact.steps().get(0).boards(), evaluation,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        GameRuleCore.CollectionProjection collection = new GameRuleCore.CollectionProjection(state.scatterProgress(),
                state.coins(), false, GameRuleCore.CollectionBranch.UNCHANGED, state);
        ObjectNode data = responseData(json, fact, idle, collection, bet, level, 1, BigDecimal.ZERO.setScale(2),
                balance, balance, oid, oid);
        data.put("bet_gold", charge(bet, level, fact.betType(), false));
        data.put("change_gold", 0);
        data.put("odds", 0);
        data.put("total_win", 0);
        ObjectNode winArr = (ObjectNode) data.path("props").path("win_arr");
        winArr.removeAll();
        for (int axis = 1; axis <= fact.steps().get(0).boards().size(); axis++) winArr.putArray(Integer.toString(axis));
        return data;
    }

    private static ObjectNode props(ObjectMapper json, GameRuleCore.CompleteRoundFact fact, Delivery delivery,
                                    GameRuleCore.CollectionProjection collection,
                                    BigDecimal bet, int level) {
        ObjectNode props = json.createObjectNode();
        props.put("bet_gold", unitBetGold(bet, level));
        ObjectNode coins = props.putObject("coins");
        ArrayNode coinValues = coins.putArray("coins");
        for (int value : collection.coins()) coinValues.add(value);
        coins.put("count", GameRuleCore.coinCount(collection.coins()));
        coins.put("is_full", collection.fullReward());
        props.put("is_free", delivery.index() > 0 ? 1 : 0);
        ArrayNode boards = props.putArray("props");
        for (int[] board : delivery.boards()) {
            ArrayNode values = boards.addArray();
            for (int symbol : board) values.add(symbol);
        }
        // 原厂购买(type=3)的触发页和随后五个type=2响应均固定回传scatter=25；
        // 这是启动原页面Free状态机的协议游标，不是判奖或重新发牌。购买盘30个Scatter的
        // 轴/整页硬上限仍由共享GameRuleCore.BoardCaps校验，Controller不得据此再判一次奖。
        props.put("scatter", fact.entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL
                ? 25 : collection.scatterProgress());
        ObjectNode winArr = props.putObject("win_arr");
        for (int axis = 1; axis <= delivery.boards().size(); axis++) winArr.putArray(Integer.toString(axis));
        for (ResultUtil.LineWin win : delivery.evaluation().wins()) {
            ObjectNode line = ((ArrayNode) winArr.path(Integer.toString(win.axis()))).addObject();
            line.put("count", win.count());
            line.put("line", win.line());
            line.put("pay_out", ratio25(win.multiplier()));
            line.put("reward", money(BigDecimal.valueOf(win.multiplier()).multiply(bet)
                    .multiply(BigDecimal.valueOf(level))));
            line.put("symbol", win.symbol());
        }
        return props;
    }

    private static ObjectNode frees(ObjectMapper json, GameRuleCore.CompleteRoundFact fact, Delivery delivery,
                                    GameRuleCore.CollectionProjection collection,
                                    BigDecimal bet, int level) {
        ObjectNode frees = json.createObjectNode();
        ArrayNode coinValues = frees.putArray("coins");
        for (int value : collection.coins()) coinValues.add(value);
        if (fact.steps().size() == 6) {
            frees.put("0", 1);
            frees.put("ba", unitBetGold(bet, level));
            frees.put("bet", bet);
            frees.put("l", level);
            // 自然触发步可能同时中奖，原厂 frees.m 必须回显该步倍率，不能按 index=0 强制清零。
            frees.put("m", odds(delivery.evaluation().multiplier()));
            frees.put("spe_num", LINES);
            // 起点明确返回5；五个 type=2 响应依次为4、3、2、1、0，终态绝不省略。
            frees.put("st", delivery.index() == 0 ? GameRuleCore.FREE_STEPS : GameRuleCore.FREE_STEPS - delivery.index());
            frees.put("tt", GameRuleCore.FREE_STEPS);
            frees.put("twa", delivery.cumulativeFree());
        }
        return frees;
    }

    private static BigDecimal odds(int multiplier) { return ratio25(multiplier); }
    private static BigDecimal ratio25(int multiplier) {
        return BigDecimal.valueOf(multiplier).divide(TWENTY_FIVE, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    record Delivery(int index, List<int[]> boards, ResultUtil.StepEvaluation evaluation,
                    BigDecimal win, BigDecimal cumulativeFree) {
        Delivery {
            List<int[]> copied = new ArrayList<>();
            for (int[] board : boards) copied.add(board.clone());
            boards = List.copyOf(copied);
        }
        @Override public List<int[]> boards() {
            List<int[]> copied = new ArrayList<>();
            for (int[] board : boards) copied.add(board.clone());
            return List.copyOf(copied);
        }
    }
}
