package com.cpgame.crazybirds.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** 显式样本基础权重与“中性 -> 逐牌单独放大 -> 复位”的批次相位。 */
public final class GenerationWeights {
    private final String source;
    private final List<String> order;
    private final long[] paidBase;
    private final long[] freeBase;
    private final int[] boosts;

    public GenerationWeights(Properties properties) {
        source = required(properties, "weights.source");
        order = parseOrder(required(properties, "weights.symbol-order"));
        paidBase = new long[order.size()];
        freeBase = new long[order.size()];
        boosts = new int[order.size()];
        for (int i = 0; i < order.size(); i++) {
            String symbol = order.get(i);
            paidBase[i] = nonNegativeLong(properties, "weights.base.paid." + symbol);
            freeBase[i] = nonNegativeLong(properties, "weights.base.free." + symbol);
            boosts[i] = positiveInt(properties, "weights.boost." + symbol);
        }
        requirePositiveStage("paid", paidBase);
        requirePositiveStage("free", freeBase);
    }

    public String source() { return source; }
    public List<String> symbolOrder() { return order; }
    public int phaseCount() { return order.size() + 1; }

    public BatchWeights forBatch(long batchIndex) {
        if (batchIndex < 0) throw new IllegalArgumentException("batchIndex 不能为负数");
        int phase = (int) (batchIndex % phaseCount());
        int boostedIndex = phase == 0 ? -1 : phase - 1;
        long[] paid = paidBase.clone();
        long[] free = freeBase.clone();
        if (boostedIndex >= 0) {
            paid[boostedIndex] = Math.multiplyExact(paid[boostedIndex], boosts[boostedIndex]);
            free[boostedIndex] = Math.multiplyExact(free[boostedIndex], boosts[boostedIndex]);
        }
        return new BatchWeights(batchIndex, phase,
                boostedIndex < 0 ? null : order.get(boostedIndex), order, paid, free);
    }

    public record BatchWeights(long batchIndex, int phaseIndex, String boostedSymbol,
                               List<String> order, long[] paid, long[] free) {
        public BatchWeights {
            order = List.copyOf(order);
            paid = paid.clone();
            free = free.clone();
        }

        @Override public long[] paid() { return paid.clone(); }
        @Override public long[] free() { return free.clone(); }

        public String draw(boolean freeStep, Predicate<String> allowed, RandomGenerator random) {
            long[] weights = freeStep ? free : paid;
            long total = 0;
            for (int i = 0; i < order.size(); i++) {
                if (allowed.test(order.get(i))) total = Math.addExact(total, weights[i]);
            }
            if (total <= 0) throw new IllegalArgumentException("当前阶段/位置没有可抽取的正权重符号");
            long point = random.nextLong(total);
            for (int i = 0; i < order.size(); i++) {
                if (!allowed.test(order.get(i))) continue;
                long weight = weights[i];
                if (point < weight) return order.get(i);
                point -= weight;
            }
            throw new IllegalStateException("权重抽样越界");
        }
    }

    private static List<String> parseOrder(String raw) {
        List<String> parsed = new ArrayList<>();
        for (String value : raw.split(",", -1)) {
            String symbol = value.trim();
            if (symbol.isEmpty()) throw new IllegalArgumentException("weights.symbol-order 含空符号");
            parsed.add(symbol);
        }
        Set<String> unique = new HashSet<>(parsed);
        if (unique.size() != parsed.size() || !unique.equals(new HashSet<>(GameRules.ALL_SYMBOLS))) {
            throw new IllegalArgumentException("weights.symbol-order 必须且只能包含当前游戏全部符号");
        }
        return List.copyOf(parsed);
    }

    private static void requirePositiveStage(String stage, long[] values) {
        long sum = 0;
        for (long value : values) sum = Math.addExact(sum, value);
        if (sum <= 0) throw new IllegalArgumentException("weights.base." + stage + " 总权重必须大于0");
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static long nonNegativeLong(Properties p, String key) {
        long value;
        try { value = Long.parseLong(required(p, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须为非负整数", e); }
        if (value < 0) throw new IllegalArgumentException(key + " 必须为非负整数");
        return value;
    }

    private static int positiveInt(Properties p, String key) {
        int value;
        try { value = Integer.parseInt(required(p, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须为正整数", e); }
        if (value <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
    }
}
