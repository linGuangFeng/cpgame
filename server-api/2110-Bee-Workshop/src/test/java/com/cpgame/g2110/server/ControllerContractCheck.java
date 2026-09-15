package com.cpgame.g2110.server;

import java.nio.file.Path;
import java.util.Properties;

/** Parse the real platform contract without starting Redis or a listener. */
public final class ControllerContractCheck {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("expected config and publish paths");
        Properties split=ControllerMain.load(new String[]{"--config",args[0],"--port","50081","--publish",args[1]});
        if(!split.getProperty("server.port").equals("50081")||!Path.of(split.getProperty("publish.directory")).equals(Path.of(args[1]).toAbsolutePath()))
            throw new IllegalStateException("split platform arguments rejected");
        Properties equals=ControllerMain.load(new String[]{"--config="+args[0],"--port=59999","--publish="+args[1]});
        if(!equals.getProperty("server.port").equals("59999"))throw new IllegalStateException("equals platform arguments rejected");
        for(String bad:new String[]{"49999","60000"}){
            boolean failed=false;try{ControllerMain.load(new String[]{"--config",args[0],"--port",bad});}catch(IllegalArgumentException expected){failed=true;}
            if(!failed)throw new IllegalStateException("out-of-range port accepted");
        }
        System.out.println("{\"controllerContract\":3,\"splitArguments\":\"PASS\",\"equalsArguments\":\"PASS\",\"invalidPorts\":\"PASS\",\"listenersStarted\":0,\"redisAccessed\":false}");
    }
}
