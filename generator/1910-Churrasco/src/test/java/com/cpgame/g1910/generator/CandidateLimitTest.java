package com.cpgame.g1910.generator;
import com.cpgame.g1910.core.*;
import java.security.SecureRandom;
import java.util.Properties;
public class CandidateLimitTest {
 public static void main(String[] args) {
  var limits=new LoaderLimits(new Properties());
  var factory=new RoundFactory(new SecureRandom());
  var round=factory.ordinary(true);
  int units=ResultUtil.totalUnits(MinimalRoundFactCodec.encode(round));
  if(RedisLoader.acceptsRound(round,false,limits,units-1,10))throw new AssertionError("over-limit candidate accepted");
  if(!RedisLoader.acceptsRound(round,false,limits,units,10))throw new AssertionError("inclusive cap rejected");
  System.out.println("CandidateLimitTest passed");
 }
}
