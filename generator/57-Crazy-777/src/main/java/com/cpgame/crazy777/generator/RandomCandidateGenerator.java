package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundMode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 从训练集完整联合状态 kernel 抽样。不逐格抽符号，也不拼接未中奖盘。
 * 允许的变换只做上下翻转和左右换轴，保持五条线集合与特殊符号计数不变。
 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/crazy777-joint-kernels.txt";
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
        return c.boards().size() == 1 && !ResultUtil.isScatterTrigger(c.boards().get(0)) && ResultUtil.evaluateRegularLines(c.boards().get(0)).isEmpty();
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

    public RoundCandidate freeSpins(RandomGenerator random) {
        return transform(choose(MODEL.frees, random), random);
    }

    public RoundCandidate natural(RandomGenerator random) {
        int total = MODEL.losses.size() + MODEL.wins.size() + MODEL.frees.size();
        int pick = random.nextInt(total);
        if (pick < MODEL.losses.size()) return transform(MODEL.losses.get(pick), random);
        pick -= MODEL.losses.size();
        if (pick < MODEL.wins.size()) return transform(MODEL.wins.get(pick), random);
        return transform(MODEL.frees.get(pick - MODEL.wins.size()), random);
    }

    public int trainingKernelCount() {
        return MODEL.losses.size() + MODEL.wins.size() + MODEL.frees.size();
    }

    public int lossKernelCount() { return MODEL.losses.size(); }
    public int winKernelCount() { return MODEL.wins.size(); }
    public int freeKernelCount() { return MODEL.frees.size(); }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            List<String> board = independentLossCandidate(random).boards().get(0);
            if (ResultUtil.isScatterTrigger(board) || !ResultUtil.evaluateRegularLines(board).isEmpty()) {
                return 0.0d;
            }
        }
        return 1.0d;
    }

    private static RoundCandidate choose(List<RoundCandidate> values, RandomGenerator random) {
        if (values.isEmpty()) throw new IllegalStateException("训练模型缺少所需完整局类别");
        return values.get(random.nextInt(values.size()));
    }

    private static RoundCandidate transform(RoundCandidate source, RandomGenerator random) {
        int kind = random.nextInt(4);
        if (kind == 0) return source;
        List<List<String>> boards = new ArrayList<>(source.boards().size());
        for (List<String> board : source.boards()) {
            List<String> next = board;
            if ((kind & 1) != 0) next = verticalReverse(next);
            if ((kind & 2) != 0) next = swapOuterReels(next);
            boards.add(next);
        }
        RoundCandidate transformed = new RoundCandidate(boards);
        RoundMode expected = source.boards().size() == 1
                ? (ResultUtil.isScatterTrigger(source.boards().get(0)) ? RoundMode.FREE_SPINS
                : ResultUtil.evaluateRegularLines(source.boards().get(0)).isEmpty()
                ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN)
                : RoundMode.FREE_SPINS;
        RoundMode actual = transformed.boards().size() == 1
                ? (ResultUtil.isScatterTrigger(transformed.boards().get(0)) ? RoundMode.FREE_SPINS
                : ResultUtil.evaluateRegularLines(transformed.boards().get(0)).isEmpty()
                ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN)
                : RoundMode.FREE_SPINS;
        if (expected != actual) return source;
        for (int i = 0; i < transformed.boards().size(); i++) {
            ResultUtil.validateBoard(transformed.boards().get(i), i > 0);
        }
        return transformed;
    }

    static List<String> verticalReverse(List<String> board) {
        List<String> out = new ArrayList<>(15);
        for (int reel = 0; reel < 3; reel++) {
            for (int pos = 0; pos < 5; pos++) out.add(board.get(reel * 5 + (4 - pos)));
        }
        return List.copyOf(out);
    }

    static List<String> swapOuterReels(List<String> board) {
        List<String> out = new ArrayList<>(15);
        out.addAll(board.subList(10, 15));
        out.addAll(board.subList(5, 10));
        out.addAll(board.subList(0, 5));
        return List.copyOf(out);
    }

    private static Model loadModel() {
        List<RoundCandidate> losses = new ArrayList<>();
        List<RoundCandidate> wins = new ArrayList<>();
        List<RoundCandidate> frees = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                RandomCandidateGenerator.class.getResourceAsStream(MODEL_RESOURCE), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length < 2) throw new IllegalArgumentException("联合模型行字段数错误");
                switch (fields[0]) {
                    case "L" -> {
                        if (fields.length != 2) throw new IllegalArgumentException("LOSS kernel 必须是单牌面");
                        losses.add(new RoundCandidate(List.of(symbols(fields[1]))));
                    }
                    case "W" -> {
                        if (fields.length != 2) throw new IllegalArgumentException("WIN kernel 必须是单牌面");
                        wins.add(new RoundCandidate(List.of(symbols(fields[1]))));
                    }
                    case "F" -> {
                        if (fields.length != GameRules.FULL_FREE_STEPS + 1) {
                            throw new IllegalArgumentException("FREE kernel 必须是 11 个牌面");
                        }
                        List<List<String>> boards = new ArrayList<>(GameRules.FULL_FREE_STEPS);
                        for (int i = 1; i < fields.length; i++) boards.add(symbols(fields[i]));
                        frees.add(new RoundCandidate(boards));
                    }
                    default -> throw new IllegalArgumentException("联合模型类别错误: " + fields[0]);
                }
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
        if (losses.size() < 200 || wins.size() < 100 || frees.size() < 30) {
            throw new ExceptionInInitializerError("联合模型训练样本不足 L=" + losses.size()
                    + " W=" + wins.size() + " F=" + frees.size());
        }
        return new Model(List.copyOf(losses), List.copyOf(wins), List.copyOf(frees));
    }

    private static List<String> symbols(String value) {
        List<String> board = List.of(value.split(",", -1));
        if (board.size() != GameRules.BOARD_SIZE) throw new IllegalArgumentException("kernel 牌面长度错误");
        return board;
    }

    private record Model(List<RoundCandidate> losses, List<RoundCandidate> wins, List<RoundCandidate> frees) {}
}
