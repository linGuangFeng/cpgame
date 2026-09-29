package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundMode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 中奖/免费仍从训练联合 kernel 抽样。独立 LOSS 按 BLANK 交替卷轴构造，并拆开可见线颜色。
 */
public final class RandomCandidateGenerator {
    private static final String MODEL_RESOURCE = "/crazy777-joint-kernels.txt";
    private static final Model MODEL = loadModel();
    private static final SymbolWeights OBSERVED = SymbolWeights.empiricalDefaults();
    private final WeightedPool losses;
    private final WeightedPool wins;
    private final WeightedPool frees;

    public RandomCandidateGenerator() { this(OBSERVED); }

    public RandomCandidateGenerator(SymbolWeights weights) {
        losses = pool(MODEL.losses, weights.normal(), OBSERVED.normal(), null, null);
        wins = pool(MODEL.wins, weights.normal(), OBSERVED.normal(), null, null);
        frees = pool(MODEL.frees, weights.entry(), OBSERVED.entry(), weights.free(), OBSERVED.free());
    }

    public RoundCandidate independentLoss(RandomGenerator random) {
        return new IndependentLossGenerator().generate(random);
    }
    public RoundCandidate independentLossCandidate(RandomGenerator random) {
        return new IndependentLossGenerator().candidate(random);
    }

    public RoundCandidate ordinaryWin(RandomGenerator random) {
        return sanitizePresentationOnlyLosses(transform(wins.choose(random), random), random);
    }

    public RoundCandidate freeSpins(RandomGenerator random) {
        return sanitizePresentationOnlyLosses(transform(frees.choose(random), random), random);
    }

    public RoundCandidate natural(RandomGenerator random) {
        int total = MODEL.losses.size() + MODEL.wins.size() + MODEL.frees.size();
        int pick = random.nextInt(total);
        if (pick < MODEL.losses.size()) {
            return sanitizePresentationOnlyLosses(transform(losses.choose(random), random), random);
        }
        pick -= MODEL.losses.size();
        if (pick < MODEL.wins.size()) {
            return sanitizePresentationOnlyLosses(transform(wins.choose(random), random), random);
        }
        return sanitizePresentationOnlyLosses(transform(frees.choose(random), random), random);
    }

    public int trainingKernelCount() {
        return MODEL.losses.size() + MODEL.wins.size() + MODEL.frees.size();
    }

    public int lossKernelCount() { return MODEL.losses.size(); }
    public int winKernelCount() { return MODEL.wins.size(); }
    public int freeKernelCount() { return MODEL.frees.size(); }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        return new IndependentLossGenerator().measureFirstAttemptLossSuccess(random, samples);
    }

    private static WeightedPool pool(List<RoundCandidate> values,
                                     Map<String, Integer> first, Map<String, Integer> observedFirst,
                                     Map<String, Integer> continuation, Map<String, Integer> observedContinuation) {
        double[] logs = new double[values.size()];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.size(); i++) {
            RoundCandidate candidate = values.get(i);
            double value = boardLogWeight(candidate.boards().get(0), first, observedFirst);
            if (continuation != null) for (int page = 1; page < candidate.boards().size(); page++)
                value += boardLogWeight(candidate.boards().get(page), continuation, observedContinuation);
            logs[i] = value;
            max = Math.max(max, value);
        }
        double[] cumulative = new double[logs.length];
        double total = 0;
        for (int i = 0; i < logs.length; i++) cumulative[i] = total += Math.exp(logs[i] - max);
        return new WeightedPool(values, cumulative, total);
    }

    private static double boardLogWeight(List<String> board, Map<String, Integer> configured,
                                         Map<String, Integer> observed) {
        double result = 0;
        for (String symbol : board) result += Math.log((double) configured.get(symbol) / observed.get(symbol));
        return result;
    }

    private record WeightedPool(List<RoundCandidate> values, double[] cumulative, double total) {
        RoundCandidate choose(RandomGenerator random) {
            int index = Arrays.binarySearch(cumulative, random.nextDouble(total));
            if (index < 0) index = -index - 1;
            return values.get(Math.min(index, values.size() - 1));
        }
    }

    private static RoundCandidate sanitizePresentationOnlyLosses(RoundCandidate candidate, RandomGenerator random) {
        List<List<String>> boards = new ArrayList<>(candidate.boards());
        boolean changed = false;
        for (int page = 0; page < candidate.boards().size(); page++) {
            if (ResultUtil.hasPresentationOnlyWin(candidate.boards().get(page), page > 0)) {
                boards.set(page, new IndependentLossGenerator().generate(random).boards().get(0));
                changed = true;
            }
        }
        return changed ? new RoundCandidate(boards) : candidate;
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
