package com.cpgame.g2110.server;
import com.cpgame.g2110.core.GameRuleCore;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Checks each indexed publish file through the production static handler, without a listener. */
public final class ControllerStaticEvidenceCheck {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("expected publish directory and manifest");
  ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"bounded-static-check");t.setDaemon(true);return t;});
  try{
   var json=new ObjectMapper();
   JsonNode manifest=io.submit(()->json.readTree(Files.readString(Path.of(args[1])))).get(10,TimeUnit.SECONDS);
   var controller=new ControllerMain();Field publish=ControllerMain.class.getDeclaredField("publish");publish.setAccessible(true);publish.set(controller,Path.of(args[0]));
   Method handle=ControllerMain.class.getDeclaredMethod("handle",HttpExchange.class);handle.setAccessible(true);
   List<Map<String,Object>> files=new ArrayList<>();int success=0;long bytes=0;
   for(JsonNode item:manifest.path("files")){
    String relative=item.path("path").asText();
    ControllerApiEvidenceCheck.Exchange x=new ControllerApiEvidenceCheck.Exchange("GET","/"+relative,"");
    Future<?> task=io.submit(()->{try{handle.invoke(controller,x);}catch(Exception e){throw new RuntimeException(e);}});
    try{task.get(10,TimeUnit.SECONDS);}catch(TimeoutException timeout){
     task.cancel(true);System.out.println(json.writeValueAsString(Map.of("status","IO_TIMEOUT","path",Path.of(args[0]).resolve(relative).toString(),"operation","production static handler read","timeoutSeconds",10,"checkedFiles",files)));return;
    }
    byte[] body=x.output.toByteArray();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    boolean match=x.status==200&&body.length==item.path("bytes").asInt()&&hash.equals(item.path("sha256").asText());
    if(!match)throw new IllegalStateException("static mismatch "+relative+" status="+x.status+" bytes="+body.length+" hash="+hash);
    String type=x.response.getFirst("Content-Type");
    if(relative.endsWith(".js")&&!type.startsWith("application/javascript"))throw new IllegalStateException("script MIME "+relative);
    if(relative.endsWith(".json")&&!type.startsWith("application/json"))throw new IllegalStateException("JSON MIME "+relative);
    files.add(Map.of("path",relative,"status",x.status,"bytes",body.length,"sha256",hash,"contentType",type));
    success++;bytes+=body.length;
    if(success%100==0)System.out.println("static files verified: "+success);
   }
   for(String alias:List.of("/fixed/","/fixed/index.html")){
    ControllerApiEvidenceCheck.Exchange x=new ControllerApiEvidenceCheck.Exchange("GET",alias,"");
    io.submit(()->{try{handle.invoke(controller,x);}catch(Exception e){throw new RuntimeException(e);}}).get(10,TimeUnit.SECONDS);
    if(x.status!=200)throw new IllegalStateException("entry alias "+alias);
   }
   ControllerApiEvidenceCheck.Exchange traversal=new ControllerApiEvidenceCheck.Exchange("GET","/../controller.properties","");
   handle.invoke(controller,traversal);if(traversal.status!=404)throw new IllegalStateException("path escapes publish");
   System.out.println(json.writeValueAsString(Map.of("status","PASS","observedAt",Instant.now().toString(),"rulesHash",GameRuleCore.RULES_HASH,"filesVerified",success,"bytesVerified",bytes,"entryAliases","PASS","pathTraversal","404","listenersStarted",0,"verificationScope","Production static handler matches existing manifest; not live HTTP closure or original provenance","files",files)));
  }finally{io.shutdownNow();}
 }
}
