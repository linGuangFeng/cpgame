package com.cpgame.saci.generator;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoaderMultiplierUnitsTest {
 @Test void comparesActualMultiplierAndPreservesInclusiveBounds() {
  Properties p=new Properties();
  p.setProperty("generation.normal-min-win-multiplier","50");
  p.setProperty("generation.normal-max-win-multiplier","1500");
  LoaderLimits limits=new LoaderLimits(p);
  assertTrue(limits.acceptsHundredths(false,5000));
  assertTrue(limits.acceptsHundredths(false,150000));
  assertFalse(limits.acceptsHundredths(false,4999));
  assertFalse(limits.acceptsHundredths(false,150001));
 }
}
