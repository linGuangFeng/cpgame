package com.cpgame.batcha.g16;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Writes verified complete members to PerKeyList/MaryKeyList and reads them back with LINDEX. */
public final class RedisRoundWriter {
    private final RedisRoundStore store;
    private final MemberCodec codec;
    private final IndependentVerifier verifier;
    private final int maximumMembersPerMultiplier;
    private final int specialMaximum;

    public RedisRoundWriter(RedisRoundStore store, MemberCodec codec,
                            IndependentVerifier verifier, int maximumMembersPerMultiplier) {this(store,codec,verifier,maximumMembersPerMultiplier,100);}
    public RedisRoundWriter(RedisRoundStore store, MemberCodec codec,
                            IndependentVerifier verifier, int maximumMembersPerMultiplier, int specialMaximum) {
        this.specialMaximum=specialMaximum;
        if (maximumMembersPerMultiplier < 1) throw new IllegalArgumentException("pool limit must be positive");
        this.store = store;
        this.codec = codec;
        this.verifier = verifier;
        this.maximumMembersPerMultiplier = maximumMembersPerMultiplier;
    }

    public WriteResult write(CompleteRound round) throws IOException {
        verifier.verify(round);
        int ratio = round.multiplier().intValueExact();
        boolean special = RedisKeys.special(round.mode());
        store.writeMember(special, ratio, codec.encode(round), special ? specialMaximum : maximumMembersPerMultiplier);
        return new WriteResult(true, RedisKeys.list(special, ratio), round.mode(), round.multiplier());
    }

    public Optional<ClaimedRound> claimAny(List<RoundMode> modes, SecureRandom random) throws IOException {
        if (modes == null || modes.isEmpty() || random == null) {
            throw new IllegalArgumentException("modes and random are required");
        }
        boolean wantOrdinary = modes.stream().anyMatch(mode -> !RedisKeys.special(mode));
        boolean wantSpecial = modes.stream().anyMatch(RedisKeys::special);
        List<Boolean> order = new ArrayList<>();
        if (wantSpecial) order.add(true);
        if (wantOrdinary) order.add(false);
        Collections.shuffle(order, random);
        for (boolean special : order) {
            Optional<ClaimedRound> claimed = claimPool(special, modes, random);
            if (claimed.isPresent()) return claimed;
        }
        return Optional.empty();
    }

    public SetView poolSize(boolean special) throws IOException {
        long keys = 0;
        long members = 0;
        for (String ratio : store.ratios(special)) {
            long len = store.listLength(special, Integer.parseInt(ratio));
            if (len > 0) {
                keys++;
                members += len;
            }
        }
        return new SetView(keys, members);
    }

    private Optional<ClaimedRound> claimPool(boolean special, List<RoundMode> modes, SecureRandom random)
            throws IOException {
        int minimum = (!special && modes.contains(RoundMode.LOSS) && !modes.contains(RoundMode.WIN)
            && !modes.contains(RoundMode.MARY)) ? 0 : (special || modes.contains(RoundMode.LOSS) ? 0 : 1);
        int maximum = (!special && modes.contains(RoundMode.LOSS) && !modes.contains(RoundMode.WIN)
            && !modes.contains(RoundMode.MARY)) ? 0 : Integer.MAX_VALUE;
        Optional<Integer> top = store.highestAtMost(special, maximum, minimum);
        if (top.isEmpty()) return Optional.empty();
        int target = minimum >= top.get() ? minimum : random.nextInt(minimum, top.get() + 1);
        int ceiling = target;
        int attempts = 0;
        while (ceiling >= minimum && attempts++ < 64) {
            Optional<Integer> found = store.highestAtMost(special, ceiling, minimum);
            if (found.isEmpty()) return Optional.empty();
            int ratio = found.get();
            long len = store.listLength(special, ratio);
            if (len > 0) {
                int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
                Optional<byte[]> member = store.readMember(special, ratio, offset);
                if (member.isPresent()) {
                    CompleteRound round = codec.decode(member.get());
                    verifier.verify(round);
                    if (modes.contains(round.mode()) && RedisKeys.special(round.mode()) == special
                        && round.multiplier().intValueExact() == ratio) {
                        return Optional.of(new ClaimedRound(RedisKeys.list(special, ratio), round));
                    }
                }
            }
            ceiling = ratio - 1;
        }
        return Optional.empty();
    }

    public record WriteResult(boolean written, String poolKey, RoundMode mode, BigDecimal multiplier) { }
    public record ClaimedRound(String poolKey, CompleteRound round) { }
    public record SetView(long buckets, long members) { }
}
