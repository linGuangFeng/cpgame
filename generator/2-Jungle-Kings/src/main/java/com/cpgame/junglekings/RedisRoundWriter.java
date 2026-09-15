package com.cpgame.junglekings;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Writes verified complete members to BetLog.
 * betType 0 = one reel (PerKeyList_0), betType 1 = both reels (PerKeyList_1).
 */
public final class RedisRoundWriter {
    private final RedisRoundStore store;
    private final MemberCodec codec;
    private final IndependentVerifier verifier;
    private final int maximumMembersPerMultiplier;

    public RedisRoundWriter(RedisRoundStore store, MemberCodec codec,
                            IndependentVerifier verifier, int maximumMembersPerMultiplier) {
        if (maximumMembersPerMultiplier < 1) throw new IllegalArgumentException("pool limit must be positive");
        this.store = store;
        this.codec = codec;
        this.verifier = verifier;
        this.maximumMembersPerMultiplier = maximumMembersPerMultiplier;
    }

    public WriteResult write(CompleteRound round) throws IOException {
        verifier.verify(round);
        int betType = RedisKeys.betType(round.chessboards());
        int ratio = round.multiplier();
        store.writeMember(betType, ratio, codec.encode(round), maximumMembersPerMultiplier);
        return new WriteResult(true, RedisKeys.list(betType, ratio), round.mode(), BigDecimal.valueOf(ratio));
    }

    public Optional<ClaimedRound> claimAny(List<RoundMode> modes, SecureRandom random) throws IOException {
        return claimAny(modes, GameRuleCore.CHESSBOARDS, random);
    }

    public Optional<ClaimedRound> claimAny(List<RoundMode> modes, List<String> chessboards,
                                           SecureRandom random) throws IOException {
        if (modes == null || modes.isEmpty() || random == null || chessboards == null || chessboards.isEmpty()) {
            throw new IllegalArgumentException("modes, chessboards and random are required");
        }
        List<String> wanted = GameRuleCore.parseChessboards(String.join(",", chessboards));
        int betType = RedisKeys.betType(wanted);
        List<Integer> ratios = new ArrayList<>();
        for (String token : store.ratios(betType)) {
            int ratio = Integer.parseInt(token);
            if (modes.contains(RoundMode.LOSS) && !modes.contains(RoundMode.WIN) && ratio != 0) continue;
            if (modes.contains(RoundMode.WIN) && !modes.contains(RoundMode.LOSS) && ratio <= 0) continue;
            if (store.listLength(betType, ratio) > 0) ratios.add(ratio);
        }
        Collections.shuffle(ratios, random);
        for (int ratio : ratios) {
            Optional<ClaimedRound> hit = claimRatio(betType, ratio, modes, wanted, random);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    private Optional<ClaimedRound> claimRatio(int betType, int ratio, List<RoundMode> modes,
                                              List<String> wanted, SecureRandom random) throws IOException {
        long len = store.listLength(betType, ratio);
        if (len <= 0) return Optional.empty();
        int n = (int) Math.min(len, Integer.MAX_VALUE);
        int start = random.nextInt(n);
        for (int i = 0; i < n; i++) {
            Optional<byte[]> member = store.readMember(betType, ratio, (start + i) % n);
            if (member.isEmpty()) continue;
            CompleteRound round = codec.decode(member.get());
            verifier.verify(round);
            if (RedisKeys.betType(round.chessboards()) != betType) continue;
            if (!wanted.equals(round.chessboards())) continue;
            if (!modes.contains(round.mode())) continue;
            if (round.multiplier() != ratio) throw new IOException("cached member multiplier does not match list key");
            return Optional.of(new ClaimedRound(RedisKeys.list(betType, ratio), round));
        }
        return Optional.empty();
    }

    public SetView poolSize() throws IOException {
        long keys = 0;
        long members = 0;
        for (int betType : List.of(RedisKeys.SINGLE_LINE, RedisKeys.BOTH_LINES)) {
            for (String ratio : store.ratios(betType)) {
                long len = store.listLength(betType, Integer.parseInt(ratio));
                if (len > 0) {
                    keys++;
                    members += len;
                }
            }
        }
        return new SetView(keys, members);
    }

    public record WriteResult(boolean written, String poolKey, RoundMode mode, BigDecimal multiplier) { }
    public record ClaimedRound(String poolKey, CompleteRound round) { }
    public record SetView(long buckets, long members) { }
}
