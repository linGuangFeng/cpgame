package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundCandidate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 从真实训练集拟合的完整联合状态 kernel 抽样。这里不逐格抽符号，也不构造 LOSS；
 * 一次选择得到完整 3x3 牌面，轮盘也一次选择完整相邻序列。
 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/crazy-piggy-joint-kernels.txt";
    private static final Model MODEL = loadModel();
    private final GenerationWeights weights;

    public RandomCandidateGenerator() { this(GenerationWeights.defaults()); }
    public RandomCandidateGenerator(GenerationWeights weights) { this.weights = weights; }

    public RoundCandidate natural(RandomGenerator random) {
        String mode = weights.choose(random, weights.modeWeights());
        if (GenerationWeights.BOOSTER_WHEEL.equals(mode)) return boosterWheel(random);
        int ordinaryTotal = MODEL.losses.size() + MODEL.wins.size();
        int pick = random.nextInt(ordinaryTotal);
        return transform(pick < MODEL.losses.size() ? MODEL.losses.get(pick)
                : MODEL.wins.get(pick - MODEL.losses.size()), random);
    }

    public RoundCandidate independentLoss(RandomGenerator random, LossGenerationPolicy ignored) {
        return generateLossWithCandidates(random, () -> independentLossCandidate(random));
    }
    RoundCandidate generateLossWithCandidates(RandomGenerator random, java.util.function.Supplier<RoundCandidate> proposals) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RoundCandidate candidate = proposals.get();
            if (candidate != null && isIndependentLoss(candidate)) return candidate;
        }
        return DEFAULT_LOSSES.get(random.nextInt(10));
    }

    public RoundCandidate independentLossCandidate(RandomGenerator random) {
        return transform(choose(MODEL.losses, random), random);
    }

    private static boolean isIndependentLoss(RoundCandidate c) {
        return c.wheelPositions().isEmpty() && c.wheelMultipliers().isEmpty() && ResultUtil.evaluateLines(c.symbols()).isEmpty();
    }

    private static final List<RoundCandidate> DEFAULT_LOSSES = createLossDefaults();
    private static List<RoundCandidate> createLossDefaults() {
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (RoundCandidate c : MODEL.losses) {
            if (!isIndependentLoss(c)) throw new ExceptionInInitializerError("invalid LOSS model entry");
            if (defaults.size() < 10) defaults.add(c);
        }
        if (defaults.size() != 10) throw new ExceptionInInitializerError("ten default losses required");
        return List.copyOf(defaults);
    }

    public RoundCandidate ordinaryWin(RandomGenerator random) {
        return transform(choose(MODEL.wins, random), random);
    }

    public RoundCandidate boosterWheel(RandomGenerator random) {
        return transform(choose(MODEL.boosters, random), random);
    }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            if (!ResultUtil.evaluateLines(independentLossCandidate(random).symbols()).isEmpty())
                return 0.0d;
        }
        return 1.0d;
    }

    public int trainingKernelCount() { return MODEL.losses.size() + MODEL.wins.size() + MODEL.boosters.size(); }

    private static RoundCandidate choose(List<RoundCandidate> values, RandomGenerator random) {
        if (values.isEmpty()) throw new IllegalStateException("训练模型缺少所需完整局类别");
        return values.get(random.nextInt(values.size()));
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
