package com.cpgame.sharpshooter.server;
import java.nio.file.*;
import java.util.Properties;
/** Runs without a listener or Redis; checks the platform launch contract. */
public final class ControllerConfigProbe {
 public static void main(String[] args)throws Exception {
  String config=args[0], publish=args[1];
  for(String port:new String[]{"50000","59999"}) {
   Properties p=SharpshooterController.load(new String[]{"--config",config,"--port",port,"--publish",publish});
   require(port.equals(p.getProperty("server.port")),"injected port");
   require(Path.of(publish).toAbsolutePath().normalize().toString().equals(p.getProperty("publish.directory")),"injected publish");
  }
  Properties equal=SharpshooterController.load(new String[]{"--config="+config,"--port=51090","--publish="+publish});
  require("51090".equals(equal.getProperty("server.port")),"equals arguments");
  Properties relative=SharpshooterController.load(new String[]{"--config",config,"--port","51091"});
  require(Path.of(publish).toAbsolutePath().normalize().toString().equals(relative.getProperty("publish.directory")),"config-relative publish path");
  try {SharpshooterController.load(new String[]{"--config",config,"--publish"});throw new AssertionError("missing value accepted");}
  catch(IllegalArgumentException expected){}
  System.out.println("PASS: injected boundary ports, publish override, equals arguments, relative path, missing argument rejection");
 }
 private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
