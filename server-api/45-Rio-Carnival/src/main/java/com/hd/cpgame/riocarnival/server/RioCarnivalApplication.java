package com.hd.cpgame.riocarnival.server;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Platform-managed v3 process; a single HTTP listener receives an injected port. */
@SpringBootApplication
public class RioCarnivalApplication {
    public static void main(String[] args)throws Exception {
        Map<String,String> options=new LinkedHashMap<String,String>();
        for(int i=0;i<args.length;i++) {
            String a=args[i];int eq=a.indexOf('=');
            if(eq>=0)options.put(a.substring(0,eq),a.substring(eq+1));
            else {if(i+1>=args.length)throw new IllegalArgumentException("Missing argument for "+a);options.put(a,args[++i]);}
        }
        for(String k:options.keySet())if(!Arrays.asList("--port","--config","--publish").contains(k))throw new IllegalArgumentException("Unsupported controller argument "+k);
        String raw=options.containsKey("--port")?options.get("--port"):System.getenv("PORT");
        if(raw==null)throw new IllegalArgumentException("Platform must inject --port or PORT");
        int port=Integer.parseInt(raw);if(port<50000||port>59999)throw new IllegalArgumentException("Controller port must be 50000-59999");
        Path config=Paths.get(options.getOrDefault("--config","dist/controller.properties")).toAbsolutePath().normalize();
        Properties properties=new Properties();try(Reader in=Files.newBufferedReader(config)){properties.load(in);}
        Map<String,Object> defaults=new LinkedHashMap<String,Object>();
        for(String k:properties.stringPropertyNames())defaults.put(k,properties.getProperty(k));
        String publish=options.get("--publish");
        Path publishPath=publish==null?config.getParent().resolve("../../../publish/45-rio-carnival").normalize():Paths.get(publish).toAbsolutePath().normalize();
        if(!Files.isRegularFile(publishPath.resolve("index.html")))throw new IllegalArgumentException("Original publish index.html missing");
        defaults.put("rio.publish-directory",publishPath.toString());
        defaults.put("rio.state-directory",config.getParent().resolve("data-v3").toString());
        defaults.put("server.tomcat.basedir",config.getParent().resolve("tomcat-v3").toString());
        defaults.put("server.address","0.0.0.0");defaults.put("server.port",port);
        defaults.put("spring.config.location",config.toUri().toString());
        SpringApplication app=new SpringApplication(RioCarnivalApplication.class);app.setDefaultProperties(defaults);
        app.run("--server.port="+port,"--rio.publish-directory="+publishPath);
    }
}
