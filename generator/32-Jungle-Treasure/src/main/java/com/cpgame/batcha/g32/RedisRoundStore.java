package com.cpgame.batcha.g32;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface RedisRoundStore extends AutoCloseable {
    void writeMember(boolean special, int ratio, byte[] member, int maximumMembers) throws IOException;
    List<String> ratios(boolean special) throws IOException;
    long listLength(boolean special, int ratio) throws IOException;
    Optional<byte[]> readMember(boolean special, int ratio, int offset) throws IOException;
    @Override void close() throws IOException;
}
