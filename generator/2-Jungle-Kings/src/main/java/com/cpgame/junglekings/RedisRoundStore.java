package com.cpgame.junglekings;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Platform Redis operations: ZADD index + RPUSH/LTRIM on write; ZRANGE + LINDEX on demo read. */
public interface RedisRoundStore extends AutoCloseable {
    void writeMember(int betType, int ratio, byte[] member, int maximumMembers) throws IOException;
    List<String> ratios(int betType) throws IOException;
    long listLength(int betType, int ratio) throws IOException;
    Optional<byte[]> readMember(int betType, int ratio, int offset) throws IOException;
    @Override void close() throws IOException;
}
