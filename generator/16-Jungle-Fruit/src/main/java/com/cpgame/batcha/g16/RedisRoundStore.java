package com.cpgame.batcha.g16;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Platform Redis operations: ZADD index + RPUSH/LTRIM list on write; ZRANGE + LINDEX on demo read. */
public interface RedisRoundStore extends AutoCloseable {
    void writeMember(boolean special, int ratio, byte[] member, int maximumMembers) throws IOException;
    record PendingMember(boolean special, int ratio, byte[] member, int maximumMembers) { }
    default void writeBatch(List<PendingMember> members) throws IOException {
        for (PendingMember member : members)
            writeMember(member.special(), member.ratio(), member.member(), member.maximumMembers());
    }
    List<String> ratios(boolean special) throws IOException;
    default Optional<Integer> highestAtMost(boolean special, int maxInclusive, int minInclusive) throws IOException {
        if (maxInclusive < minInclusive) return Optional.empty();
        int best = Integer.MIN_VALUE;
        boolean found = false;
        for (String token : ratios(special)) {
            int ratio = Integer.parseInt(token);
            if (ratio >= minInclusive && ratio <= maxInclusive && ratio >= best) {
                best = ratio;
                found = true;
            }
        }
        return found ? Optional.of(best) : Optional.empty();
    }
    long listLength(boolean special, int ratio) throws IOException;
    Optional<byte[]> readMember(boolean special, int ratio, int offset) throws IOException;
    @Override void close() throws IOException;
}
