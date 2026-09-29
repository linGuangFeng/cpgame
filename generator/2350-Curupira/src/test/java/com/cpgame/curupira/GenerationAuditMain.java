package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationScene;
import com.cpgame.curupira.loader.GenerationRun;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.random.SecureRandomSource;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Manual, no-Redis audit entry point; it executes the same GenerationRun used by RedisLoader. */
public final class GenerationAuditMain {
    private GenerationAuditMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: GenerationAuditMain <properties>");
        EngineConfiguration config = EngineConfiguration.load(Path.of(args[0]));
        MinimalFactCodec codec = new MinimalFactCodec();
        Set<String> uniqueMembers = new HashSet<>();
        Set<String> structures = new HashSet<>();
        Map<CompleteRoundFact.Kind, Set<String>> uniqueByKind = new EnumMap<>(CompleteRoundFact.Kind.class);
        Set<Integer> memberLengths = new TreeSet<>();
        long[] firstColumnWild = {0};
        long[] maxSeparatorsPerTenThousand = {0};

        GenerationRun.Summary summary = GenerationRun.execute(config, new SecureRandomSource(),
                (batch, phase, entries) -> entries.forEach(entry -> {
                    String member = entry.member();
                    uniqueMembers.add(member);
                    memberLengths.add(member.length());
                    for (char separator : new char[]{';', '/', ':', '.'}) {
                        long count = member.chars().filter(ch -> ch == separator).count();
                        maxSeparatorsPerTenThousand[0] = Math.max(maxSeparatorsPerTenThousand[0],
                                count * 10_000L / member.length());
                    }
                    CompleteRoundFact fact = codec.decode(member);
                    uniqueByKind.computeIfAbsent(fact.kind(), ignored -> new HashSet<>()).add(member);
                    if (fact.kind() == CompleteRoundFact.Kind.HOLD) {
                        var last = fact.steps().getLast();
                        structures.add("HOLD:steps=" + fact.steps().size() + ":filled=" + last.fcc()
                                + ":newCounts=" + fact.steps().stream().map(step -> step.fcn().size()).toList());
                    } else {
                        var shape = new StringBuilder(fact.kind().name());
                        for (var step : fact.steps()) {
                            var board = step.evaluatedBoard();
                            if (board.ps().subList(0, GameRules.ROWS).contains(GameRules.WILD)) firstColumnWild[0]++;
                            shape.append(":sc=").append(board.scatterCount())
                                    .append(":ew=").append(board.expandingWildColumns())
                                    .append(":awards=").append(board.awards().size());
                            if (!GameRules.hasAtMostOneScatterPerColumn(board.ps())) {
                                throw new AssertionError("same-column double Scatter escaped generation");
                            }
                        }
                        structures.add(shape.toString());
                    }
                }));

        System.out.println("AUDIT attempts=" + summary.attempts());
        System.out.println("AUDIT accepted=" + summary.accepted());
        System.out.println("AUDIT rejected=" + summary.rejected());
        System.out.println("AUDIT batches=" + summary.batches());
        System.out.println("AUDIT attemptsByScene=" + summary.attemptsByScene());
        System.out.println("AUDIT acceptedByKind=" + summary.acceptedByKind());
        System.out.println("AUDIT uniqueByKind=" + uniqueByKind.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size())));
        System.out.println("AUDIT rejectedByReason=" + summary.rejectedByReason());
        System.out.println("AUDIT phaseAttempts=" + summary.phaseAttempts());
        System.out.println("AUDIT uniqueMembers=" + uniqueMembers.size());
        System.out.println("AUDIT uniqueMultipliers=" + summary.multiplierFrequency().size());
        System.out.println("AUDIT multiplierMin=" + summary.multiplierFrequency().keySet().stream().min(Integer::compareTo).orElse(-1));
        System.out.println("AUDIT multiplierMax=" + summary.multiplierFrequency().keySet().stream().max(Integer::compareTo).orElse(-1));
        System.out.println("AUDIT multiplierBands={0=" + count(summary, 0, 0)
                + ",1..125=" + count(summary, 1, 125)
                + ",126..500=" + count(summary, 126, 500)
                + ",501..1250=" + count(summary, 501, 1_250)
                + ",1251..2500=" + count(summary, 1_251, 2_500)
                + ",2501..250000=" + count(summary, 2_501, 250_000) + "}");
        for (GenerationScene scene : GenerationScene.values()) {
            Map<Integer, Long> frequency = summary.multiplierFrequencyByScene().getOrDefault(scene, Map.of());
            System.out.println("AUDIT multiplierBands." + scene + "={0=" + count(frequency, 0, 0)
                    + ",1..125=" + count(frequency, 1, 125)
                    + ",126..500=" + count(frequency, 126, 500)
                    + ",501..1250=" + count(frequency, 501, 1_250)
                    + ",1251..2500=" + count(frequency, 1_251, 2_500)
                    + ",2501..250000=" + count(frequency, 2_501, 250_000) + "}");
        }
        System.out.println("AUDIT uniqueStructures=" + structures.size());
        System.out.println("AUDIT firstColumnWild=" + firstColumnWild[0]);
        System.out.println("AUDIT memberLengths=" + memberLengths);
        System.out.println("AUDIT maxSeparatorRatio=" + (maxSeparatorsPerTenThousand[0] / 100.0) + "%");
        System.out.println("AUDIT redisWritePerformed=false");
    }

    private static long count(GenerationRun.Summary summary, int min, int max) {
        return count(summary.multiplierFrequency(), min, max);
    }

    private static long count(Map<Integer, Long> frequency, int min, int max) {
        return frequency.entrySet().stream()
                .filter(entry -> entry.getKey() >= min && entry.getKey() <= max)
                .mapToLong(java.util.Map.Entry::getValue).sum();
    }
}
