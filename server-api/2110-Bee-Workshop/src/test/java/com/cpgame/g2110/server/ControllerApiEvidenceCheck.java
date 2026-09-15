package com.cpgame.g2110.server;

import com.cpgame.g2110.core.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import redis.clients.jedis.Jedis;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Runs production request handlers in memory against this game's Redis pools; opens no listener. */
public final class ControllerApiEvidenceCheck {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final ResultUtil ORACLE=new ResultUtil(new GameRuleCore());
 private final ControllerMain controller=new ControllerMain();
 private final Method handler;
 private final RedisRoundRepository repository;
 private final Jedis jedis;
 private final List<Map<String,Object>> rounds=new ArrayList<>();
 private int requests;
 private ControllerApiEvidenceCheck(Properties p,Path publish)throws Exception{
  repository=new RedisRoundRepository(p);
  Field field=ControllerMain.class.getDeclaredField("redis");field.setAccessible(true);field.set(controller,repository);
  field=ControllerMain.class.getDeclaredField("publish");field.setAccessible(true);field.set(controller,publish);
  field=RedisRoundRepository.class.getDeclaredField("jedis");field.setAccessible(true);jedis=(Jedis)field.get(repository);
  handler=ControllerMain.class.getDeclaredMethod("handle",HttpExchange.class);handler.setAccessible(true);
 }
 public static void main(String[] args)throws Exception{
  if(args.length<2||args.length>3)throw new IllegalArgumentException("expected config and publish");
  Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){p.load(r);}
  var test=new ControllerApiEvidenceCheck(p,Path.of(args[1]));
  try{if(args.length==3&&args[2].equals("--empty-only"))test.emptyChecks();else if(args.length==3&&args[2].equals("--history"))test.historyChecks();else if(args.length==3&&args[2].equals("--ten-free"))test.tenFreeChecks();else test.run();}finally{test.repository.close();}
 }
 private void run()throws Exception{
  String token="playthrough-api-"+System.nanoTime();
  JsonNode health=call("GET","/health","",200);
  need(health.path("rulesHash").asText().equals(GameRuleCore.RULES_HASH),"health rule hash");
  JsonNode config=api("/config/initialData",token,"");
  need(config.path("game_info").path("gid").asInt()==2110,"config gid");
  need(config.path("game_info").path("buy_free_max_bet").asInt()==-1,"captured purchase disable");
  api("/account/getUserInfo",token,"");api("/activity/getActivity",token,"");api("/config/setGameConfig",token,"");
  api("/single_game.Game/initRoom",token,"");
  call("POST","/cp/unknown","token="+token,404);
  call("POST","/cp/single_game.Game/gameResult","token=normal&playthrough_kind=ORDINARY_LOSS",403);
  for(String invalid:List.of("bet_gold=NaN&level=10","bet_gold=Infinity&level=10","bet_gold=-1&level=10","bet_gold=0.02&level=0","bet_gold=x&level=10","bet_gold=0.000001&level=10"))
   call("POST","/cp/single_game.Game/gameResult","token="+token+"&"+invalid,400);
  call("POST","/cp/single_game.Game/gameResult","token="+token+"&bet_gold=100000&level=10",409);
  double wallet=100000;int completed=0;double allChanges=0;
  for(var kind:GameRuleCore.RoundKind.values())for(int iteration=0;iteration<2;iteration++){
   long before=poolMembers(kind);double roundStart=wallet;int delivered=0,total=0,previousRemaining=-1;String oid=null;double freeWin=0;
   List<Integer> sequence=new ArrayList<>();Set<Integer> previousSticky=new HashSet<>();
   do{
    String extra=delivered==0?"bet_gold=0.02&level=10&playthrough_kind="+kind.name():"bet_gold=2&level=20";
    JsonNode d=api("/single_game.Game/gameResult",token,extra);
    double payout=ORACLE.moneyFromUnits(ORACLE.independentPayoutUnits(board(d)),.02,10);
    eq(d.path("total_win").asDouble(),payout,"independent payout");
    eq(d.path("props").path("tw").asDouble(),payout,"props payout");
    eq(d.path("start_gold").asDouble(),wallet,"wallet start");
    wallet=ORACLE.money(wallet+payout-(delivered==0?4:0));
    eq(d.path("end_gold").asDouble(),wallet,"one-time wager and step award");
    eq(d.path("change_gold").asDouble(),payout-(delivered==0?4:0),"change");
    eq(d.path("bet").asDouble(),.02,"pinned bet");need(d.path("level").asInt()==10,"pinned level");
    eq(d.path("bet_gold").asDouble(),4,"source-compatible bet_gold");
    if(delivered==0){oid=d.path("oid").asText();total=d.path("frees").path("tt").asInt();}
    else{
     need(d.path("forder_id").asText().equals(oid),"same free order");
     freeWin=ORACLE.money(freeWin+payout);
     int[] board=board(d);Set<Integer> sticky=new HashSet<>();
     d.path("spe_pos").forEach(x->sticky.add(x.asInt()));
     need(sticky.containsAll(previousSticky)&&sticky.contains(7),"sticky retention");
     for(int cell=0;cell<15;cell++)need(sticky.contains(cell)==(board[cell]==board[7]),"all matching positions sticky");
     previousSticky=sticky;
    }
    need(d.path("oid").asText().equals(oid),"same complete round id");
    int remaining=d.path("frees").path("st").asInt();sequence.add(remaining);
    if(delivered>0)need(remaining==previousRemaining-1,"remaining monotonic");
    eq(d.path("frees").path("twa").asDouble(),freeWin,"cumulative free win");
    need(d.path("type").asInt()==(delivered==0?1:2),"step type");
    previousRemaining=remaining;delivered++;
    need(poolMembers(kind)==before,"LINDEX does not consume the pool");
    JsonNode state=call("GET","/api/session?token="+token,"",200);
    if(remaining>0){
     need(state.path("activeRound").asBoolean(),"active round");
     int next=state.path("nextStep").asInt();
     JsonNode resumed=api("/single_game.Game/initRoom",token,"");
     need(resumed.equals(d),"initRoom returns delivered state without advancing");
     need(call("GET","/api/session?token="+token,"",200).path("nextStep").asInt()==next,"resume does not consume step");
    }else need(!state.path("activeRound").asBoolean()&&state.path("nextStep").asInt()==0,"terminal reset");
   }while(previousRemaining>0);
   need(delivered==(kind==GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS?total+1:1),"complete delivery count");
   eq(call("GET","/api/balance?token="+token,"",200).path("balance").asDouble(),wallet,"balance endpoint");
   completed++;double change=ORACLE.money(wallet-roundStart);allChanges=ORACLE.money(allChanges+change);
   JsonNode history=api("/goldgame/single_game_user_history",token,"");
   need(history.path("list").size()==completed,"one history row per complete round");
   JsonNode newest=history.path("list").get(0);
   need(newest.path("results").size()==delivered,"history contains all deliveries");
   need("0".equals(newest.path("extend").path("act_id").asText()),"history parent extend.act_id string zero");
   need("0".equals(newest.path("results").get(0).path("extend").path("act_id").asText()),"history step extend.act_id string zero");
   need(newest.path("results").get(0).path("result").path("prop").size()==15,"history detail board");
   need(newest.path("results").get(0).path("spe_pos").isArray(),"history step spe_pos");
   eq(newest.path("change_gold").asDouble(),change,"history round net");
   eq(history.path("statistics").path("total_bet_gold").asDouble(),4*completed,"history wager once per round");
   eq(history.path("statistics").path("total_change_gold").asDouble(),allChanges,"history net total");
   JsonNode day=api("/goldgame/single_game_user_gold_history",token,"");
   eq(day.path("statistics").path("total_bet_gold").asDouble(),4*completed,"day wager");
   eq(day.path("statistics").path("total_change_gold").asDouble(),allChanges,"day net");
   rounds.add(Map.of("kind",kind.name(),"iteration",iteration+1,"deliveries",delivered,"remaining",sequence,"redisMembersConsumed",0,"netChange",change));
  }
  JsonNode separate=call("GET","/api/balance?token=isolated-"+System.nanoTime(),"",200);
  eq(separate.path("balance").asDouble(),100000,"session isolation");
  System.out.println(JSON.writeValueAsString(Map.of("status","PASS","observedAt",Instant.now().toString(),"rulesHash",GameRuleCore.RULES_HASH,"requestCount",requests,"completeRounds",completed,"rounds",rounds,"listenersStarted",0,"fixtureRuntimeReads",false,"verificationScope","Production handlers in memory plus real game Redis; not HTTP transport or browser animation")));
 }
 private void tenFreeChecks()throws Exception{
  Field field=RedisRoundRepository.class.getDeclaredField("jedis");field.setAccessible(true);
  var codec=new MinimalRoundFactCodec();var kind=GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS;
  String index=RedisKeys.maryIndex();List<Map<String,Object>> checked=new ArrayList<>();
  for(int iteration=0;iteration<2;iteration++){
   String selected=null;
   for(String choice:jedis.zrange(index,0,-1)){
    int units=Integer.parseInt(choice);
    String member=jedis.lindex(RedisKeys.maryList(units),-1);
    if(member!=null&&codec.decode(member).kind()==kind&&codec.decode(member).steps().size()==11){selected=choice;break;}
   }
   need(selected!=null,"no remaining existing ten-free member at bucket head");
   long before=poolMembers(kind);String token="playthrough-ten-free-"+System.nanoTime();double balance=100000,freeWin=0;
   List<Integer> remaining=new ArrayList<>();String order=null;
   field.set(repository,new SelectedJedis(jedis,index,selected));
   try{
    for(int step=0;step<11;step++){
     JsonNode d=api("/single_game.Game/gameResult",token,step==0?"bet_gold=0.02&level=10&playthrough_kind=FREE_STICKY_SYMBOLS":"bet_gold=2&level=20");
     long units=ORACLE.independentPayoutUnits(board(d));double win=ORACLE.moneyFromUnits(units,.02,10);
     balance=ORACLE.money(balance+win-(step==0?4:0));eq(d.path("end_gold").asDouble(),balance,"ten-free wallet");
     if(step==0)order=d.path("oid").asText();else freeWin=ORACLE.money(freeWin+win);
     need(d.path("oid").asText().equals(order),"same ten-free order");eq(d.path("bet").asDouble(),.02,"ten-free pinned wager");
     need(d.path("frees").path("tt").asInt()==10&&d.path("frees").path("st").asInt()==10-step,"ten-free remaining");
     eq(d.path("frees").path("twa").asDouble(),freeWin,"ten-free cumulative");remaining.add(10-step);
     if(step<10)need(api("/single_game.Game/initRoom",token,"").equals(d),"ten-free resume");
    }
   }finally{field.set(repository,jedis);}
   need(poolMembers(kind)==before,"LINDEX does not consume the ten-free pool");
   need(!call("GET","/api/session?token="+token,"",200).path("activeRound").asBoolean(),"ten-free terminal idle");
   JsonNode history=api("/goldgame/single_game_user_history",token,"");
   need(history.path("list").size()==1&&history.path("list").get(0).path("results").size()==11,"ten-free grouped History");
   eq(history.path("statistics").path("total_bet_gold").asDouble(),4,"ten-free single wager History");
   checked.add(Map.of("iteration",iteration+1,"deliveries",11,"remaining",remaining,"realRedisMembersConsumed",0));
  }
  System.out.println(JSON.writeValueAsString(Map.of("status","PASS","observedAt",Instant.now().toString(),"rulesHash",GameRuleCore.RULES_HASH,"requestCount",requests,"completeRounds",2,"rounds",checked,"listenersStarted",0,"verificationScope","Test-selected existing ten-free bucket heads; real Redis LINDEX and production API delivery, not production selection frequency or browser animation")));
 }
 private static final class SelectedJedis extends Jedis{
  final Jedis actual;final String index,choice;
  SelectedJedis(Jedis actual,String index,String choice){this.actual=actual;this.index=index;this.choice=choice;}
  @Override public List<String> zrange(String key,long start,long stop){if(!key.equals(index))throw new IllegalStateException("unexpected test selection index");return List.of(choice);}
  @Override public String lindex(String key,long index){return actual.lindex(key,index);}
  @Override public long llen(String key){return actual.llen(key);}
  @Override public void close(){}
 }
 private void historyChecks()throws Exception{
  String token="playthrough-history-"+System.nanoTime();long before=poolMembers(GameRuleCore.RoundKind.ORDINARY_LOSS);
  for(int i=0;i<31;i++)api("/single_game.Game/gameResult",token,"bet_gold=0.02&level=10&playthrough_kind=ORDINARY_LOSS");
  need(poolMembers(GameRuleCore.RoundKind.ORDINARY_LOSS)==before,"LINDEX does not consume loss members");
  long today=Math.floorDiv(Instant.now().getEpochSecond(),86400)*86400;
  Set<String> orders=new HashSet<>();List<Integer> pageSizes=new ArrayList<>();
  for(int page=1;page<=3;page++){
   JsonNode d=api("/goldgame/single_game_user_history",token,"day="+today+"&page_size=30&page="+page);
   need(d.path("list").size()==(page==1?30:page==2?1:0),"correct page length");
   pageSizes.add(d.path("list").size());
   for(JsonNode row:d.path("list")){
    need(orders.add(row.path("order_id").asText()),"no duplicate history order");
    need("0".equals(row.path("extend").path("act_id").asText()),"paged history extend.act_id");
    need(row.path("results").get(0).path("result").path("prop").size()==15,"paged history board");
   }
   eq(d.path("statistics").path("total_bet_gold").asDouble(),124,"full day total on every page");
   eq(d.path("statistics").path("total_change_gold").asDouble(),-124,"full day net on every page");
  }
  need(orders.size()==31,"all rounds retained beyond page one");
  JsonNode custom=api("/goldgame/single_game_user_history",token,"day="+today+"&page_size=7&page=5");
  need(custom.path("list").size()==3,"custom page_size");
  JsonNode previous=api("/goldgame/single_game_user_history",token,"day="+(today-86400)+"&page_size=30&page=1");
  need(previous.path("list").isEmpty(),"day selection");
  eq(previous.path("statistics").path("total_bet_gold").asDouble(),0,"empty day total");
  JsonNode daily=api("/goldgame/single_game_user_gold_history",token,"");
  need(daily.path("list").size()==7,"seven daily rows");
  for(int i=0;i<7;i++){
   JsonNode day=daily.path("list").get(i);need(day.path("day").asLong()==today-i*86400L,"daily date sequence");
   eq(day.path("bet_gold").asDouble(),i==0?124:0,"daily wager");eq(day.path("change_gold").asDouble(),i==0?-124:0,"daily net");
  }
  eq(daily.path("statistics").path("total_bet_gold").asDouble(),124,"seven-day statistics");
  eq(call("GET","/api/balance?token="+token,"",200).path("balance").asDouble(),99876,"balance after 31 losses");
  for(String invalid:List.of("page=0","page_size=0","page=x","day=x"))call("POST","/cp/goldgame/single_game_user_history","token="+token+"&"+invalid,400);
  System.out.println(JSON.writeValueAsString(Map.of("status","PASS","observedAt",Instant.now().toString(),"rulesHash",GameRuleCore.RULES_HASH,"requestCount",requests,"realRedisCompleteRounds",31,"pageLengths",pageSizes,"fullDayBet",124,"dailyRows",7,"listenersStarted",0,"verificationScope","Production handlers and actual Redis loss members; pagination/statistics regression based on captured History fields")));
 }
 private void emptyChecks()throws Exception{
  Field field=RedisRoundRepository.class.getDeclaredField("jedis");field.setAccessible(true);
  List<Map<String,Object>> checks=new ArrayList<>();
  try{
   for(boolean stale:List.of(false,true)){
    EmptyJedis fake=new EmptyJedis(stale);field.set(repository,fake);
    JsonNode result=call("POST","/cp/single_game.Game/gameResult","token=empty-check-"+stale+"&bet_gold=0.02&level=10",503);
    need(result.path("msg").asText().equals("PREGENERATED_CACHE_EMPTY"),"explicit empty response");
    need(fake.indexCalls>=1,"choose platform index before list");
    need(List.of(RedisKeys.normalIndex(),RedisKeys.maryIndex()).contains(fake.index),"choose platform index before list");
    checks.add(Map.of("case",stale?"selected bucket exhausted":"selected outcome index empty","status",503,"indexReads",fake.indexCalls,"popAttempts",fake.popCalls,"redisMutation","none; mocked exhausted cache"));
   }
  }finally{field.set(repository,jedis);}
  System.out.println(JSON.writeValueAsString(Map.of("status","PASS","observedAt",Instant.now().toString(),"rulesHash",GameRuleCore.RULES_HASH,"checks",checks,"requestCount",requests,"listenersStarted",0,"verificationScope","Real Controller and repository empty-cache branches; Jedis exhaustion test double")));
 }
 private static final class EmptyJedis extends Jedis{
  final boolean stale;int indexCalls,popCalls;String index;
  EmptyJedis(boolean stale){this.stale=stale;}
  @Override public List<String> zrange(String key,long start,long stop){indexCalls++;index=key;return stale?List.of("0"):List.of();}
  @Override public long llen(String key){return stale?1:0;}
  @Override public String lindex(String key,long index){popCalls++;return null;}
  @Override public void close(){}
 }
 private long poolMembers(GameRuleCore.RoundKind kind){
  boolean special=RedisKeys.special(kind);long count=0;
  for(String c:jedis.zrange(RedisKeys.index(special),0,-1)){
   int units=Integer.parseInt(c);
   for(String member:jedis.lrange(RedisKeys.list(special,units),0,-1)){
    if(codecOf().decode(member).kind()==kind)count++;
   }
  }return count;
 }
 private static MinimalRoundFactCodec codecOf(){return new MinimalRoundFactCodec();}
 private JsonNode api(String path,String token,String extra)throws Exception{return call("POST","/cp"+path,"token="+token+(extra.isEmpty()?"":"&"+extra),200).path("data");}
 private JsonNode call(String method,String path,String body,int status)throws Exception{
  Exchange x=new Exchange(method,path,body);handler.invoke(controller,x);requests++;
  JsonNode result=JSON.readTree(x.output.toByteArray());need(x.status==status,"status "+path+": "+x.status+" expected "+status+" "+result);
  if(path.startsWith("/cp/")&&status==200)need(result.path("code").asInt(-1)==0,"API success envelope");
  return result;
 }
 private static int[] board(JsonNode d){int[] out=new int[15];JsonNode a=d.path("props").path("prop");need(a.size()==15,"board length");for(int i=0;i<15;i++)out[i]=a.get(i).asInt();return out;}
 private static void eq(double actual,double expected,String why){need(Math.abs(actual-expected)<.000001,why+": "+actual+" != "+expected);}
 private static void need(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
 static final class Exchange extends HttpExchange{
  final Headers request=new Headers(),response=new Headers();final URI uri;final String method;InputStream input;OutputStream target;final ByteArrayOutputStream output=new ByteArrayOutputStream();int status;final Map<String,Object> attrs=new HashMap<>();
  Exchange(String method,String path,String body){this.method=method;uri=URI.create(path);input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));target=output;request.set("Host","in-process");}
  public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return input;}public OutputStream getResponseBody(){return target;}public void sendResponseHeaders(int status,long length){this.status=status;}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}public int getResponseCode(){return status;}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String name){return attrs.get(name);}public void setAttribute(String name,Object value){attrs.put(name,value);}public void setStreams(InputStream input,OutputStream output){this.input=input;target=output;}public HttpPrincipal getPrincipal(){return null;}
 }
}
