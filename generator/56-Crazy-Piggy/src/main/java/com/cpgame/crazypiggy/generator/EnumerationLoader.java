package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.ResultAnalysis;
import com.cpgame.crazypiggy.generator.model.RoundCandidate;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Finite payout-bucket coverage. Generation counts are ignored. */
final class EnumerationLoader {
    private static final BigDecimal BET_SIZE = new BigDecimal("0.5");
    private static final int BET_LEVEL = 1;
    private static final List<String> WHEEL_SYMBOLS = List.of("H2", "H3", "H4", "H5", "H6", "H7");

    private EnumerationLoader() {}

    static GenerationSummary generate(GeneratorConfig config, BiConsumer<RoundResult, ResultAnalysis> sink) {
        LoaderLimits limits = config.outputLimits;
        int cap = config.maxMembersPerMultiplier;
        int maxChain = config.maxConsecutiveWins;
        SecureRandom random = new SecureRandom();
        RoundFactory factory = new RoundFactory();
        RoundVerifier verifier = new RoundVerifier();
        IndependentLossGenerator losses = new IndependentLossGenerator();
        OrdinaryBoardCatalog catalog = OrdinaryBoardCatalog.get();
        Map<Integer, Map<String, List<Supplier<RoundResult>>>> plans = new TreeMap<>();
        if (limits.accepts(false, 0)) {
            add(plans, 0, "LOSS", () -> factory.create(losses.generate(random), BET_SIZE, BET_LEVEL));
        }
        for (int b = 0; b < catalog.bucketCount(); b++) {
            int ratio = catalog.ratio(b);
            if (!limits.accepts(false, ratio)) continue;
            for (int p = 0; p < catalog.patternCount(b); p++) {
                List<Integer> indices = new ArrayList<>(catalog.size(b, p));
                for (int i = 0; i < catalog.size(b, p); i++) indices.add(i);
                Collections.shuffle(indices, random);
                int bucket = b;
                int pattern = p;
                int[] cursor = {0};
                add(plans, ratio, "DIRECT", () -> {
                    if (cursor[0] >= indices.size()) return null;
                    List<String> symbols = catalog.board(bucket, pattern, indices.get(cursor[0]++));
                    return factory.create(new RoundCandidate(symbols, List.of(), List.of()), BET_SIZE, BET_LEVEL);
                });
            }
        }
        int extraMax = Math.min(6, Math.max(0, maxChain - 1));
        for (String symbol : WHEEL_SYMBOLS) {
            List<String> board = List.of(symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol);
            int base = 5 * GameRules.PAYTABLE.get(symbol);
            for (int extras = 0; extras <= extraMax; extras++) {
                int combos = 1 << extras;
                for (int mask = 0; mask < combos; mask++) {
                    List<Integer> multipliers = new ArrayList<>(extras);
                    int sum = 0;
                    for (int i = 0; i < extras; i++) {
                        int value = ((mask >> i) & 1) == 0 ? 1 : 2;
                        multipliers.add(value);
                        sum += value;
                    }
                    int ratio = base * (1 + sum);
                    if (!limits.accepts(false, ratio)) continue;
                    List<Integer> positions = new ArrayList<>(extras + 1);
                    for (int i = 0; i <= extras; i++) positions.add(i);
                    List<Integer> frozenMultipliers = List.copyOf(multipliers);
                    List<Integer> frozenPositions = List.copyOf(positions);
                    int[] used = {0};
                    add(plans, ratio, "BOOSTER", () -> {
                        if (used[0]++ > 0) return null;
                        return factory.create(new RoundCandidate(board, frozenPositions, frozenMultipliers),
                                BET_SIZE, BET_LEVEL);
                    });
                }
            }
        }
        List<Bucket> buckets = new ArrayList<>();
        for (var entry : plans.entrySet()) {
            List<Supplier<RoundResult>> families = new ArrayList<>();
            for (var sources : entry.getValue().values()) families.add(cycle(sources, random));
            buckets.add(new Bucket(entry.getKey(), cap, cycle(families, random)));
        }
        System.out.printf("ENUMERATION_START ordinaryBoards=%d patterns=%d buckets=%d normalCap=%d extraMax=%d%n",
                catalog.storedBoards, catalog.patternCount, buckets.size(), cap, extraMax);
        int loss = 0, win = 0, booster = 0;
        long attempts = 0;
        while (!buckets.isEmpty()) {
            for (Iterator<Bucket> it = buckets.iterator(); it.hasNext(); ) {
                Bucket bucket = it.next();
                if (++bucket.attempts > Math.max(10_000L, 100L * bucket.target))
                    throw new IllegalStateException("Enumeration could not fill unique bucket " + bucket.ratio
                            + " filled=" + bucket.seen.size() + "/" + bucket.target);
                attempts++;
                RoundResult round = bucket.next.get();
                if (round == null) { it.remove(); continue; }
                ResultAnalysis analysis = verifier.verify(round);
                int ratio = analysis.totalAward().divide(analysis.betAmount()).intValueExact();
                if (ratio != bucket.ratio)
                    throw new IllegalStateException("enumeration bucket mismatch");
                if (!limits.accepts(false, ratio))
                    throw new IllegalStateException("enumeration produced a ratio outside the configured range");
                if (!bucket.seen.add(identity(round))) continue;
                sink.accept(round, analysis);
                if (round.loss()) loss++;
                else {
                    win++;
                    if (round.boosterWheel()) booster++;
                }
                if (bucket.seen.size() >= bucket.target) it.remove();
            }
        }
        return new GenerationSummary(loss, win, booster, attempts);
    }

    private static void add(Map<Integer, Map<String, List<Supplier<RoundResult>>>> plans,
                            int ratio, String family, Supplier<RoundResult> source) {
        plans.computeIfAbsent(ratio, k -> new TreeMap<>())
                .computeIfAbsent(family, k -> new ArrayList<>())
                .add(source);
    }

    private static Supplier<RoundResult> cycle(List<Supplier<RoundResult>> sources, SecureRandom random) {
        List<Supplier<RoundResult>> active = new ArrayList<>(sources);
        Collections.shuffle(active, random);
        int[] cursor = {0};
        return () -> {
            while (!active.isEmpty()) {
                int index = cursor[0] % active.size();
                RoundResult round = active.get(index).get();
                if (round == null) {
                    active.remove(index);
                    cursor[0] = index;
                    continue;
                }
                cursor[0] = (index + 1) % active.size();
                return round;
            }
            return null;
        };
    }

    private static String identity(RoundResult round) {
        StringBuilder out = new StringBuilder(20);
        for (String symbol : round.symbols()) out.append(GameRules.SYMBOLS.indexOf(symbol));
        out.append('|');
        for (int position : round.wheelPositions()) out.append(position);
        out.append('|');
        for (int multiplier : round.wheelMultipliers()) out.append(multiplier);
        return out.toString();
    }

    private static final class Bucket {
        final int ratio, target;
        final Supplier<RoundResult> next;
        final Set<String> seen = new HashSet<>();
        long attempts;
        Bucket(int ratio, int target, Supplier<RoundResult> next) {
            this.ratio = ratio;
            this.target = target;
            this.next = next;
        }
    }

    record GenerationSummary(int loss, int win, int booster, long attempts) {}
}
