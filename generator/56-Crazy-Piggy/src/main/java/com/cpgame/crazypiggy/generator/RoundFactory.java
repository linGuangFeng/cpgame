package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundCandidate;
import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import com.cpgame.crazypiggy.generator.model.WheelDelivery;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** 完整 Round 真值工厂；刻意不调用 ResultUtil，便于独立反推核验。 */
public final class RoundFactory {
    private final IdentitySource identitySource;

    public RoundFactory() {
        this(new SecureIdentitySource());
    }

    public RoundFactory(IdentitySource identitySource) {
        this.identitySource = identitySource;
    }

    public RoundResult create(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        RoundIdentity identity = identitySource.next();
        return create(candidate, betSize, betLevel, identity.roundKey(), identity.createdAtEpochSecond());
    }

    public RoundResult restore(RoundFacts facts) {
        return create(new RoundCandidate(facts.symbols(), facts.wheelPositions(), facts.wheelMultipliers()),
                facts.betSize(), facts.betLevel(), facts.roundKey(), facts.createdAtEpochSecond());
    }

    private RoundResult create(RoundCandidate candidate, BigDecimal betSize, int betLevel,
                               String roundKey, long createdAtEpochSecond) {
        List<String> symbols = candidate.symbols();
        if (symbols.size() != 9) throw new IllegalArgumentException("候选牌面必须为 3×3 九格");
        Map<Integer, String> wins = generatedLineWins(symbols);
        BigDecimal betAmount = betSize.multiply(BigDecimal.valueOf(betLevel));
        BigDecimal base = generatedAward(wins, betSize, betLevel);
        boolean trigger = boosterTrigger(symbols);
        boolean wheelFacts = !candidate.wheelPositions().isEmpty() || !candidate.wheelMultipliers().isEmpty();
        if (trigger != wheelFacts) throw new IllegalArgumentException("轮盘触发牌面与候选轮盘事实不一致");

        BigDecimal wheel = BigDecimal.ZERO;
        List<WheelDelivery> deliveries = List.of();
        int gameMode = 0;
        int smallGameType = 0;
        if (trigger) {
            int multiplierSum = candidate.wheelMultipliers().stream().mapToInt(Integer::intValue).sum();
            wheel = base.multiply(BigDecimal.valueOf(multiplierSum));
            deliveries = generatedDeliveries(candidate);
            gameMode = 1;
            smallGameType = 2;
        }
        return new RoundResult(roundKey, createdAtEpochSecond, betSize, betLevel, betAmount,
                symbols, wins, base, wheel, base.add(wheel), gameMode, smallGameType,
                candidate.wheelPositions(), candidate.wheelMultipliers(), deliveries);
    }

    private static Map<Integer, String> generatedLineWins(List<String> symbols) {
        Map<Integer, String> wins = new LinkedHashMap<>();
        for (int line = 0; line < GameRules.PAYLINES.length; line++) {
            int[] route = GameRules.PAYLINES[line];
            String symbol = symbols.get(route[0]);
            if (!GameRules.PAYTABLE.containsKey(symbol)) throw new IllegalArgumentException("非法符号: " + symbol);
            if (symbol.equals(symbols.get(route[1])) && symbol.equals(symbols.get(route[2]))) {
                wins.put(line, symbol);
            }
        }
        return wins;
    }

    private static BigDecimal generatedAward(Map<Integer, String> wins, BigDecimal betSize, int betLevel) {
        BigDecimal result = BigDecimal.ZERO;
        for (String symbol : wins.values()) {
            result = result.add(betSize.multiply(BigDecimal.valueOf(betLevel))
                    .multiply(BigDecimal.valueOf(GameRules.PAYTABLE.get(symbol))));
        }
        return result;
    }

    private static List<WheelDelivery> generatedDeliveries(RoundCandidate candidate) {
        List<WheelDelivery> deliveries = new ArrayList<>(candidate.wheelPositions().size());
        for (int i = 0; i < candidate.wheelPositions().size(); i++) {
            boolean terminal = i == candidate.wheelPositions().size() - 1;
            deliveries.add(new WheelDelivery(i, candidate.wheelPositions().get(i),
                    terminal ? null : candidate.wheelMultipliers().get(i), terminal));
        }
        return deliveries;
    }

    private static boolean boosterTrigger(List<String> symbols) {
        String first = symbols.get(0);
        return !first.equals("HOT") && !first.equals("SEV") && symbols.stream().allMatch(first::equals);
    }

    public interface IdentitySource {
        RoundIdentity next();
    }

    public record RoundIdentity(String roundKey, long createdAtEpochSecond) {}

    private static final class SecureIdentitySource implements IdentitySource {
        private final SecureRandom random = new SecureRandom();
        @Override public RoundIdentity next() {
            return new RoundIdentity("cp" + GameRules.GAME_ID + "-" + randomHex(random),
                    Instant.now().getEpochSecond());
        }
    }

    public static IdentitySource deterministicIdentitySource(long seed) {
        RandomGenerator random = new java.util.SplittableRandom(seed);
        return () -> new RoundIdentity("cp" + GameRules.GAME_ID + "-test-" + randomHex(random),
                1_700_000_000L + Math.floorMod(random.nextLong(), 10_000_000L));
    }

    private static String randomHex(RandomGenerator random) {
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }
}
