package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundMode;
import com.cpgame.crazybirds.generator.model.RoundResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** server-api、生成器和 Redis Loader 共用的唯一规则核心。 */
public final class GameRuleCore {
    private final RoundFactory factory = new RoundFactory();
    private final RoundVerifier verifier = new RoundVerifier();
    private final List<List<List<String>>> loss;
    private final List<List<List<String>>> win;
    private final List<List<List<String>>> free;
    private final RandomGenerator random;

    public GameRuleCore() {
        this(new SecureRandom());
    }

    public GameRuleCore(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
        KernelBank bank = BankHolder.BANK;
        this.loss = bank.loss;
        this.win = bank.win;
        this.free = bank.free;
    }

    public static GameRuleCore forRestoration() {
        return new GameRuleCore(new SecureRandom());
    }

    public RoundResult generateIndependentLoss(int bl, BigDecimal bs, BigDecimal start, RandomGenerator rng) {
        return generateWithCandidates(bl,bs,start,rng,() -> independentLossCandidate(rng));
    }
    public List<List<String>> independentLossCandidate(RandomGenerator rng) { return pick(loss,rng); }
    RoundResult generateWithCandidates(int bl, BigDecimal bs, BigDecimal start, RandomGenerator rng,
                                       java.util.function.Supplier<List<List<String>>> proposals) {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<List<String>> candidate = proposals.get();
            if (candidate != null && isIndependentLoss(candidate)) return lossResult(candidate, bl, bs, start, rng);
        }
        return lossResult(BankHolder.DEFAULTS.get(rng.nextInt(10)), bl, bs, start, rng);
    }

    public RoundResult generateOrdinaryWin(int bl, BigDecimal bs, BigDecimal start, RandomGenerator rng) {
        return finish(pick(win, rng), bl, bs, start, RoundMode.ORDINARY_WIN);
    }

    public RoundResult generateFreeSpins(int bl, BigDecimal bs, BigDecimal start, RandomGenerator rng) {
        return finish(pick(free, rng), bl, bs, start, RoundMode.FREE_SPINS);
    }

    public RoundResult restore(String payload, BigDecimal bs, int bl, BigDecimal start) {
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(factory, verifier);
        return codec.decodeRedisMember(payload, bs, bl, start);
    }

    private RoundResult finish(List<List<String>> boards, int bl, BigDecimal bs, BigDecimal start, RoundMode expected) {
        for (int attempt = 0; attempt < 64; attempt++) {
            List<List<String>> pick = boards != null ? boards : null;
            if (attempt > 0) {
                pick = switch (expected) {
                    case ORDINARY_LOSS -> pick(loss, random);
                    case ORDINARY_WIN -> pick(win, random);
                    case FREE_SPINS -> pick(free, random);
                };
            }
            RoundResult round = factory.restore(Long.toHexString(random.nextLong()), bs, bl, start, pick);
            var analysis = verifier.verify(round);
            if (analysis.mode() == expected) return round;
        }
        throw new IllegalStateException("内核类别与目标不一致: " + expected);
    }

    private static List<List<String>> pick(List<List<List<String>>> pool, RandomGenerator rng) {
        if (pool.isEmpty()) throw new IllegalStateException("生成内核为空");
        return pool.get(rng.nextInt(pool.size()));
    }

    private record KernelBank(List<List<List<String>>> loss, List<List<List<String>>> win, List<List<List<String>>> free) {
        static KernelBank load() {
            List<List<List<String>>> loss = new ArrayList<>();
            List<List<List<String>>> win = new ArrayList<>();
            List<List<List<String>>> free = new ArrayList<>();
            try (var in = GameRuleCore.class.getResourceAsStream("/crazy-birds-kernels.txt")) {
                if (in == null) throw new IllegalStateException("缺少 crazy-birds-kernels.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    int sp = line.indexOf(' ');
                    String kind = line.substring(0, sp);
                    List<List<String>> boards = parse(line.substring(sp + 1));
                    switch (kind) {
                        case "LOSS" -> loss.add(boards);
                        case "WIN" -> win.add(boards);
                        case "FREE" -> free.add(boards);
                        default -> throw new IllegalStateException("未知内核类别 " + kind);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("读取生成内核失败", e);
            }
            if (loss.isEmpty() || win.isEmpty()) throw new IllegalStateException("LOSS/WIN 内核不足");
            return new KernelBank(List.copyOf(loss), List.copyOf(win), List.copyOf(free));
        }

        private static List<List<String>> parse(String raw) {
            List<List<String>> boards = new ArrayList<>();
            for (String board : raw.split("\\|", -1)) boards.add(List.of(board.split(",", -1)));
            return List.copyOf(boards);
        }
    }

    private RoundResult lossResult(List<List<String>> boards, int bl, BigDecimal bs, BigDecimal start, RandomGenerator rng) {
        RoundResult result = factory.restore(Long.toHexString(rng.nextLong()), bs, bl, start, boards);
        if (verifier.verify(result).mode() != RoundMode.ORDINARY_LOSS) throw new IllegalStateException("loss verification");
        return result;
    }
    private static boolean isIndependentLoss(List<List<String>> boards) {
        return boards.size() == 1 && !ResultUtil.isScatterTrigger(boards.get(0))
                && ResultUtil.evaluateWays(boards.get(0), BigDecimal.ONE).isEmpty();
    }
    private static final class BankHolder {
        static final KernelBank BANK = KernelBank.load();
        static final List<List<List<String>>> DEFAULTS = defaults();
        private static List<List<List<String>>> defaults() {
            for (var board : BANK.loss) if (!isIndependentLoss(board))
                throw new ExceptionInInitializerError("invalid LOSS model entry");
            if (BANK.loss.size() < 10) throw new ExceptionInInitializerError("ten default losses required");
            return List.copyOf(BANK.loss.subList(0, 10));
        }
    }
}
