package com.hd.cpgame.riocarnival.server.web;

import java.net.URLEncoder;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class DemoController {
    @GetMapping("/demo")
    public ResponseEntity<Void> demo(HttpServletRequest request,@RequestParam(value="l",defaultValue="en") String language)throws Exception{
        if(!language.matches("en|pt|es|th|vi|id|bn|ko|fr|tr"))language="en";
        String host=request.getHeader("Host");if(host==null||!host.matches("[A-Za-z0-9.\\-\\[\\]:]+"))host=request.getServerName()+":"+request.getServerPort();
        String query="/?ai=local&btt=1&gid=45&l="+language+"&language="+language+"&sip="+URLEncoder.encode(host,"UTF-8")+"&t="+newLaunchToken();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION,query).build();
    }

    @GetMapping("/api/game/startup")
    public Envelope startup(HttpServletRequest request,@RequestParam("game_id") int gameId)throws Exception{
        if(gameId!=1401)throw new IllegalArgumentException("invalid game_id");String host=request.getHeader("Host");if(host==null||!host.matches("[A-Za-z0-9.\\-\\[\\]:]+"))host=request.getServerName()+":"+request.getServerPort();
        String url=request.getScheme()+"://"+host+"/?ai=local&btt=1&gid=45&l=en&language=en&sip="+URLEncoder.encode(host,"UTF-8")+"&t="+newLaunchToken();
        java.util.Map<String,Object>d=new java.util.LinkedHashMap<String,Object>();d.put("game_url",url);return Envelope.ok(d);
    }

    @GetMapping("/api/report/timing") public Envelope timing(){return Envelope.ok(new java.util.LinkedHashMap<String,Object>());}
    @PostMapping("/api/report/timing") public Envelope timingPost(){return Envelope.ok(new java.util.LinkedHashMap<String,Object>());}
    private static String newLaunchToken(){return "rio-local-"+java.util.UUID.randomUUID().toString();}
}
