package com.cpgame.batcha.g8;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class LoaderCliTest {
 @Test void adminAndLauncherConfigFormsMatch() {
  Path expected=Path.of("folder with spaces/generator.properties");
  assertEquals(expected,LoaderMain.configPath(new String[]{expected.toString()}));
  assertEquals(expected,LoaderMain.configPath(new String[]{"--config",expected.toString()}));
  assertEquals(expected,LoaderMain.configPath(new String[]{"--config="+expected}));
 }
 @Test void malformedOrExtraArgumentsAreRejected() {
  for(String[] args:new String[][]{{},{"--config"},{"--config="},{"--config","--seed=1"},{"a.properties","--seed=1"}})
   assertThrows(IllegalArgumentException.class,()->LoaderMain.configPath(args));
 }
}
