package com.cpgame.curupira.core;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
final class RoundIdGenerator{
 private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis()*1000000L+new SecureRandom().nextInt(1000000));
 long next(){return sequence.incrementAndGet();}
}
