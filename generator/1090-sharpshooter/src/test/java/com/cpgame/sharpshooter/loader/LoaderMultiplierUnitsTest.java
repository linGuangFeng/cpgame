package com.cpgame.sharpshooter.loader;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoaderMultiplierUnitsTest {
 @Test void comparesActualMultiplierAndPreservesInclusiveBounds() {
  Properties p=new Properties();
  p.setProperty("generation.normal-min-win-multiplier","50");
  p.setProperty("generation.normal-max-win-multiplier","1500");
  LoaderLimits limits=new LoaderLimits(p);
  assertTrue(limits.acceptsHundredths(false,50));
  assertTrue(limits.acceptsHundredths(false,1500));
  assertFalse(limits.acceptsHundredths(false,49));
  assertFalse(limits.acceptsHundredths(false,1501));
 }
}
