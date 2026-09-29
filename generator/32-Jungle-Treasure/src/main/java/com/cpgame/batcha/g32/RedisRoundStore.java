package com.cpgame.batcha.g32;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RedisRoundStore extends AutoCloseable {
    void writeMember(boolean special, int ratio, byte[] member, int maximumMembers) throws IOException;
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
