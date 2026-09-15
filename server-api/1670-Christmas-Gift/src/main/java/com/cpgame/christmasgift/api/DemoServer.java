package com.cpgame.christmasgift.api;

import com.cpgame.christmasgift.core.GameRuleCore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public final class DemoServer {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ServerConfig config;
    private final StateStore stateStore;
    private final CacheRepository cache;
    private final ProviderPresenter presenter = new ProviderPresenter();
    private final SecureRandom random = new SecureRandom();

    private DemoServer(ServerConfig config) throws Exception {
        this.config = config;
        this.stateStore = new StateStore(config.stateFile());
        this.cache = new CacheRepository(config);
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        ServerConfig config = ServerConfig.load(arguments.config, arguments.publish);
        DemoServer application = new DemoServer(config);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", arguments.port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        application.routes(server);
        server.start();
        System.out.printf("READY game=1670 port=%d bind=0.0.0.0 publish=%s rulesHash=%s%n",
            arguments.port,config.publishDirectory(),GameRuleCore.RULES_HASH);
    }

    private void routes(HttpServer server) {
        server.createContext("/api/health", exchange -> safe(exchange, this::health));
        server.createContext("/api/spin", exchange -> safe(exchange, this::spin));
        server.createContext("/api/history", exchange -> safe(exchange, this::historyApi));
        server.createContext("/api/test/select", exchange -> safe(exchange, this::selectForQa));
        server.createContext("/cp/config/initialData", exchange -> safe(exchange, this::initialData));
        server.createContext("/cp/account/getUserInfo", exchange -> safe(exchange, this::userInfo));
        server.createContext("/cp/account/getBalance", exchange -> safe(exchange, this::balance));
        server.createContext("/cp/config/setGameConfig", exchange -> safe(exchange, this::success));
        server.createContext("/cp/heart/heart_check", exchange -> safe(exchange, this::success));
        server.createContext("/cp/single_game.Game/initRoom", exchange -> safe(exchange, this::initRoom));
        server.createContext("/cp/single_game.Game/gameResult", exchange -> safe(exchange, this::spin));
        server.createContext("/cp/order/log_list", exchange -> safe(exchange, this::historyList));
        server.createContext("/cp/order/log_view", exchange -> safe(exchange, this::historyView));
        server.createContext("/history", exchange -> safe(exchange, this::historyPage));
        server.createContext("/", exchange -> safe(exchange, this::staticFile));
    }

    private void safe(HttpExchange exchange, Handler handler) throws IOException {
        cors(exchange.getResponseHeaders());
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204,-1); exchange.close(); return; }
        try { handler.handle(exchange); }
        catch (Exception failure) {
            failure.printStackTrace(System.err);
            sendJson(exchange,503,Map.of("code",503,"data",Map.of(),"msg",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(),"time",epoch()));
        }
    }

    private void health(HttpExchange exchange) throws Exception {
        sendJson(exchange,200,Map.of("status","UP","gameId",1670,"rulesHash",GameRuleCore.RULES_HASH,
            "redis",config.redisHost()+":"+config.redisPort()+"/"+config.redisDatabase(),"queuedQaSelections",stateStore.queuedSelections()));
    }

    private void initialData(HttpExchange exchange) throws Exception {
        String language = parameters(exchange).getOrDefault("language","en-us");
        String host = requestHost(exchange);
        Map<String,Object> gameInfo = linked("bet_gold",List.of(0.08,0.8,3,10),"buy_free_max_bet",-1,"default_bet_gold",0,
            "default_level",10,"game_way",List.of(linked("max_bet_gold","0.00","min_bet_gold","0.00","way_id",167010000,"win_multi","1.00")),
            "gid",1670,"least_gold",0,"name","Christmas Gift","status","1");
        Map<String,Object> gameServer = linked("gos_host","","gos_port","8976","gos_sport","","gs_host","","gs_host1","",
            "gs_port","","gs_port1","","gs_push_host","","gs_push_port","","gs_push_sport","28966","gs_sport","","gs_sport1","",
            "ngs_switch",0,"ps_host","","ps_port","","snake_gs_host","","snake_gs_port","","snake_gs_sport","","snake_gs_url","",
            "web_detail_url","http://"+host+"/history?language="+url(language));
        Map<String,Object> data = linked("game_address",Map.of("ship_address_config",Map.of()),"game_info",gameInfo,"game_server",gameServer,
            "initial_config",linked("bd_bet_count",2,"current_sys_time",Long.parseLong(epoch()),"is_debug",false,"is_stopgs",0,"user_on_hook_time",600,"version",1745909504),
            "language",language,"r",1,"zone",0);
        sendEnvelope(exchange,data);
    }

    private void userInfo(HttpExchange exchange) throws Exception {
        Map<String,String> p=parameters(exchange); String token=p.getOrDefault("token","local-1670");
        Map<String,Object> gameConfig=linked("ac",List.of(),"open_auto_spin",1,"open_free_buy",1,"open_music",1,"open_paytable",0,"open_sound",1);
        sendEnvelope(exchange,linked("currency_symbol","R$","day_first_login",0,"first_gold",null,"gid",1670,
            "gold",stateStore.state().balance,"is_guide",0,"nickname","Local Player","token",token,"total_recharge","0","uid",1670,
            "user_config",Map.of("game_config",gameConfig)));
    }

    private void balance(HttpExchange exchange) throws Exception { sendEnvelope(exchange,linked("gold",stateStore.state().balance)); }
    private void success(HttpExchange exchange) throws Exception { sendEnvelope(exchange,Map.of()); }

    private synchronized void initRoom(HttpExchange exchange) throws Exception {
        String existing = stateStore.state().lastDataJson;
        if (existing == null) {
            var round = cache.take(CacheRepository.Pool.ORDINARY_LOSS);
            BigDecimal balance = stateStore.state().balance;
            Map<String,Object> data = presenter.data(round,new BigDecimal("0.08"),10,balance,stateStore.state().nextOid);
            data.put("bet_gold",BigDecimal.ZERO); data.put("change_gold",BigDecimal.ZERO);
            data.put("end_gold",balance); data.put("start_gold",balance); data.put("total_win",BigDecimal.ZERO);
            sendEnvelope(exchange,data); return;
        }
        Map<String,Object> data=json.readValue(existing,new TypeReference<>(){});
        sendEnvelope(exchange,data);
    }

    private synchronized void spin(HttpExchange exchange) throws Exception {
        Map<String,String> p=parameters(exchange);
        BigDecimal bet=new BigDecimal(p.getOrDefault("bet","0.08"));
        int level=Integer.parseInt(p.getOrDefault("level","10"));
        String requestId=exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if(requestId==null||requestId.isBlank()) requestId=p.get("request_id");
        String previous=stateStore.idempotent(requestId);
        if(previous!=null){sendRawJson(exchange,200,previous);return;}
        CacheRepository.Pool pool=stateStore.pollSelection();
        if(pool==null) pool=randomPool();
        String response=settle(bet,level,requestId,pool);
        sendRawJson(exchange,200,response);
    }

    private String settle(BigDecimal bet,int level,String requestId,CacheRepository.Pool pool) throws Exception {
        BigDecimal betGold=GameRuleCore.betGold(bet,level);
        if(stateStore.state().balance.compareTo(betGold)<0) throw new IllegalStateException("insufficient local balance");
        var round=cache.take(pool);
        long oid=stateStore.state().nextOid++;
        BigDecimal start=stateStore.state().balance;
        Map<String,Object> data=presenter.data(round,bet,level,start,oid);
        stateStore.state().balance=(BigDecimal)data.get("end_gold");
        String dataJson=json.writeValueAsString(data);
        stateStore.state().lastDataJson=dataJson;
        String response=json.writeValueAsString(envelope(data));
        stateStore.rememberIdempotent(requestId,response);
        stateStore.addHistory(StateStore.history(oid,pool.name(),betGold,(BigDecimal)data.get("total_win"),stateStore.state().balance,dataJson));
        stateStore.save();
        return response;
    }

    private CacheRepository.Pool randomPool(){
        if(random.nextDouble()<config.featureProbability())return CacheRepository.Pool.FEATURE;
        return random.nextDouble()<config.ordinaryLossProbability()?CacheRepository.Pool.ORDINARY_LOSS:CacheRepository.Pool.ORDINARY_WIN;
    }

    private void selectForQa(HttpExchange exchange)throws Exception{
        Map<String,String> p=parameters(exchange); String mode=p.getOrDefault("mode","feature").toLowerCase(Locale.ROOT);
        int count=Math.max(1,Math.min(20,Integer.parseInt(p.getOrDefault("count","1"))));
        CacheRepository.Pool pool=switch(mode){case"ordinary-loss","loss"->CacheRepository.Pool.ORDINARY_LOSS;case"ordinary-win","win"->CacheRepository.Pool.ORDINARY_WIN;case"feature","special"->CacheRepository.Pool.FEATURE;default->throw new IllegalArgumentException("mode must be loss, win, or feature");};
        if(Boolean.parseBoolean(p.getOrDefault("replace","false")))stateStore.clearSelections();
        stateStore.enqueue(pool,count);stateStore.save();sendJson(exchange,200,Map.of("result","QUEUED","mode",pool.name(),"count",count,"queued",stateStore.queuedSelections()));
    }

    private void historyApi(HttpExchange exchange)throws Exception{
        String path=exchange.getRequestURI().getPath();
        if(path.matches("/api/history/\\d+")){long oid=Long.parseLong(path.substring(path.lastIndexOf('/')+1));StateStore.HistoryRecord item=stateStore.history(oid);if(item==null){sendJson(exchange,404,Map.of("error","not found"));return;}sendJson(exchange,200,historyDetail(item));return;}
        List<Map<String,Object>> rows=new ArrayList<>();for(StateStore.HistoryRecord item:stateStore.history())rows.add(historySummary(item));sendJson(exchange,200,Map.of("items",rows));
    }

    private void historyList(HttpExchange exchange)throws Exception{
        List<Map<String,Object>> rows=new ArrayList<>();for(StateStore.HistoryRecord item:stateStore.history())rows.add(linked("oid",item.oid(),"bet_gold",item.betGold(),"total_win",item.totalWin(),"end_gold",item.endGold(),"created_at",item.createdAt(),"type",item.mode()));
        sendEnvelope(exchange,linked("list",rows,"total",rows.size(),"page",1));
    }

    private void historyView(HttpExchange exchange)throws Exception{
        String raw=parameters(exchange).getOrDefault("oid","0");long oid=Long.parseLong(raw);StateStore.HistoryRecord item=stateStore.history(oid);
        sendEnvelope(exchange,item==null?Map.of():json.readValue(item.dataJson(),new TypeReference<Map<String,Object>>(){}));
    }

    private Map<String,Object> historySummary(StateStore.HistoryRecord item){return linked("oid",Long.toString(item.oid()),"mode",item.mode(),"betGold",item.betGold(),"totalWin",item.totalWin(),"endGold",item.endGold(),"createdAt",item.createdAt());}
    private Map<String,Object> historyDetail(StateStore.HistoryRecord item)throws Exception{return linked("summary",historySummary(item),"result",json.readValue(item.dataJson(),new TypeReference<Map<String,Object>>(){}));}

    private void historyPage(HttpExchange exchange)throws Exception{
        String html="""
          <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Christmas Gift History</title><style>body{margin:0;background:#07172b;color:#fff;font:16px system-ui}header{padding:16px;background:#8d1429;font-size:22px}button{padding:10px 14px;background:#ddb24b;border:0;border-radius:8px}#app{padding:16px}.row{padding:12px;margin:8px 0;background:#143252;border-radius:10px}.muted{color:#a9bdd0}pre{white-space:pre-wrap;word-break:break-word;background:#03101e;padding:12px;border-radius:8px}</style></head>
          <body><header>Christmas Gift · History</header><main id="app">Loading…</main><script>
          const app=document.getElementById('app');async function list(){history.replaceState({},'',location.pathname+location.search);const d=await fetch('/api/history').then(r=>r.json());app.innerHTML=d.items.length?d.items.map(x=>`<div class="row" onclick="detail('${x.oid}')"><b>#${x.oid}</b><br>${x.mode}<br><span class="muted">Bet ${x.betGold} · Win ${x.totalWin} · Balance ${x.endGold}</span></div>`).join(''):'No rounds yet';}
          async function detail(id){history.pushState({detail:1},'',location.pathname+'?oid='+id);const d=await fetch('/api/history/'+id).then(r=>r.json());app.innerHTML=`<button onclick="history.back()">← Back</button><h2>#${id}</h2><pre>${JSON.stringify(d.result,null,2)}</pre>`;}addEventListener('popstate',list);const q=new URLSearchParams(location.search);q.get('oid')?detail(q.get('oid')):list();
          </script></body></html>
          """;
        send(exchange,200,"text/html; charset=utf-8",html.getBytes(StandardCharsets.UTF_8));
    }

    private void staticFile(HttpExchange exchange)throws Exception{
        String raw=URLDecoder.decode(exchange.getRequestURI().getPath(),StandardCharsets.UTF_8);if(raw.equals("/"))raw="/index.html";
        Path file=config.publishDirectory().resolve(raw.substring(1)).normalize();
        if(!file.startsWith(config.publishDirectory())||!Files.isRegularFile(file)){send(exchange,404,"text/plain; charset=utf-8","Not found".getBytes(StandardCharsets.UTF_8));return;}
        send(exchange,200,mime(file),Files.readAllBytes(file));
    }

    private Map<String,String> parameters(HttpExchange exchange)throws IOException{
        Map<String,String> result=new LinkedHashMap<>();parseQuery(exchange.getRequestURI().getRawQuery(),result);
        if(!"GET".equalsIgnoreCase(exchange.getRequestMethod())&&!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())){
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);String type=exchange.getRequestHeaders().getFirst("Content-Type");
            if(type!=null&&type.toLowerCase(Locale.ROOT).contains("application/json")&&!body.isBlank()){Map<String,Object> values=json.readValue(body,new TypeReference<>(){});values.forEach((k,v)->result.put(k,String.valueOf(v)));}else parseQuery(body,result);
        }return result;
    }
    private static void parseQuery(String raw,Map<String,String> result){if(raw==null||raw.isBlank())return;for(String item:raw.split("&")){String[] pair=item.split("=",2);result.put(urlDecode(pair[0]),pair.length==2?urlDecode(pair[1]):"");}}
    private static String urlDecode(String value){return URLDecoder.decode(value,StandardCharsets.UTF_8);}
    private static String url(String value){return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String requestHost(HttpExchange exchange){String host=exchange.getRequestHeaders().getFirst("Host");return host==null?"127.0.0.1:"+exchange.getLocalAddress().getPort():host;}
    private static String epoch(){return Long.toString(Instant.now().getEpochSecond());}
    private Map<String,Object> envelope(Object data){return linked("code",0,"data",data,"msg","success","time",epoch());}
    private void sendEnvelope(HttpExchange exchange,Object data)throws Exception{sendJson(exchange,200,envelope(data));}
    private void sendJson(HttpExchange exchange,int status,Object value)throws IOException{sendRawJson(exchange,status,json.writeValueAsString(value));}
    private void sendRawJson(HttpExchange exchange,int status,String value)throws IOException{send(exchange,status,"application/json; charset=utf-8",value.getBytes(StandardCharsets.UTF_8));}
    private static void send(HttpExchange exchange,int status,String contentType,byte[] body)throws IOException{Headers h=exchange.getResponseHeaders();h.set("Content-Type",contentType);h.set("Cache-Control","no-store");exchange.sendResponseHeaders(status,"HEAD".equalsIgnoreCase(exchange.getRequestMethod())?-1:body.length);if(!"HEAD".equalsIgnoreCase(exchange.getRequestMethod()))exchange.getResponseBody().write(body);exchange.close();}
    private static void cors(Headers h){h.set("Access-Control-Allow-Origin","*");h.set("Access-Control-Allow-Methods","GET,POST,OPTIONS");h.set("Access-Control-Allow-Headers","Content-Type,Idempotency-Key");}
    private static String mime(Path file){String name=file.getFileName().toString().toLowerCase(Locale.ROOT);if(name.endsWith(".html"))return"text/html; charset=utf-8";if(name.endsWith(".js"))return"application/javascript; charset=utf-8";if(name.endsWith(".css"))return"text/css; charset=utf-8";if(name.endsWith(".json"))return"application/json; charset=utf-8";if(name.endsWith(".png"))return"image/png";if(name.endsWith(".jpg")||name.endsWith(".jpeg"))return"image/jpeg";if(name.endsWith(".webp"))return"image/webp";if(name.endsWith(".mp3"))return"audio/mpeg";if(name.endsWith(".ogg"))return"audio/ogg";if(name.endsWith(".ttf"))return"font/ttf";if(name.endsWith(".wasm"))return"application/wasm";return"application/octet-stream";}
    private static Map<String,Object> linked(Object... values){LinkedHashMap<String,Object> map=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]);return map;}
    @FunctionalInterface private interface Handler{void handle(HttpExchange exchange)throws Exception;}
    private record Arguments(int port,Path config,Path publish){static Arguments parse(String[] args){Integer port=null;Path config=null;Path publish=null;for(int i=0;i<args.length;i++){switch(args[i]){case"--port"->port=Integer.parseInt(args[++i]);case"--config"->config=Path.of(args[++i]);case"--publish"->publish=Path.of(args[++i]);default->throw new IllegalArgumentException("unknown argument "+args[i]);}}if(port==null){String env=System.getenv("PORT");if(env!=null&&!env.isBlank())port=Integer.parseInt(env);}if(port==null||port<50000||port>59999)throw new IllegalArgumentException("dynamic port 50000..59999 required");if(config==null)throw new IllegalArgumentException("--config required");return new Arguments(port,config,publish);}}
}
