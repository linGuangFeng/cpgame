package com.hd.cpgame.riocarnival.core;

import java.security.SecureRandom;

public final class SecureRoundRandom implements RandomSource {
    private final SecureRandom random = new SecureRandom();

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
