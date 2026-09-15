package com.cpgame.batcha.g32;
import java.nio.file.Path;
public class LoaderCliTest {
 public static void main(String[] ignored) {
  Path expected=Path.of("folder with spaces/generator.properties");
  for (String[] args : new String[][]{{expected.toString()},{"--config",expected.toString()},{"--config="+expected}})
   if (!expected.equals(LoaderMain.configPath(args))) throw new AssertionError("config path mismatch");
  for(String[] args:new String[][]{{},{"--config"},{"--config="},{"--config","--seed=1"},{"a.properties","--seed=1"}}) {
   try { LoaderMain.configPath(args); } catch (IllegalArgumentException expectedError) { continue; }
   throw new AssertionError("malformed arguments accepted");
  }
  System.out.println("LoaderCliTest passed");
 }
}
