package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundMode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** 从训练集完整联合 kernel 抽样整局。不逐格抽符号，也不拼接未中奖盘。 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/saci-joint-kernels.txt";
    private static final Model MODEL = loadModel();

    public RoundCandidate independentLoss(RandomGenerator random) {
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
        return choose(MODEL.byMode.get(RoundMode.ORDINARY_LOSS), random);
    }

    private static boolean isIndependentLoss(RoundCandidate c) {
        return c.mode() == RoundMode.ORDINARY_LOSS && c.steps().size() == 1 && ResultUtil.scatterCount(c.steps().get(0).rskl()) < 3 && ResultUtil.expectedWa(c.steps().get(0).rskl(), 1, new java.math.BigDecimal("0.02")).signum() == 0;
    }

    private static final List<RoundCandidate> DEFAULT_LOSSES = createLossDefaults();
    private static List<RoundCandidate> createLossDefaults() {
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (RoundCandidate c : MODEL.byMode.get(RoundMode.ORDINARY_LOSS)) {
            if (!isIndependentLoss(c)) throw new ExceptionInInitializerError("invalid LOSS model entry");
            if (defaults.size() < 10) defaults.add(c);
        }
        if (defaults.size() != 10) throw new ExceptionInInitializerError("ten default losses required");
        return List.copyOf(defaults);
    }

    public RoundCandidate ordinaryWin(RandomGenerator random) {
        return choose(MODEL.byMode.get(RoundMode.ORDINARY_WIN), random);
    }

    public RoundCandidate special(RandomGenerator random) {
        List<RoundCandidate> pool = new ArrayList<>();
        pool.addAll(MODEL.byMode.get(RoundMode.FREE_SPINS));
        pool.addAll(MODEL.byMode.get(RoundMode.WILD_VORTEX));
        return choose(pool, random);
    }

    public RoundCandidate natural(RandomGenerator random) {
        return choose(MODEL.all, random);
    }

    public int trainingKernelCount() {
        return MODEL.all.size();
    }

    public int count(RoundMode mode) {
        return MODEL.byMode.get(mode).size();
    }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            RoundCandidate loss = independentLossCandidate(random);
            if (loss.mode() != RoundMode.ORDINARY_LOSS) return 0.0d;
            if (ResultUtil.expectedWa(loss.steps().get(0).rskl(), 1, new java.math.BigDecimal("0.02")).signum() != 0) {
                return 0.0d;
            }
        }
        return 1.0d;
    }

    private static RoundCandidate choose(List<RoundCandidate> values, RandomGenerator random) {
        if (values == null || values.isEmpty()) throw new IllegalStateException("训练模型缺少所需完整局类别");
        return values.get(random.nextInt(values.size()));
    }

    private static Model loadModel() {
        Map<RoundMode, List<RoundCandidate>> byMode = new EnumMap<>(RoundMode.class);
        for (RoundMode mode : RoundMode.values()) byMode.put(mode, new ArrayList<>());
        List<RoundCandidate> all = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                RandomCandidateGenerator.class.getResourceAsStream(MODEL_RESOURCE), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                RoundCandidate candidate = KernelCodec.decodeCandidate(line);
                byMode.get(candidate.mode()).add(candidate);
                all.add(candidate);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法加载 Saci 联合 kernel", ex);
        }
        if (all.isEmpty()) throw new IllegalStateException("训练模型为空");
        return new Model(all, byMode);
    }

    private record Model(List<RoundCandidate> all, Map<RoundMode, List<RoundCandidate>> byMode) {}
}
