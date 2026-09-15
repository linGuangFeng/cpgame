package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Samples complete joint states from captured training rounds.
 * Does not draw symbols cell-by-cell and does not stitch loss boards.
 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/lucky-cat-ii-joint-kernels.txt";
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
        return transform(choose(MODEL.losses, random), random);
    }

    private static boolean isIndependentLoss(RoundCandidate c) {
        return !c.luckyRespin() && c.rpx() == 1 && c.paidBoard().equals(c.finalBoard()) && !ResultUtil.isWheelBoard(c.finalBoard()) && ResultUtil.findLuckyTrigger(c.paidBoard()) == null && ResultUtil.evaluatePaylines(c.finalBoard()).isEmpty();
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

    public RoundCandidate special(RandomGenerator random) {
        return transform(choose(MODEL.specials, random), random);
    }

    public RoundCandidate luckyRespin(RandomGenerator random) {
        return transform(choose(MODEL.lucky, random), random);
    }

    public RoundCandidate multiplierWheel(RandomGenerator random) {
        return transform(choose(MODEL.wheels, random), random);
    }

    public int trainingKernelCount() {
        return MODEL.losses.size() + MODEL.wins.size() + MODEL.specials.size();
    }

    public int lossCount() { return MODEL.losses.size(); }
    public int winCount() { return MODEL.wins.size(); }
    public int specialCount() { return MODEL.specials.size(); }
    public int luckyCount() { return MODEL.lucky.size(); }
    public int wheelCount() { return MODEL.wheels.size(); }

    private static RoundCandidate choose(List<RoundCandidate> values, RandomGenerator random) {
        if (values.isEmpty()) throw new IllegalStateException("训练模型缺少所需完整局类别");
        return values.get(random.nextInt(values.size()));
    }

    /** Left-right reel swap preserves prefix-WILD stacks when the classifier rdri stays the changed reel. */
    static RoundCandidate transform(RoundCandidate source, RandomGenerator random) {
        if (!random.nextBoolean()) return source;
        RoundCandidate swapped = new RoundCandidate(swapOuterReels(source.paidBoard()),
                swapOuterReels(source.finalBoard()), source.rpx(), source.luckyRespin());
        if (!source.luckyRespin()) return swapped;
        ResultUtil.LuckyTrigger trigger = ResultUtil.findLuckyTrigger(swapped.paidBoard());
        if (trigger == null) return source;
        int rdri = trigger.respinReelIndex();
        for (int reel = 0; reel < 3; reel++) {
            if (reel == rdri) continue;
            if (!ResultUtil.reel(swapped.paidBoard(), reel).equals(ResultUtil.reel(swapped.finalBoard(), reel)))
                return source;
        }
        if (!swapped.paidBoard().equals(swapped.finalBoard())
                && ResultUtil.reel(swapped.paidBoard(), rdri).equals(ResultUtil.reel(swapped.finalBoard(), rdri)))
            return source;
        return swapped;
    }

    static List<String> swapOuterReels(List<String> board) {
        return List.of(
                board.get(6), board.get(7), board.get(8),
                board.get(3), board.get(4), board.get(5),
                board.get(0), board.get(1), board.get(2));
    }

    private static Model loadModel() {
        List<RoundCandidate> losses = new ArrayList<>();
        List<RoundCandidate> wins = new ArrayList<>();
        List<RoundCandidate> specials = new ArrayList<>();
        List<RoundCandidate> lucky = new ArrayList<>();
        List<RoundCandidate> wheels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                RandomCandidateGenerator.class.getResourceAsStream(MODEL_RESOURCE), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length != 4) throw new IllegalArgumentException("联合模型行字段数错误");
                List<String> s01 = List.of(fields[2].split(",", -1));
                List<String> s02 = fields[3].isEmpty() ? s01 : List.of(fields[3].split(",", -1));
                int rpx = Integer.parseInt(fields[1]);
                boolean luckyRespin = "R".equals(fields[0]) || "K".equals(fields[0]);
                RoundCandidate candidate = new RoundCandidate(s01, s02, rpx, luckyRespin);
                switch (fields[0]) {
                    case "L" -> losses.add(candidate);
                    case "W" -> wins.add(candidate);
                    case "R" -> {
                        lucky.add(candidate);
                        specials.add(candidate);
                    }
                    case "M" -> {
                        wheels.add(candidate);
                        specials.add(candidate);
                    }
                    case "K" -> {
                        lucky.add(candidate);
                        wheels.add(candidate);
                        specials.add(candidate);
                    }
                    default -> throw new IllegalArgumentException("联合模型类别错误: " + fields[0]);
                }
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
        if (losses.size() < 1000 || wins.size() < 100 || specials.size() < 30 || lucky.size() < 30 || wheels.size() < 30) {
            throw new ExceptionInInitializerError("联合模型训练样本不足 L=" + losses.size()
                    + " W=" + wins.size() + " S=" + specials.size());
        }
        return new Model(List.copyOf(losses), List.copyOf(wins), List.copyOf(specials),
                List.copyOf(lucky), List.copyOf(wheels));
    }

    private record Model(List<RoundCandidate> losses, List<RoundCandidate> wins, List<RoundCandidate> specials,
                         List<RoundCandidate> lucky, List<RoundCandidate> wheels) {}
}
