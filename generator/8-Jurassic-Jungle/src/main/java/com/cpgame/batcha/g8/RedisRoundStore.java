package com.cpgame.batcha.g8;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Platform Redis operations: ZADD+RPUSH/LTRIM on write; ZRANGE+LPOP on Demo claim. */
public interface RedisRoundStore extends AutoCloseable {
    void writeMember(boolean special, int ratio, byte[] member, int maximumMembers) throws IOException;
    List<String> ratios(boolean special) throws IOException;
    long listLength(boolean special, int ratio) throws IOException;
    Optional<byte[]> popMember(boolean special, int ratio) throws IOException;
    Optional<byte[]> readMember(boolean special, int ratio, int offset) throws IOException;
    @Override void close() throws IOException;
}
