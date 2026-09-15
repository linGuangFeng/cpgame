package com.cpgame.curupira.random;
import java.security.SecureRandom;
public final class SecureRandomSource implements RandomSource {
    private final SecureRandom random = new SecureRandom();
    @Override public int nextInt(int bound) { return random.nextInt(bound); }
}
