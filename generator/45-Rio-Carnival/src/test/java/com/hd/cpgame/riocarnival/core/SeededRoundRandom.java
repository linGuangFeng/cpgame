package com.hd.cpgame.riocarnival.core;

import java.util.Random;

/** 仅存在于测试源码，绝不进入正式 Loader 或试玩 JAR。 */
final class SeededRoundRandom implements RandomSource {
    private final Random random;
    SeededRoundRandom(long seed) { this.random=new Random(seed); }
    @Override public int nextInt(int bound) { return random.nextInt(bound); }
}
