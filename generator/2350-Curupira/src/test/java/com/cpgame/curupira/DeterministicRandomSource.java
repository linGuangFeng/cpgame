package com.cpgame.curupira;
import com.cpgame.curupira.random.RandomSource;
import java.util.Random;
/** Explicit test-only reproducibility input. */
final class DeterministicRandomSource implements RandomSource{
 private final Random random;
 DeterministicRandomSource(long value){random=new Random(value);}
 @Override public int nextInt(int bound){return random.nextInt(bound);}
}
