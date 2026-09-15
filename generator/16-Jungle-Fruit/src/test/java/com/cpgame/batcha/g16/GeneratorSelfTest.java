package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Dependency-free tests, executed with java -ea after test compilation. */
public final class GeneratorSelfTest {
    private GeneratorSelfTest() { }

    public static void main(String[] args) throws Exception {
        paytableAndGlobalCountRule();
        completeRoundsAndCodec();
        atomicClaimContract();
        System.out.println("GeneratorSelfTest PASS");
    }

    private static void paytableAndGlobalCountRule() {
        assert GameRuleCore.symbolPayTable().get("H1").get(8) == 40;
        assert GameRuleCore.symbolPayTable().get("H1").get(36) == 300;
        assert GameRuleCore.symbolPayTable().get("Scat").get(36) == 0;
        assert GameRuleCore.pay("A", 8, new BigDecimal("0.05"), 2)
            .compareTo(new BigDecimal("1")) == 0;
        try { GameRuleCore.symbolPayTable().put("bad", Map.of()); assert false; }
        catch (UnsupportedOperationException expected) { }

        List<String> board = new ArrayList<>();
        List<String> withoutH2 = GameRuleCore.PAYING_SYMBOLS.stream()
            .filter(symbol -> !"H2".equals(symbol)).toList();
        for (int i = 0; i < 36; i++) board.add(withoutH2.get(i % withoutH2.size()));
        int[] scattered = {0, 5, 7, 12, 19, 23, 30, 35};
        for (int index : scattered) board.set(index, "H2");
        GameRuleCore.BoardResult core = GameRuleCore.evaluateBoard(board, new BigDecimal("0.05"), 1);
        ResultUtil.BoardResult independent = new ResultUtil().evaluate(board, new BigDecimal("0.05"), 1);
        assert core.matches().equals(independent.matches());
        assert core.winAmount().compareTo(new BigDecimal("1.5")) == 0;
        assert core.matches().getFirst().indices().equals(List.of(0, 5, 11, 20, 31, 35, 50, 55));
        board.set(35, "Q");
        assert GameRuleCore.evaluateBoard(board, new BigDecimal("0.05"), 1).winAmount().signum() == 0;
    }

    private static void completeRoundsAndCodec() {
        CompleteRoundFactory factory = new CompleteRoundFactory(10, 30);
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 10, 30);
        MemberCodec codec = new MemberCodec();
        SecureRandom random = new SecureRandom();
        for (RoundMode mode : List.of(RoundMode.LOSS, RoundMode.MARY, RoundMode.FREE)) {
            for (int sample = 0; sample < 100; sample++) {
                CompleteRound round = factory.generate(mode, random, new BigDecimal("0.05"), 1);
                assert round.mode() == mode : mode + " classified as " + round.mode();
                verifier.verifyCodecRoundTrip(round, codec);
                BigDecimal payout = round.steps().stream().filter(step -> step.spinStatus() == 1)
                    .map(Step::roundWinAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                assert payout.compareTo(round.payout()) == 0;

            }
        }
    }

    private static void atomicClaimContract() throws Exception {
        MemoryStore store = new MemoryStore();
        MemberCodec codec = new MemberCodec();
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 10, 30);
        RedisRoundWriter writer = new RedisRoundWriter(store, codec, verifier, 2);
        CompleteRoundFactory factory = new CompleteRoundFactory(10, 30);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10;) {
            CompleteRound round = factory.generate(RoundMode.MARY, random, new BigDecimal("0.05"), 1);
            if (round.multiplier().stripTrailingZeros().scale() > 0) continue;
            writer.write(round);
            i++;
        }
        writer.write(factory.generate(RoundMode.LOSS, random, new BigDecimal("0.05"), 1));
        int before = store.totalMembers();
        Optional<RedisRoundWriter.ClaimedRound> claimed = writer.claimAny(List.of(RoundMode.MARY), random);
        assert claimed.isPresent();
        assert claimed.get().round().mode() == RoundMode.MARY;
        assert claimed.get().poolKey().startsWith("MaryLog:000000016:");
        assert store.totalMembers() == before;
        assert writer.claimAny(List.of(RoundMode.LOSS), random).orElseThrow().round().mode() == RoundMode.LOSS;
    }

    private static List<String> safeBoard() {
        List<String> board = new ArrayList<>();
        for (int i = 0; i < 36; i++) board.add(GameRuleCore.PAYING_SYMBOLS.get(i % 10));
        return board;
    }

    private static final class MemoryStore implements RedisRoundStore {
        private final Map<String, ArrayDeque<byte[]>> lists = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<String>> indexes = new LinkedHashMap<>();
        @Override public synchronized void writeMember(boolean special, int ratio, byte[] member, int max) {
            String listKey = RedisKeys.list(special, ratio);
            ArrayDeque<byte[]> list = lists.computeIfAbsent(listKey, ignored -> new ArrayDeque<>());
            list.addLast(member.clone());
            while (list.size() > max) list.removeFirst();
            indexes.computeIfAbsent(RedisKeys.index(special), ignored -> new LinkedHashSet<>()).add(Integer.toString(ratio));
        }
        @Override public synchronized List<String> ratios(boolean special) {
            return List.copyOf(indexes.getOrDefault(RedisKeys.index(special), new LinkedHashSet<>()));
        }
        @Override public synchronized long listLength(boolean special, int ratio) {
            ArrayDeque<byte[]> list = lists.get(RedisKeys.list(special, ratio));
            return list == null ? 0 : list.size();
        }
        @Override public synchronized Optional<byte[]> readMember(boolean special, int ratio, int offset) {
            ArrayDeque<byte[]> list = lists.get(RedisKeys.list(special, ratio));
            if (list == null || offset < 0 || offset >= list.size()) return Optional.empty();
            int index = 0;
            for (byte[] member : list) {
                if (index++ == offset) return Optional.of(member.clone());
            }
            return Optional.empty();
        }
        int totalMembers() { return lists.values().stream().mapToInt(ArrayDeque::size).sum(); }
        @Override public void close() { }
    }
}
