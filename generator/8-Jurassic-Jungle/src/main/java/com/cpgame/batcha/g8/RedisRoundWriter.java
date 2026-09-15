package com.cpgame.batcha.g8;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Writes verified complete members and claims them with LPOP. */
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
        int ratio = round.unitRatio();
        boolean special = RedisKeys.special(round.mode());
        store.writeMember(special, ratio, codec.encode(round), special ? specialMaximum : maximumMembersPerMultiplier);
        return new WriteResult(true, RedisKeys.list(special, ratio), round.mode(), ratio);
    }

    public Optional<ClaimedRound> claimWinOrLoss(boolean wantWin, SecureRandom random) throws IOException {
        if (random == null) throw new IllegalArgumentException("random is required");
        List<Boolean> pools = new ArrayList<>();
        if (wantWin) {
            pools.add(true);
            pools.add(false);
        } else {
            pools.add(false);
        }
        Collections.shuffle(pools, random);
        for (boolean special : pools) {
            Optional<ClaimedRound> claimed = claimPool(special, wantWin, random);
            if (claimed.isPresent()) return claimed;
        }
        return Optional.empty();
    }

    public Optional<ClaimedRound> claimMode(RoundMode mode, SecureRandom random) throws IOException {
        return claimPool(RedisKeys.special(mode), mode != RoundMode.LOSS, random)
            .filter(claimed -> claimed.round().mode() == mode);
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

    private Optional<ClaimedRound> claimPool(boolean special, boolean wantWin, SecureRandom random)
            throws IOException {
        List<Integer> ratios = new ArrayList<>();
        for (String token : store.ratios(special)) {
            int ratio = Integer.parseInt(token);
            if (wantWin) {
                if (ratio <= 0) continue;
            } else if (ratio != 0) continue;
            if (store.listLength(special, ratio) > 0) ratios.add(ratio);
        }
        Collections.shuffle(ratios, random);
        for (int ratio : ratios) {
            Optional<byte[]> member = store.popMember(special, ratio);
            if (member.isEmpty()) continue;
            CompleteRound round = codec.decode(member.get());
            verifier.verify(round);
            if (RedisKeys.special(round.mode()) != special) {
                throw new IOException("claimed member special flag does not match list");
            }
            if (round.unitRatio() != ratio) throw new IOException("claimed member unit ratio does not match list key");
            return Optional.of(new ClaimedRound(RedisKeys.list(special, ratio), round));
        }
        return Optional.empty();
    }

    public record WriteResult(boolean written, String poolKey, RoundMode mode, int unitRatio) { }
    public record ClaimedRound(String poolKey, CompleteRound round) { }
    public record SetView(long buckets, long members) { }
}
