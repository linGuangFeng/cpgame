package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundCandidate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 中奖/轮盘仍从训练联合 kernel 抽样。独立 LOSS 按五条线构造：每条线第三格与前两格拆开。
 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/crazy-piggy-joint-kernels.txt";
    private static final Model MODEL = loadModel();
    private static final GenerationWeights OBSERVED = GenerationWeights.defaults();
    private final GenerationWeights weights;
    private final WeightedPool losses;
    private final WeightedPool wins;
    private final WeightedPool boosters;

    public RandomCandidateGenerator() { this(GenerationWeights.defaults()); }
    public RandomCandidateGenerator(GenerationWeights weights) {
        this.weights = weights;
        losses = pool(MODEL.losses, weights.symbolWeights(), OBSERVED.symbolWeights());
        wins = pool(MODEL.wins, weights.symbolWeights(), OBSERVED.symbolWeights());
        boosters = pool(MODEL.boosters, weights.boosterSymbolWeights(), OBSERVED.boosterSymbolWeights());
    }

    public RoundCandidate natural(RandomGenerator random) {
        String mode = weights.choose(random, weights.modeWeights());
        if (GenerationWeights.BOOSTER_WHEEL.equals(mode)) return boosterWheel(random);
        int ordinaryTotal = MODEL.losses.size() + MODEL.wins.size();
        int pick = random.nextInt(ordinaryTotal);
        return transform(pick < MODEL.losses.size() ? losses.choose(random) : wins.choose(random), random);
    }

    public RoundCandidate independentLoss(RandomGenerator random, LossGenerationPolicy ignored) {
        return new IndependentLossGenerator().generate(random);
    }
    public RoundCandidate independentLossCandidate(RandomGenerator random) {
        return new IndependentLossGenerator().candidate(random);
    }

    public RoundCandidate ordinaryWin(RandomGenerator random) {
        return transform(wins.choose(random), random);
    }

    public RoundCandidate boosterWheel(RandomGenerator random) {
        return transform(boosters.choose(random), random);
    }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            if (!ResultUtil.evaluateLines(independentLossCandidate(random).symbols()).isEmpty())
                return 0.0d;
        }
        return 1.0d;
    }

    public int trainingKernelCount() { return MODEL.losses.size() + MODEL.wins.size() + MODEL.boosters.size(); }

    private static WeightedPool pool(List<RoundCandidate> values, Map<String, Integer> configured,
                                     Map<String, Integer> observed) {
        double[] logs = new double[values.size()];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.size(); i++) {
            double value = 0;
            for (String symbol : values.get(i).symbols())
                value += Math.log((double) configured.get(symbol) / observed.get(symbol));
            logs[i] = value;
            max = Math.max(max, value);
        }
        double[] cumulative = new double[logs.length];
        double total = 0;
        for (int i = 0; i < logs.length; i++) cumulative[i] = total += Math.exp(logs[i] - max);
        return new WeightedPool(values, cumulative, total);
    }

    private record WeightedPool(List<RoundCandidate> values, double[] cumulative, double total) {
        RoundCandidate choose(RandomGenerator random) {
            int index = Arrays.binarySearch(cumulative, random.nextDouble(total));
            if (index < 0) index = -index - 1;
            return values.get(Math.min(index, values.size() - 1));
        }
    }

    /** 证据确认的上下镜像保持五条线集合不变，同时生成未逐格拼接的新联合状态。 */
    private static RoundCandidate transform(RoundCandidate source, RandomGenerator random) {
        if (!random.nextBoolean()) return source;
        List<String> s = source.symbols();
        List<String> reflected = List.of(s.get(2), s.get(1), s.get(0),
                s.get(5), s.get(4), s.get(3), s.get(8), s.get(7), s.get(6));
        return new RoundCandidate(reflected, source.wheelPositions(), source.wheelMultipliers());
    }

    private static Model loadModel() {
        List<RoundCandidate> losses = new ArrayList<>(), wins = new ArrayList<>(), boosters = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                RandomCandidateGenerator.class.getResourceAsStream(MODEL_RESOURCE), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length != 4) throw new IllegalArgumentException("联合模型行字段数错误");
                List<String> board = List.of(fields[1].split(","));
                List<Integer> positions = ints(fields[2]);
                List<Integer> multipliers = ints(fields[3]);
                RoundCandidate candidate = new RoundCandidate(board, positions, multipliers);
                switch (fields[0]) {
                    case "L" -> losses.add(candidate);
                    case "W" -> wins.add(candidate);
                    case "B" -> boosters.add(candidate);
                    default -> throw new IllegalArgumentException("联合模型类别错误: " + fields[0]);
                }
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
        if (losses.size() < 1000 || wins.size() < 100 || boosters.size() < 30)
            throw new ExceptionInInitializerError("联合模型训练样本不足");
        return new Model(List.copyOf(losses), List.copyOf(wins), List.copyOf(boosters));
    }

    private static List<Integer> ints(String value) {
        if (value.isEmpty()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String item : value.split(",")) result.add(Integer.parseInt(item));
        return Collections.unmodifiableList(result);
    }

    private record Model(List<RoundCandidate> losses, List<RoundCandidate> wins,
                         List<RoundCandidate> boosters) {}
}
