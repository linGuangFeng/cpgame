package com.cpgame.clubgoddess.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClubGoddessApiApplication {
    public static void main(String[] args) {
        java.util.List<String> mapped=new java.util.ArrayList<>();
        for(int i=0;i<args.length;i++){
            if("--port".equals(args[i])&&i+1<args.length)mapped.add("--server.port="+args[++i]);
            else if(args[i].startsWith("--port="))mapped.add("--server.port="+args[i].substring(7));
            else if("--config".equals(args[i])&&i+1<args.length)mapped.add("--spring.config.additional-location=file:"+args[++i]);
            else if(args[i].startsWith("--config="))mapped.add("--spring.config.additional-location=file:"+args[i].substring(9));
            else if("--publish".equals(args[i])&&i+1<args.length)mapped.add("--demo.publish-directory="+args[++i]);
            else if(args[i].startsWith("--publish="))mapped.add("--demo.publish-directory="+args[i].substring(10));
            else mapped.add(args[i]);
        }
        String portArgument=mapped.stream().filter(v->v.startsWith("--server.port=")).reduce((a,b)->b).orElse(null);
        String portValue=portArgument==null?System.getenv("PORT"):portArgument.substring("--server.port=".length());
        if(portValue==null)throw new IllegalArgumentException("Controller requires platform --port or PORT");
        int port=Integer.parseInt(portValue);
        if(port<50000||port>59999)throw new IllegalArgumentException("Controller port must be 50000-59999");
        if(portArgument==null)mapped.add("--server.port="+port);
        SpringApplication.run(ClubGoddessApiApplication.class,mapped.toArray(String[]::new));
    }
}
