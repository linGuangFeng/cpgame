package com.cpgame.fiesta.controller;

import com.cpgame.fiesta.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class ControllerMain {
    private final ObjectMapper json=new ObjectMapper();private final ConcurrentMap<String,SessionState> sessions=new ConcurrentHashMap<>();private final ProtocolProjection projection=new ProtocolProjection();private RedisRoundRepository redis;private Path publish;
    public static void main(String[] args)throws Exception{Properties p=load(args);new ControllerMain().start(p);}
    private void start(Properties p)throws Exception{if(!"3".equals(p.getProperty("controller.contract-version")))throw new IllegalArgumentException("contract v3 required");publish=Path.of(p.getProperty("publish.directory")).toAbsolutePath().normalize();redis=new RedisRoundRepository(p);int port=Integer.parseInt(p.getProperty("server.port"));if(port<50000||port>59999)throw new IllegalArgumentException("port must be dynamic 5xxxx");HttpServer server=HttpServer.create(new InetSocketAddress(p.getProperty("server.bind","0.0.0.0"),port),0);server.createContext("/",this::handle);server.setExecutor(Executors.newFixedThreadPool(8));Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);redis.close();}));server.start();System.out.printf("Electro Fiesta Controller v3 listening on %d, rulesHash=%s%n",port,GameRuleCore.RULES_HASH);}
    private void handle(HttpExchange x)throws IOException{try{addCors(x);if("OPTIONS".equals(x.getRequestMethod())){x.sendResponseHeaders(204,-1);return;}String path=x.getRequestURI().getPath();if(path.startsWith("/cp/")){api(x,path.substring(3));return;}staticFile(x,path);}catch(RedisRoundRepository.CacheEmptyException e){send(x,503,Map.of("code",50301,"msg","PREGENERATED_CACHE_EMPTY","detail",e.getMessage()));}catch(Exception e){send(x,500,Map.of("code",50000,"msg",e.getClass().getSimpleName()+":"+e.getMessage()));}finally{x.close();}}
    private void api(HttpExchange x,String path)throws IOException{Map<String,String> q=form(x);String sid=q.getOrDefault("token","demo");SessionState s=sessions.computeIfAbsent(sid,k->new SessionState());switch(path){
        case "/activity/getActivity"->send(x,200,ok(Map.of("free",Map.of("act_list",List.of(),"invite_act_have",0,"invite_end_time",0))));
        case "/config/initialData"->send(x,200,ok(config()));
        case "/account/getUserInfo"->send(x,200,ok(Map.of("token",sid,"gold",s.balance,"currency_symbol","$","user_config",Map.of("game_config",Map.of("open_sound",1,"open_music",1)))));
        case "/config/setGameConfig"->send(x,200,ok(Map.of()));
        case "/single_game.Game/initRoom"->send(x,200,ok(initRoom(s)));
        case "/single_game.Game/gameResult"->spin(x,s,q);
        case "/Goldgame/user_game_history","/goldgame/user_game_history"->send(x,200,ok(userGameHistory(s,q)));
        case "/goldgame/single_game_user_gold_history","/Goldgame/single_game_user_gold_history"->send(x,200,ok(goldHistory(s)));
        case "/goldgame/single_game_user_history","/Goldgame/single_game_user_history"->send(x,200,ok(dayHistory(s,q)));
        default->send(x,404,Map.of("code",404,"msg","UNKNOWN_API","path",path));}}
    private void spin(HttpExchange x,SessionState s,Map<String,String> q)throws IOException{synchronized(s){
        int type=Integer.parseInt(q.getOrDefault("type","1"));
        if(type==1){
            if(s.active!=null){send(x,409,Map.of("code",40901,"msg","ROUND_ACTIVE"));return;}
            s.active=redis.claim();s.deliveryIndex=0;s.roundId=System.currentTimeMillis();s.currentDeliveries.clear();
        }else if(type!=2||s.active==null){send(x,409,Map.of("code",40902,"msg","NO_ACTIVE_ROUND"));return;}
        double bet=Double.parseDouble(q.getOrDefault("bet",q.getOrDefault("b","0.8")));
        int level=Integer.parseInt(q.getOrDefault("level",q.getOrDefault("l","1")));
        Map<String,Object> data=projection.project(s,s.active,s.deliveryIndex,bet,level);
        s.currentDeliveries.add(new LinkedHashMap<>(data));
        boolean terminal=s.deliveryIndex==s.active.states().size()-1;
        if(terminal){
            s.history.addFirst(historyItem(s));
            while(s.history.size()>200)s.history.removeLast();
            s.active=null;s.deliveryIndex=0;s.currentDeliveries.clear();
        }else s.deliveryIndex++;
        send(x,200,ok(data));
    }}
    private Map<String,Object> historyItem(SessionState s){
        Map<String,Object> first=s.currentDeliveries.get(0);
        Map<String,Object> last=s.currentDeliveries.get(s.currentDeliveries.size()-1);
        long time=Instant.now().getEpochSecond();
        long day=Math.floorDiv(time,86400L)*86400L;
        double bg=number(first.get("bg"));
        double cg=money(number(last.get("tw"))-bg);
        Map<String,Object> ext=new LinkedHashMap<>();
        ext.put("act_id",0);ext.put("act_bet_gold",0);ext.put("act_type",0);
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("oid",s.roundId);
        item.put("order_id",s.roundId+"-2410");
        item.put("time",time);
        item.put("d",day);
        item.put("day",day);
        item.put("b",first.get("b"));
        item.put("l",first.get("l"));
        item.put("t",s.currentDeliveries.size()>1?2:1);
        item.put("bg",bg);
        item.put("bet_gold",bg);
        item.put("cg",money(cg));
        item.put("change_gold",money(cg));
        item.put("sg",first.get("sg"));
        item.put("eg",last.get("eg"));
        item.put("ext",ext);
        item.put("extend",ext);
        item.put("res",List.copyOf(s.currentDeliveries));
        return item;
    }
    private Map<String,Object> userGameHistory(SessionState s,Map<String,String> q){
        long start=parseLong(q.get("start"),0);
        long end=parseLong(q.get("end"),0);
        int page=Math.max(1,(int)parseLong(q.get("page"),1));
        int size=Math.max(1,(int)parseLong(q.get("page_size"),30));
        synchronized(s){
            List<Map<String,Object>> all=new ArrayList<>();
            for(Map<String,Object> row:s.history){
                long time=(long)number(row.get("time"));
                if(start>0&&time<start)continue;
                if(end>0&&time>end)continue;
                all.add(row);
            }
            double bet=0,change=0;
            for(Map<String,Object> row:all){bet+=number(row.get("bg"));change+=number(row.get("cg"));}
            int from=Math.min(all.size(),(page-1)*size);
            int to=Math.min(all.size(),from+size);
            Map<String,Object> totals=new LinkedHashMap<>();
            totals.put("bet_golds",money(bet));
            totals.put("change_golds",money(change));
            totals.put("total",all.size());
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("list",new ArrayList<>(all.subList(from,to)));
            data.put("totals",totals);
            data.put("page",page);
            data.put("page_size",size);
            data.put("total",all.size());
            return data;
        }
    }
    private Map<String,Object> goldHistory(SessionState s){
        long today=Math.floorDiv(Instant.now().getEpochSecond(),86400L)*86400L;
        synchronized(s){
            List<Map<String,Object>> days=new ArrayList<>();
            double totalBet=0,totalChange=0;
            for(int offset=0;offset<7;offset++){
                long day=today-offset*86400L;
                double bet=0,change=0;
                for(Map<String,Object> row:s.history){
                    if((long)number(row.get("d"))!=day)continue;
                    bet+=number(row.get("bg"));
                    change+=number(row.get("cg"));
                }
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("d",day);item.put("day",day);
                item.put("bet_gold",money(bet));item.put("cg",money(change));item.put("change_gold",money(change));
                days.add(item);
                totalBet+=bet;totalChange+=change;
            }
            Map<String,Object> statistics=new LinkedHashMap<>();
            statistics.put("total_bet_gold",money(totalBet));
            statistics.put("total_change_gold",money(totalChange));
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("list",days);
            data.put("statistics",statistics);
            return data;
        }
    }
    private Map<String,Object> dayHistory(SessionState s,Map<String,String> q){
        long day=parseLong(q.get("day"),Math.floorDiv(Instant.now().getEpochSecond(),86400L)*86400L);
        int page=Math.max(1,(int)parseLong(q.get("page"),1));
        int size=Math.max(1,(int)parseLong(q.get("page_size"),30));
        synchronized(s){
            List<Map<String,Object>> all=new ArrayList<>();
            for(Map<String,Object> row:s.history)if((long)number(row.get("d"))==day)all.add(row);
            double bet=0,change=0;
            for(Map<String,Object> row:all){bet+=number(row.get("bg"));change+=number(row.get("cg"));}
            int from=Math.min(all.size(),(page-1)*size);
            int to=Math.min(all.size(),from+size);
            Map<String,Object> statistics=new LinkedHashMap<>();
            statistics.put("total_bet_gold",money(bet));
            statistics.put("total_change_gold",money(change));
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("list",new ArrayList<>(all.subList(from,to)));
            data.put("statistics",statistics);
            return data;
        }
    }
    private static double number(Object value){
        if(value instanceof Number n)return n.doubleValue();
        return value==null?0:Double.parseDouble(String.valueOf(value));
    }
    private static long parseLong(String raw,long fallback){
        if(raw==null||raw.isBlank())return fallback;
        return Long.parseLong(raw);
    }
    private static double money(double x){return Math.round(x*100.0)/100.0;}
    private Map<String,Object> config(){return Map.of("game_address",Map.of(),"game_info",Map.of("bet_gold",List.of(.08,.8,3,10),"buy_free_max_bet",-1,"default_bet_gold",0,"default_level",10,"game_way",List.of(Map.of("max_bet_gold","0.00","min_bet_gold","0.00","way_id",241010000,"win_multi","1.00")),"gid",2410,"least_gold",0,"name","Electro Fiesta","status","1"),"game_server",Map.of(),"initial_config",Map.of("bd_bet_count",2,"current_sys_time",Instant.now().getEpochSecond(),"is_debug",false,"is_stopgs",0,"user_on_hook_time",600,"version",1745909504),"language","en-us","r",1,"zone",0);}
    private Map<String,Object> initRoom(SessionState s){int[] b={2,3,5,10,20,50,3,5,10};Map<String,Object> res=Map.of("ds",0,"ps",Arrays.stream(b).boxed().toList(),"tws",0,"wa",List.of());return new LinkedHashMap<>(Map.ofEntries(Map.entry("b",.8),Map.entry("bet",.8),Map.entry("bet_gold",4),Map.entry("bg",4),Map.entry("cg",0),Map.entry("change_gold",0),Map.entry("cl",0),Map.entry("eg",s.balance),Map.entry("end_gold",s.balance),Map.entry("f",List.of()),Map.entry("l",1),Map.entry("level",1),Map.entry("o",0),Map.entry("oid",0),Map.entry("res",res),Map.entry("rid",0),Map.entry("sg",s.balance),Map.entry("small_game_type",0),Map.entry("start_gold",s.balance),Map.entry("t",1),Map.entry("tw",0),Map.entry("u",24100001)));}
    private Map<String,Object> ok(Object data){return Map.of("code",0,"data",data,"msg","success","time",String.valueOf(Instant.now().getEpochSecond()));}
    private void staticFile(HttpExchange x,String raw)throws IOException{String path=raw;if(path.equals("/")){String host=x.getRequestHeaders().getFirst("Host");x.getResponseHeaders().set("Location","/v2/2410/index.html?gid=2410&language=pt-br&token=demo&ai=demo&sip="+host);x.sendResponseHeaders(302,-1);return;}if(path.equals("/v2/reportv2.js"))path="/v2/reportv2.js";else if(path.startsWith("/v2/2410/"))path=path.substring("/v2/2410".length());Path file=publish.resolve(path.substring(1)).normalize();if(!file.startsWith(publish)||!Files.isRegularFile(file)){send(x,404,Map.of("code",404,"msg","STATIC_NOT_FOUND"));return;}byte[] body=Files.readAllBytes(file);x.getResponseHeaders().set("Content-Type",content(file));x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);}
    private String content(Path p){String s=p.toString().toLowerCase(Locale.ROOT);if(s.endsWith(".html"))return"text/html; charset=utf-8";if(s.endsWith(".js"))return"application/javascript; charset=utf-8";if(s.endsWith(".json"))return"application/json";if(s.endsWith(".png"))return"image/png";if(s.endsWith(".jpg"))return"image/jpeg";if(s.endsWith(".mp3"))return"audio/mpeg";return"application/octet-stream";}
    private Map<String,String> form(HttpExchange x)throws IOException{String raw=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);Map<String,String> out=new LinkedHashMap<>();for(String part:raw.split("&")){if(part.isBlank())continue;String[] kv=part.split("=",2);out.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv.length>1?kv[1]:"",StandardCharsets.UTF_8));}return out;}
    private void send(HttpExchange x,int status,Object value)throws IOException{byte[] body=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,body.length);x.getResponseBody().write(body);}
    private void addCors(HttpExchange x){x.getResponseHeaders().set("Access-Control-Allow-Origin","*");x.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type");}
    private static Properties load(String[] args)throws IOException{
        Properties overrides=new Properties();
        String file="demo-controller.properties";
        boolean explicitConfig=false;
        for(int i=0;i<args.length;i++){
            String a=args[i];
            if("--config".equals(a)){
                if(++i>=args.length)throw new IllegalArgumentException("--config requires a path");
                file=args[i];explicitConfig=true;
            }else if(a.startsWith("--config=")){
                file=a.substring("--config=".length());explicitConfig=true;
            }else if("--port".equals(a)||"--server.port".equals(a)){
                if(++i>=args.length)throw new IllegalArgumentException(a+" requires a value");
                overrides.setProperty("server.port",args[i]);
            }else if(a.startsWith("--port=")||a.startsWith("--server.port=")){
                overrides.setProperty("server.port",a.substring(a.indexOf('=')+1));
            }else if("--publish".equals(a)){
                if(++i>=args.length)throw new IllegalArgumentException("--publish requires a path");
                overrides.setProperty("publish.directory",args[i]);
            }else if("--bind".equals(a)){
                if(++i>=args.length)throw new IllegalArgumentException("--bind requires a value");
                overrides.setProperty("server.bind",args[i]);
            }else if(a.matches("\\d{5}")){
                // 兼容平台旧启动器传入的单独位置端口；绝不能将 50004 当作配置文件名。
                overrides.setProperty("server.port",a);
            }else if(!a.startsWith("--")&&!explicitConfig){
                file=a;explicitConfig=true;
            }else if(a.startsWith("--")&&a.contains("=")){
                String[] kv=a.substring(2).split("=",2);overrides.setProperty(kv[0],kv[1]);
            }else{
                throw new IllegalArgumentException("unsupported argument: "+a);
            }
        }
        if(!explicitConfig&&!Files.isRegularFile(Path.of(file))&&Files.isRegularFile(Path.of("dist",file)))
            file=Path.of("dist",file).toString();
        Properties p=new Properties();
        try(Reader r=new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)){p.load(r);}
        p.putAll(overrides);
        String env=System.getenv("PORT");if(env!=null&&!env.isBlank())p.setProperty("server.port",env);
        return p;
    }
}
