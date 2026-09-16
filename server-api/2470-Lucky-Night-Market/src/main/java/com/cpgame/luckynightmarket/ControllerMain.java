package com.cpgame.luckynightmarket;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.sun.net.httpserver.*;
import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Original protocol adapter. Every paid round is one complete immutable Redis member. */
public final class ControllerMain {
    private static final String GAME="2470", DIRECTORY="2470-Lucky-Night-Market";
    private static final String PER="PerKeyList_008002470", MARY="MaryKeyList_008002470", PRE="PreKeyList_108002470";
    private static final BigDecimal FIVE=BigDecimal.valueOf(5);
    private static final List<BigDecimal> BETS=List.of(new BigDecimal("0.08"),new BigDecimal("0.8"),new BigDecimal("3"),new BigDecimal("10"));
    private final Properties properties; private final Path publish; private final int port;
    private final SecureRandom random=new SecureRandom(); private final AtomicLong ids=new AtomicLong(System.currentTimeMillis()*1000L);
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final AtomicLong redisReads=new AtomicLong(); private final AtomicLong paidRounds=new AtomicLong();
    public ControllerMain(Properties p,Path publish,int port){if(!"8002470".equals(p.getProperty("redis.game-id","8002470")))throw new IllegalArgumentException("redis.game-id must be 2470");this.properties=p;this.publish=publish.toAbsolutePath().normalize();this.port=port;}
    public static void main(String[] args)throws Exception {
        Map<String,String> options=new LinkedHashMap<>();for(int i=0;i<args.length;i++){String a=args[i];if(!a.startsWith("--"))throw new IllegalArgumentException("Expected --option");int eq=a.indexOf('=');if(eq>0)options.put(a.substring(2,eq),a.substring(eq+1));else{if(i+1>=args.length)throw new IllegalArgumentException("Missing value for "+a);options.put(a.substring(2),args[++i]);}}
        Properties p=new Properties();Path config=Path.of(options.getOrDefault("config","dist/server.properties")).toAbsolutePath().normalize();if(!Files.isRegularFile(config))throw new IllegalArgumentException("Controller config is missing: "+config);try(Reader r=Files.newBufferedReader(config)){p.load(r);}
        if(!"8002470".equals(p.getProperty("redis.game-id","8002470")))throw new IllegalArgumentException("redis.game-id must be 2470");
        String injectedPort=options.getOrDefault("port",System.getProperty("server.port",System.getenv("PORT")));if(injectedPort==null||injectedPort.isBlank())throw new IllegalArgumentException("Platform must inject --port, -Dserver.port, or PORT");int port=Integer.parseInt(injectedPort);
        if(port<50000||port>59999)throw new IllegalArgumentException("Controller port must be in 50000-59999");
        Path published=Path.of(options.getOrDefault("publish",p.getProperty("publish.dir",p.getProperty("publish.directory","../../../publish/"+DIRECTORY))));
        if(!published.isAbsolute())published=config.getParent().resolve(published).normalize();
        ControllerMain app=new ControllerMain(p,published,port);HttpServer server=HttpServer.create(new InetSocketAddress(options.getOrDefault("bind",p.getProperty("server.bind","0.0.0.0")),port),64);
        server.createContext("/",app::handle);server.setExecutor(Executors.newFixedThreadPool(12));server.start();
        System.out.println("Lucky Night Market Controller ready on "+port+"; publish="+published);
    }
    private void handle(HttpExchange exchange)throws IOException {
        String origin=exchange.getRequestHeaders().getFirst("Origin");if(origin!=null){exchange.getResponseHeaders().set("Access-Control-Allow-Origin",origin);exchange.getResponseHeaders().set("Vary","Origin");exchange.getResponseHeaders().set("Access-Control-Allow-Credentials","true");}
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type, X-Requested-With");exchange.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
        if(exchange.getRequestMethod().equals("OPTIONS")){exchange.sendResponseHeaders(204,-1);exchange.close();return;}
        String path=exchange.getRequestURI().getPath();
        try {
            if(path.equals("/health")){json(exchange,200,Json.map("ok",true,"gameId",GAME,"service","controller","contractVersion",3,"port",port,"source","redis-complete-round","samplingVersion","demo-target-floor-v3","paidRounds",paidRounds.get()));return;}
            if(path.equals("/demo/statistics")){Map<String,String> params=params(exchange);Session s=session(exchange,params);synchronized(s){json(exchange,200,Json.map("gameId",GAME,"paidRounds",s.rounds,"modeCounts",s.counts,"redisReads",redisReads.get(),"lastRedisKey",s.lastKey,"lastMemberSha256",s.memberHash,"pendingSteps",s.active==null?0:s.active.steps().size()-s.cursor,"balance",s.balance,"samplingVersion","demo-target-floor-v3","sampling","Approximately 40% loss / 40% ordinary win / 10% wheel / 10% feature per paid round; 90% of ordinary wins below 5x total bet; random target capped by current pool maximum, downward bucket lookup, random complete round"));}return;}
            if(path.startsWith("/cp/")||path.startsWith("/single_game.")||path.startsWith("/account/")||path.startsWith("/config/")||path.startsWith("/Goldgame/")||path.startsWith("/goldgame/")||path.startsWith("/activity/")){
                Map<String,String> params=params(exchange);Session s=session(exchange,params);String endpoint=path.startsWith("/cp/")?path.substring(3):path;Object data;
                if(endpoint.equals("/activity/verifyInviteCode")){json(exchange,200,envelope(1,noActivity(),"No active promotion"));return;}
                synchronized(s){data=switch(endpoint){
                    case "/config/initialData"->initialData();
                    case "/activity/getActivity"->noActivity();
                    case "/account/getUserInfo"->user(s);
                    case "/single_game.Game/initRoom"->initRoom(s);
                    case "/single_game.Game/gameResult"->spin(s,params);
                    case "/Goldgame/user_game_history"->history(s,params);
                    case "/goldgame/single_game_user_gold_history"->historyDays(s,params);
                    case "/goldgame/single_game_user_history"->historyDaily(s,params);
                    case "/Goldgame/user_game_history_detail","/Goldgame/game_history_detail"->historyDetail(s,params);
                    default->Json.map();};}
                json(exchange,200,envelope(0,data,"success"));return;
            }
            staticFile(exchange,path);
        }catch(IllegalArgumentException e){json(exchange,200,envelope(2009,List.of(),e.getMessage()));}
        catch(Exception e){System.err.println("Request failed "+path+": "+e.getClass().getSimpleName()+": "+e.getMessage());json(exchange,200,envelope(2010,List.of(),"Demo prize pool unavailable; please retry"));}
        finally{exchange.close();}
    }
    private Session session(HttpExchange x,Map<String,String> p){String key=p.get("token"),cookie=x.getRequestHeaders().getFirst("Cookie");if((key==null||!key.matches("[A-Za-z0-9_-]{1,128}"))&&cookie!=null)for(String part:cookie.split(";")){String v=part.strip();if(v.startsWith("lnm2470="))key=v.substring(8);}if(key==null||!key.matches("[A-Za-z0-9_-]{1,128}")){key=p.get("token");if(key==null||!key.matches("[A-Za-z0-9_-]{1,128}"))key=UUID.randomUUID().toString();x.getResponseHeaders().add("Set-Cookie","lnm2470="+key+"; Path=/; HttpOnly; SameSite=Lax");}final String id=key;return sessions.computeIfAbsent(id,k->new Session(k,new BigDecimal(properties.getProperty("demo.starting-balance","100000.00")))) ;}
    private Map<String,Object> initialData(){return Json.map("game_address",Json.map("ship_address_config",Json.map()),"game_info",Json.map("bet_gold",BETS,"buy_free_max_bet",-1,"default_bet_gold",0,"default_level",1,"game_way",List.of(Json.map("max_bet_gold","0.00","min_bet_gold","0.00","way_id",247010000,"win_multi","1.00")),"gid",2470,"least_gold",0,"name","Lucky Night Market","status","1"),"game_server",Json.map("gos_host","","gos_port","8976","gos_sport","","gs_host","","gs_host1","","gs_port","","gs_port1","","gs_push_host","","gs_push_port","","gs_push_sport","28966","gs_sport","","gs_sport1","","ngs_switch",0,"ps_host","","ps_port","","snake_gs_host","","snake_gs_port","","snake_gs_sport","","snake_gs_url",""),"initial_config",Json.map("bd_bet_count",2,"current_sys_time",Instant.now().getEpochSecond(),"is_debug",false,"is_stopgs",0,"user_on_hook_time",600,"version",1745909504),"language","en-us","r",1,"zone",0);}
    /** The original activity manager always dereferences free.act_list during room startup. */
    private Object noActivity(){return Json.map("free",Json.map("act_list",List.of(),"invite_act_have",0,"invite_end_time",0));}
    private Object user(Session s){return Json.map("currency_symbol","R$","day_first_login",0,"first_gold",null,"gid",2470,"gold",s.balance,"is_guide",0,"nickname","Demo","token",s.id,"total_recharge","0","uid",24700001,"user_config",Json.map("game_config",Json.map("ac",List.of(),"open_auto_spin",1,"open_free_buy",0,"open_music",1,"open_paytable",0,"open_sound",1)));}
    private Map<String,Object> initRoom(Session s)throws Exception{if(s.last!=null){Map<String,Object> data=new LinkedHashMap<>(s.last);data.put("end_gold",s.balance);data.put("eg",s.balance);data.put("bet",s.bet);data.put("level",s.level);data.put("bet_gold",s.bet.multiply(BigDecimal.valueOf(s.level)).multiply(FIVE));return data;}
        // The canonical zero-loss marker provides an uncharged initial screen; paid rounds still require Redis.
        RoundFact.Step step=RoundCodec.decode("LNM1|L|#").steps().get(0);Map<String,Object> data=base(s,Long.toString(ids.incrementAndGet()),s.bet,s.level,BigDecimal.ZERO,BigDecimal.ZERO,s.balance,s.balance);data.put("res",Json.map("muls",step.muls(),"ps",step.ps(),"tws",BigDecimal.ZERO,"wa",List.of(),"we",0,"wem",0));data.put("bet",s.bet);data.put("level",s.level);data.put("bet_gold",s.bet.multiply(FIVE));s.last=data;return data;}
    private Map<String,Object> spin(Session s,Map<String,String> request)throws Exception {
        String fingerprint=request.getOrDefault("request_id",request.getOrDefault("requestId",""));if(!fingerprint.isEmpty())fingerprint+="|"+request.getOrDefault("type","1")+"|"+request.getOrDefault("bet","0.08")+"|"+request.getOrDefault("level","1");
        if(!fingerprint.isEmpty()&&s.replies.containsKey(fingerprint))return s.replies.get(fingerprint);
        boolean paid=s.active==null;int type=Integer.parseInt(request.getOrDefault("type","1"));if(type!=(paid?1:2))throw new IllegalArgumentException(paid?"No pending feature round":"Complete the pending feature round first");if(paid){BigDecimal bet=new BigDecimal(request.getOrDefault("bet","0.08"));int level=Integer.parseInt(request.getOrDefault("level","1"));if(BETS.stream().noneMatch(b->b.compareTo(bet)==0)||level<1||level>10)throw new IllegalArgumentException("Unsupported bet or level");BigDecimal cost=bet.multiply(BigDecimal.valueOf(level)).multiply(FIVE);if(s.balance.compareTo(cost)<0)throw new IllegalArgumentException("Insufficient demo balance");
            Selected selected=selectRound(s);s.active=selected.round;s.cursor=0;s.bet=bet;s.level=level;s.totalWin=BigDecimal.ZERO;s.roundStart=s.balance;s.roundId=Long.toString(ids.incrementAndGet());s.lastKey=selected.key;s.memberHash=hash(selected.member);s.roundFrames=new ArrayList<>();s.rounds++;s.counts.merge(selected.round.mode().name(),1,Integer::sum);paidRounds.incrementAndGet();}
        RoundFact round=s.active;RoundFact.Step step=round.steps().get(s.cursor);GameRuleCore.Evaluation evaluation=ResultUtil.evaluate(step,round.feature());BigDecimal cost=paid?s.bet.multiply(BigDecimal.valueOf(s.level)).multiply(FIVE):BigDecimal.ZERO;BigDecimal win=ResultUtil.stepCash(step,round.feature(),s.bet,s.level);BigDecimal before=s.balance;BigDecimal after=before.subtract(cost).add(win);s.totalWin=s.totalWin.add(win);
        String id=Long.toString(ids.incrementAndGet());Map<String,Object> data=base(s,id,s.bet,s.level,cost,win,before,after);
        List<Object> wa=new ArrayList<>();for(GameRuleCore.Win w:evaluation.wins())wa.add(Json.map("c",w.c(),"l",w.l(),"o",w.o(),"s",w.s()));
        data.put("res",Json.map("muls",step.muls(),"ps",step.ps(),"tws",win,"wa",wa,"we",step.wheel()?1:0,"wem",step.wheelMultiplier()));data.put("o",BigDecimal.valueOf(evaluation.units()).divide(FIVE));
        if(round.feature()){data.put("f",Json.map("bet",s.bet,"l",s.level,"st",round.steps().size()-s.cursor-1,"tt",round.steps().size(),"twa",s.totalWin));data.put("t",2);data.put("small_game_type",2);}
        s.balance=after;s.cursor++;s.last=data;Map<String,Object> historyFrame=new LinkedHashMap<>(data);long historyTime=Instant.now().getEpochSecond();historyFrame.put("d",historyTime/86400*86400);historyFrame.put("time",historyTime);historyFrame.put("ext",Json.map("act_id","0","kind",1));s.roundFrames.add(historyFrame);if(s.cursor==round.steps().size()){Map<String,Object> entry=new LinkedHashMap<>(s.roundFrames.get(0));entry.put("oid",s.roundId+"-2470");entry.put("order_id",s.roundId+"-2470");entry.put("res",List.copyOf(s.roundFrames));entry.put("cg",s.balance.subtract(s.roundStart));entry.put("day",Instant.now().getEpochSecond());entry.put("time",Instant.now().getEpochSecond());entry.put("d",Instant.now().getEpochSecond()/86400*86400);s.history.addFirst(entry);while(s.history.size()>100)s.history.removeLast();s.active=null;}
        if(!fingerprint.isEmpty()){s.replies.put(fingerprint,data);while(s.replies.size()>128)s.replies.remove(s.replies.keySet().iterator().next());}return data;
    }
    private Map<String,Object> base(Session s,String id,BigDecimal bet,int level,BigDecimal cost,BigDecimal win,BigDecimal before,BigDecimal after){return Json.map("b",bet,"bg",cost,"cg",win.subtract(cost),"cl",0,"eg",after,"f",List.of(),"l",level,"o",BigDecimal.ZERO,"oid",id,"rid",id,"sg",before,"small_game_type",0,"start_gold",before,"t",1,"tw",win,"u",24700001);}
    private Selected selectRound(Session session) throws Exception {
        try (RedisClient redis = new RedisClient(properties)) {
            RoundFact.Mode mode = chooseMode(session.counts, random,
                    Double.parseDouble(properties.getProperty("demo.win-probability", "0.60")));
            if (mode == RoundFact.Mode.ORDINARY_WIN) {
                double probability = Double.parseDouble(properties.getProperty("demo.ordinary-small-win-probability", "0.90"));
                probability(probability);
                boolean small = random.nextDouble() < probability;
                Selected selected = readMode(redis, mode, small ? 1 : 25, small ? 24 : Integer.MAX_VALUE);
                if (selected == null) selected = readMode(redis, mode, small ? 25 : 1, small ? Integer.MAX_VALUE : 24);
                if (selected != null) return selected;
            } else {
                Selected selected = readMode(redis, mode, 0,
                        mode == RoundFact.Mode.ORDINARY_LOSS ? 0 : Integer.MAX_VALUE);
                if (selected != null) return selected;
            }
            throw new IOException("No valid Redis member at or below target for " + mode);
        }
    }
    private Selected readMode(RedisClient redis, RoundFact.Mode mode, int minimum, int maximum) throws IOException {
        String index = mode == RoundFact.Mode.LUCKY_WHEEL ? PRE : mode == RoundFact.Mode.LUCKY_FEATURE ? MARY : PER;
        String prefix = mode == RoundFact.Mode.LUCKY_WHEEL ? "PreLog:108002470:"
                : mode == RoundFact.Mode.LUCKY_FEATURE ? "MaryLog:008002470:" : "BetLog:008002470:";
        var buckets = RedisFloorLookup.<IOException>open(args -> floorCommand(redis, prefix, args), index,
                m -> prefix + String.format(Locale.ROOT, "%06d", m), random, minimum, maximum);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            String key = prefix + String.format(Locale.ROOT, "%06d", multiplier);
            long length = Long.parseLong(redis.command("LLEN", key).toString());
            if (length <= 0) continue;
            long start = random.nextLong(length);
            for (long visited = 0; visited < length; visited++) {
                Object raw = redis.command("LINDEX", key, Long.toString((start + visited) % length));
                redisReads.incrementAndGet();
                if (raw == null) continue;
                String text = raw.toString();
                try {
                    RoundFact decoded = RoundCodec.decode(text);
                    if (decoded.mode() == mode && ResultUtil.totalUnits(decoded) == multiplier)
                        return new Selected(key, text, decoded);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }
    private Object floorCommand(RedisClient redis, String prefix, String... args) throws IOException {
        Object result = redis.command(args);
        if (!args[0].equals("ZREVRANGEBYSCORE") || !(result instanceof List<?> entries)) return result;
        List<String> normalized = new ArrayList<>();
        for (Object entry : entries) {
            String value = entry.toString();
            normalized.add(value.startsWith(prefix) ? value.substring(prefix.length()) : value);
        }
        return normalized;
    }
    /** Demo-only weighted draws; no fixed rotation or runtime dealing, and no Loader changes. */
    public static RoundFact.Mode chooseMode(Map<String,Integer> counts,Random rng,double winProbability){
        probability(winProbability);
        int loss=counts.getOrDefault("ORDINARY_LOSS",0);
        int ordinary=counts.getOrDefault("ORDINARY_WIN",0),wheel=counts.getOrDefault("LUCKY_WHEEL",0),feature=counts.getOrDefault("LUCKY_FEATURE",0);
        // First randomly decide win/loss, as required. Deficit weights improve short-demo coverage.
        if(weightedDraw(new int[]{loss,ordinary+wheel+feature},new double[]{1-winProbability,winProbability},rng)==0)
            return RoundFact.Mode.ORDINARY_LOSS;
        RoundFact.Mode[] modes={RoundFact.Mode.ORDINARY_WIN,RoundFact.Mode.LUCKY_WHEEL,RoundFact.Mode.LUCKY_FEATURE};
        return modes[weightedDraw(new int[]{ordinary,wheel,feature},new double[]{2.0/3,1.0/6,1.0/6},rng)];
    }
    private static int weightedDraw(int[] counts,double[] shares,Random rng){
        long total=0;for(int count:counts)total+=count;
        double maximum=Double.NEGATIVE_INFINITY;double[] logs=new double[shares.length];
        for(int i=0;i<shares.length;i++){
            logs[i]=shares[i]==0?Double.NEGATIVE_INFINITY:Math.log(shares[i])+shares[i]*total-counts[i];
            maximum=Math.max(maximum,logs[i]);
        }
        double sum=0;double[] weights=new double[shares.length];
        for(int i=0;i<weights.length;i++){weights[i]=Math.exp(logs[i]-maximum);sum+=weights[i];}
        double value=rng.nextDouble()*sum;int last=0;
        for(int i=0;i<weights.length;i++){if(weights[i]>0)last=i;value-=weights[i];if(value<0)return i;}
        return last;
    }
    /** Pool keys use b*l units: below 25 units means below five times the total five-line bet. */
    static List<String> chooseOrdinaryBand(List<String> keys,Random rng,double smallProbability){
        probability(smallProbability);
        List<String> small=new ArrayList<>(),larger=new ArrayList<>();
        for(String key:keys){int units=bucket(key);if(units>0&&units<25)small.add(key);else if(units>=25)larger.add(key);}
        if(small.isEmpty())return larger;if(larger.isEmpty())return small;
        return rng.nextDouble()<smallProbability?small:larger;
    }
    private static void probability(double p){
        if(!Double.isFinite(p)||p<0||p>1)throw new IllegalArgumentException("Invalid demo probability");
    }
    
    
    private static int bucket(String key){return Integer.parseInt(key.substring(key.lastIndexOf(':')+1));}
    private Object history(Session s,Map<String,String> p){int page=Math.max(1,Integer.parseInt(p.getOrDefault("page","1"))),size=Math.min(50,Math.max(1,Integer.parseInt(p.getOrDefault("limit",p.getOrDefault("page_size","20")))));long start=Long.parseLong(p.getOrDefault("start","0")),end=Long.parseLong(p.getOrDefault("end",Long.toString(Long.MAX_VALUE)));List<Map<String,Object>> all=s.history.stream().filter(r->{long t=((Number)r.get("time")).longValue();return t>=start&&t<=end;}).toList();int from=(int)Math.min(all.size(),((long)page-1)*size);BigDecimal bet=BigDecimal.ZERO,change=BigDecimal.ZERO;for(var r:all){bet=bet.add((BigDecimal)r.get("bg"));change=change.add((BigDecimal)r.get("cg"));}return Json.map("list",all.subList(from,Math.min(all.size(),from+size)),"totals",Json.map("bet_golds",bet,"change_golds",change,"total",all.size()));}
    private Object historyDays(Session s,Map<String,String> p){
        Map<Long,Map<String,Object>> days=new TreeMap<>(Comparator.reverseOrder());BigDecimal totalBet=BigDecimal.ZERO,totalChange=BigDecimal.ZERO;
        for(Map<String,Object> row:s.history){long day=((Number)row.get("d")).longValue();BigDecimal bet=(BigDecimal)row.get("bg"),change=(BigDecimal)row.get("cg");Map<String,Object> daily=days.computeIfAbsent(day,d->Json.map("d",d,"bet_gold",BigDecimal.ZERO,"cg",BigDecimal.ZERO));daily.put("bet_gold",((BigDecimal)daily.get("bet_gold")).add(bet));daily.put("cg",((BigDecimal)daily.get("cg")).add(change));totalBet=totalBet.add(bet);totalChange=totalChange.add(change);}
        List<Map<String,Object>> all=new ArrayList<>(days.values());int size=Math.min(50,Math.max(1,Integer.parseInt(p.getOrDefault("page_size","30")))),page=Math.max(1,Integer.parseInt(p.getOrDefault("page","1")));int from=(int)Math.min(all.size(),((long)page-1)*size);
        return Json.map("list",all.subList(from,Math.min(all.size(),from+size)),"statistics",Json.map("total_bet_gold",totalBet,"total_change_gold",totalChange));
    }
    private Object historyDaily(Session s,Map<String,String> p){
        long day=Long.parseLong(p.getOrDefault("day",Long.toString(Instant.now().getEpochSecond()/86400*86400)));Map<String,String> range=new LinkedHashMap<>(p);range.put("start",Long.toString(day));range.put("end",Long.toString(day+86399));Map<String,Object> result=Json.object(history(s,range));Map<String,Object> totals=Json.object(result.get("totals"));
        return Json.map("list",result.get("list"),"statistics",Json.map("total_bet_gold",totals.get("bet_golds"),"total_change_gold",totals.get("change_golds")));
    }
    private Object historyDetail(Session s,Map<String,String> p){String id=p.getOrDefault("order_id",p.getOrDefault("oid",""));for(var r:s.history)if(id.equals(r.get("oid")))return r;return Json.map("list",List.of());}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Map<String,String> params(HttpExchange exchange)throws IOException {Map<String,String> m=new LinkedHashMap<>();decodeForm(exchange.getRequestURI().getRawQuery(),m);if(exchange.getRequestMethod().equals("POST")){byte[] bytes=exchange.getRequestBody().readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Request too large");String text=new String(bytes,StandardCharsets.UTF_8);if(exchange.getRequestHeaders().getFirst("Content-Type")!=null&&exchange.getRequestHeaders().getFirst("Content-Type").contains("application/json")){for(var e:Json.object(Json.parse(text)).entrySet())if(e.getValue()!=null)m.put(e.getKey(),e.getValue().toString());}else decodeForm(text,m);}return m;}
    private static void decodeForm(String raw,Map<String,String> m){if(raw==null||raw.isBlank())return;for(String field:raw.split("&")){int i=field.indexOf('=');m.put(URLDecoder.decode(i<0?field:field.substring(0,i),StandardCharsets.UTF_8),URLDecoder.decode(i<0?"":field.substring(i+1),StandardCharsets.UTF_8));}}
    private void staticFile(HttpExchange x,String url)throws IOException{if(!x.getRequestMethod().equals("GET")&&!x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(405,-1);return;}String relative=url.equals("/")?"index.html":url.substring(1);if(relative.startsWith("play/"+DIRECTORY+"/"))relative=relative.substring(("play/"+DIRECTORY+"/").length());Path file=publish.resolve(relative).normalize();if(!file.startsWith(publish)||!Files.isRegularFile(file)){x.sendResponseHeaders(404,-1);return;}String type=Files.probeContentType(file);if(type==null){String name=file.getFileName().toString();type=name.endsWith(".js")?"text/javascript":name.endsWith(".json")?"application/json":name.endsWith(".wasm")?"application/wasm":"application/octet-stream";}x.getResponseHeaders().set("Content-Type",type);if(x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(200,-1);return;}x.sendResponseHeaders(200,Files.size(file));try(OutputStream out=x.getResponseBody()){Files.copy(file,out);}}
    private static Map<String,Object> envelope(int code,Object data,String msg){return Json.map("code",code,"data",data,"msg",msg,"time",Long.toString(Instant.now().getEpochSecond()));}
    private static void json(HttpExchange x,int status,Object value)throws IOException{byte[] bytes=Json.stringify(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}}
    private record Selected(String key,String member,RoundFact round){}
    private static final class Session {
        final String id;BigDecimal balance,bet=new BigDecimal("0.08"),totalWin=BigDecimal.ZERO,roundStart;int level=1,cursor,rounds;String roundId,lastKey="",memberHash="";RoundFact active;Map<String,Object> last;List<Map<String,Object>> roundFrames;final Map<String,Integer>counts=new LinkedHashMap<>();final LinkedHashMap<String,Map<String,Object>>replies=new LinkedHashMap<>();final ArrayDeque<Map<String,Object>>history=new ArrayDeque<>();Session(String id,BigDecimal balance){this.id=id;this.balance=balance;}
    }
}
