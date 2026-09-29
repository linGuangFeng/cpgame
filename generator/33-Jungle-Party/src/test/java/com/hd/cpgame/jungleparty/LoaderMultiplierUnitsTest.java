package com.hd.cpgame.jungleparty;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoaderMultiplierUnitsTest {
 @Test void configAndRedisShareIntegerBetUnitMultipliers() {
  Properties p=new Properties();
  p.setProperty("generation.normal-min-win-multiplier","50");
  p.setProperty("generation.normal-max-win-multiplier","1500");
  LoaderLimits limits=new LoaderLimits(p);
  assertTrue(limits.accepts(false,50));
  assertTrue(limits.accepts(false,1500));
  assertFalse(limits.accepts(false,49));
  assertFalse(limits.accepts(false,1501));
 }
}
