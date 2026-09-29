package com.cpgame.curupira.api;

import com.cpgame.curupira.core.GenerationScene;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 每个独立场景分别配置六档目标；只负责选局目标，不生成牌面。 */
@Component
public final class DemoSelectionPolicy {
    public record Band(int minInclusive, int maxInclusive, int weight) {
        public Band {
            if (minInclusive < 0 || maxInclusive < minInclusive || weight < 0) {
                throw new IllegalArgumentException("Invalid demo multiplier band");
            }
        }
    }

    private final Map<GenerationScene, List<Band>> bandsByScene;
    private final Map<GenerationScene, Integer> totals;
    private final RandomGenerator random;

    @Autowired
    public DemoSelectionPolicy(
            @Value("${curupira.demo.normal.band.0.weight}") int n0,
            @Value("${curupira.demo.normal.band.1.weight}") int n1,
            @Value("${curupira.demo.normal.band.2.weight}") int n2,
            @Value("${curupira.demo.normal.band.3.weight}") int n3,
            @Value("${curupira.demo.normal.band.4.weight}") int n4,
            @Value("${curupira.demo.normal.band.5.weight}") int n5,
            @Value("${curupira.demo.free-expanding-wild.band.0.weight}") int f0,
            @Value("${curupira.demo.free-expanding-wild.band.1.weight}") int f1,
            @Value("${curupira.demo.free-expanding-wild.band.2.weight}") int f2,
            @Value("${curupira.demo.free-expanding-wild.band.3.weight}") int f3,
            @Value("${curupira.demo.free-expanding-wild.band.4.weight}") int f4,
            @Value("${curupira.demo.free-expanding-wild.band.5.weight}") int f5,
            @Value("${curupira.demo.hold-and-spins.band.0.weight}") int h0,
            @Value("${curupira.demo.hold-and-spins.band.1.weight}") int h1,
            @Value("${curupira.demo.hold-and-spins.band.2.weight}") int h2,
            @Value("${curupira.demo.hold-and-spins.band.3.weight}") int h3,
            @Value("${curupira.demo.hold-and-spins.band.4.weight}") int h4,
            @Value("${curupira.demo.hold-and-spins.band.5.weight}") int h5) {
        this(new int[]{n0,n1,n2,n3,n4,n5}, new int[]{f0,f1,f2,f3,f4,f5},
                new int[]{h0,h1,h2,h3,h4,h5}, new SecureRandom());
    }

    DemoSelectionPolicy(int[] weights, RandomGenerator random) {
        this(weights, weights, weights, random);
    }

    DemoSelectionPolicy(int[] normal, int[] free, int[] hold, RandomGenerator random) {
        if (random == null) throw new IllegalArgumentException("Demo random source is required");
        EnumMap<GenerationScene, List<Band>> configured = new EnumMap<>(GenerationScene.class);
        configured.put(GenerationScene.NORMAL_PAID, bands(normal));
        configured.put(GenerationScene.FREE_EXPANDING_WILD, bands(free));
        configured.put(GenerationScene.HOLD_AND_SPINS, bands(hold));
        bandsByScene = Map.copyOf(configured);
        EnumMap<GenerationScene, Integer> sums = new EnumMap<>(GenerationScene.class);
        configured.forEach((scene, bands) -> {
            int total = bands.stream().mapToInt(Band::weight).sum();
            if (total <= 0) throw new IllegalArgumentException(scene + " requires a positive demo weight sum");
            sums.put(scene, total);
        });
        totals = Map.copyOf(sums);
        this.random = random;
    }

    public int chooseTargetMultiplier() {
        return chooseTargetMultiplier(GenerationScene.NORMAL_PAID);
    }

    public int chooseTargetMultiplier(GenerationScene scene) {
        List<Band> bands = bandsByScene.get(scene);
        if (bands == null) throw new IllegalArgumentException("Unknown demo scene: " + scene);
        int ticket = random.nextInt(totals.get(scene));
        for (Band band : bands) {
            if (ticket < band.weight()) {
                if (band.minInclusive() == band.maxInclusive()) return band.minInclusive();
                return (int) random.nextLong(band.minInclusive(), (long) band.maxInclusive() + 1L);
            }
            ticket -= band.weight();
        }
        throw new IllegalStateException("Demo band selection fell through");
    }

    List<Band> bands() { return bandsByScene.get(GenerationScene.NORMAL_PAID); }

    private static List<Band> bands(int[] weights) {
        if (weights == null || weights.length != 6) {
            throw new IllegalArgumentException("Exactly six demo weights are required per scene");
        }
        return List.of(
                new Band(0, 0, weights[0]),
                new Band(1, 125, weights[1]),
                new Band(126, 500, weights[2]),
                new Band(501, 1_250, weights[3]),
                new Band(1_251, 2_500, weights[4]),
                new Band(2_501, 250_000, weights[5]));
    }
}
