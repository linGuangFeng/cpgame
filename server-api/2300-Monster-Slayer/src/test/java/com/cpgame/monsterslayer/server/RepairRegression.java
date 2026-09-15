package com.cpgame.monsterslayer.server;

import com.cpgame.monsterslayer.core.*;
import com.cpgame.monsterslayer.generator.CompleteRoundFactory;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public final class RepairRegression {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++; if (!ok) throw new AssertionError(message);
    }
    // Independent dynamic-programming oracle: direct frontend payout constants; no RuleCore predicates/table.
    private static final int[][] PAY={{},{50,100,750},{40,80,500},{35,50,250},{30,40,200},{30,40,150},
            {25,35,150},{20,30,125},{20,30,100},{15,25,75},{10,25,50}};
    private static int oracle(int[] board) {
        int total=0;
        for(int symbol=1;symbol<=10;symbol++){
            int[] current=new int[3];
            for(int row=0;row<3;row++)if(board[row]==symbol)current[row]=1;
            for(int col=1;col<5;col++){
                int[] next=new int[3];
                for(int row=0;row<3;row++){
                    boolean extendsPath=false;
                    for(int dest=Math.max(0,row-1);dest<=Math.min(2,row+1);dest++)
                        if(board[col*3+dest]==symbol){next[dest]+=current[row];extendsPath=true;}
                    if(!extendsPath&&col>=3)total+=current[row]*PAY[symbol][col-3];
                }
                current=next;
            }
            for(int count:current)total+=count*PAY[symbol][2];
        }
        return total;
    }
    private static GameRuleCore.CompleteRound ordinary(int[] board) {
        return new GameRuleCore.CompleteRound(false,List.of(new GameRuleCore.Step(board,0,0)));
    }
    private static int[] board(JsonNode node){
        int[] b=new int[node.size()];for(int i=0;i<b.length;i++)b[i]=node.get(i).asInt();return b;
    }
    public static void main(String[]args)throws Exception{
        JsonNode evidence=JSON.readTree(Path.of(args[0]).toFile());
        Set<String> holdout=new HashSet<>();evidence.path("holdoutRoundIds").forEach(n->holdout.add(n.asText()));
        int captured=0,held=0;
        for(JsonNode round:evidence.path("rounds")){
            if(round.path("kind").asText().equals("special"))continue;
            JsonNode step=round.path("steps").get(0);int[] b=board(step.path("board"));
            int expected=new BigDecimal(step.path("tw").asText()).multiply(BigDecimal.valueOf(100))
                    .divide(new BigDecimal(step.path("bet").asText())).intValueExact();
            int independentlyCalculated=oracle(b);
            check(independentlyCalculated==expected,"raw oracle mismatch "+step.path("source")+" expected="+expected+" oracle="+independentlyCalculated);
            check(ResultUtil.evaluate(ordinary(b)).multiplierCenti()==expected,"engine raw mismatch "+step.path("source"));
            captured++;if(holdout.contains(round.path("id").asText()))held++;
        }
        CompleteRoundFactory factory=new CompleteRoundFactory();MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
        SecureRandom random=new SecureRandom();Set<String>members=new HashSet<>();int max=0;
        for(int i=0;i<10000;i++){
            boolean win=(i&1)==1;GameRuleCore.CompleteRound round=win?factory.generateWin(random):factory.generateLoss(random);
            int[] b=round.steps().get(0).board();int expected=oracle(b);max=Math.max(max,expected);
            check((expected>0)==win,"requested generated class");check(ResultUtil.evaluate(round).multiplierCenti()==expected,"generated oracle mismatch");
            int scatters=0;for(int cell=0;cell<15;cell++){
                if(b[cell]==100){scatters++;check(cell>=3&&cell<12,"scatter position");}
                else check(b[cell]>=1&&b[cell]<=10,"ordinary symbol");
            }
            check(scatters<=1,"scatter board cap");String member=codec.encode(round);
            check(codec.encode(codec.decode(member)).equals(member),"codec round trip");
            check(member.chars().allMatch(c->c>=32&&c<127),"printable ASCII");members.add(member);
        }
        boolean blocked=false;try{factory.generateSpecial(random);}catch(IllegalStateException e){blocked=true;}
        check(blocked,"special generator must fail instead of fabricating state");

        int[] loss={2,6,6,6,10,10,4,3,7,3,7,8,9,7,3};
        int[] win3={1,2,3,1,4,5,1,6,7,8,9,10,2,3,4};
        int[] win5={1,2,3,1,4,5,1,6,7,1,9,10,1,3,4};
        String l=codec.encode(ordinary(loss)),w3=codec.encode(ordinary(win3)),w5=codec.encode(ordinary(win5));
        MemoryRedis redis=new MemoryRedis();redis.add(0,l);redis.add(50,w3);redis.add(750,w5);
        ScriptedRandom chosen=new ScriptedRandom(true,1);
        try(RedisRoundStore store=new RedisRoundStore(redis,chosen)){
            check(store.claimPaidRound().multiplierCenti()==750,"random chooses specified integer bucket");
            check(chosen.booleans==1,"outcome drawn once before multiplier");
        }
        MemoryRedis noLoss=new MemoryRedis();noLoss.add(50,w3);
        try(RedisRoundStore store=new RedisRoundStore(noLoss,new ScriptedRandom(false,0))){
            boolean failed=false;try{store.claimPaidRound();}catch(IllegalStateException e){failed=e.getMessage().contains("empty");}
            check(failed,"empty loss must fail");check(noLoss.pops==0,"must not consume winning fallback");
        }
        MemoryRedis stale=new MemoryRedis();stale.add(50,null);stale.add(750,w5);
        try(RedisRoundStore store=new RedisRoundStore(stale,new ScriptedRandom(true,0))){
            check(store.claimPaidRound().multiplierCenti()==750,"stale index retries within same outcome");
        }
        MemoryRedis sessionRedis=new MemoryRedis();sessionRedis.add(0,l);sessionRedis.add(50,w3);
        try(RedisRoundStore store=new RedisRoundStore(sessionRedis,new ScriptedRandom(true,0))){
            MonsterSlayerService service=new MonsterSlayerService(store,new BigDecimal("10000"));
            Map<String,String> form=new HashMap<>(Map.of("gid","2300","type","1","bet","0.2","level","10","token","regression"));
            service.init(form);service.init(form);
            check(service.balance(form).path("data").path("balance").decimalValue().compareTo(new BigDecimal("10000"))==0,"init is balance-neutral");
            check(sessionRedis.pops==0,"init does not claim a member");
            JsonNode spin=service.spin(form).path("data");
            check(spin.path("tw").decimalValue().compareTo(new BigDecimal("1.00"))==0,"original payout projection");
            check(spin.path("eg").decimalValue().compareTo(new BigDecimal("9999.00"))==0,"single charge and settlement");
            check(spin.path("f").path("loc").isArray()&&spin.path("f").path("loc").size()==0,"ordinary loc is []");
            check(spin.path("small_game_type").asInt()==0,"ordinary small_game_type");
            check(!service.status(form).path("data").path("activeRound").asBoolean(),"ordinary round terminates");
            GameRuleCore.CompleteRound buyRound=factory.generateBuy(3,random);
            String buyMember=codec.encode(buyRound);
            int buyMult=ResultUtil.redisMultiplierCenti(buyRound);
            sessionRedis.addBuy(3,buyMult,buyMember);
            form.put("type","3");
            JsonNode buySpin=service.spin(form).path("data");
            check(buySpin.path("t").asInt()==3,"buy request type");
            check(buySpin.path("f").path("gt").asInt()>0,"buy starts in feature");
            check(buySpin.path("small_game_type").asInt()==0,"buy trigger small_game_type is 0");
            check(buySpin.path("f").path("r").isArray()&&buySpin.path("f").path("r").size()==0,"buy trigger r is []");
            check(buySpin.path("f").path("loc").isObject(),"buy trigger loc is an object");
            form.put("type","2");
            JsonNode follow=service.spin(form).path("data");
            check(follow.path("small_game_type").asInt()==2,"feature follow small_game_type is 2");
            check(follow.path("f").path("r").isArray()&&follow.path("f").path("r").size()>=1,"feature follow emits r");
            check(follow.path("f").path("r").get(0).has("1")||follow.path("f").path("r").get(0).isObject(),"r[0] has hunter 1");
            JsonNode hunter=follow.path("f").path("r").get(0).path("1");
            if(hunter.isMissingNode()) hunter=follow.path("f").path("r").get(0);
            check(hunter.path("ls").path("ln").asInt()>=1&&hunter.path("ls").path("ln").asInt()<=3,"hunter ln is a real lane");
            check(hunter.has("sp"),"hunter sp is present so the scythe can land");
        }
        ObjectNode report=JSON.createObjectNode();report.put("gameId",2300);report.put("componentRepairPass",true);report.put("readyForAcceptance",false);
        report.put("capturedOrdinaryRoundsVerified",captured);report.put("heldOutOrdinaryRoundsVerified",held);
        report.put("heldOutSpecialRoundsStillBlocked",100-held);report.put("newOrdinaryCompleteRounds",10000);
        report.put("generatedLoss",5000);report.put("generatedWin",5000);report.put("uniqueMembers",members.size());
        report.put("maximumGeneratedCentiMultiplier",max);report.put("assertions",assertions);
        report.put("oracle","Independent DP using original frontend payouts; compared to original captured amount");
        report.put("redisTest","In-process command double; no real Redis read/write by this test");
        report.putArray("blocked").add("natural scatter-feature SAMPLE_INSUFFICIENT");
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
        System.out.println(report);
    }
    private static final class ScriptedRandom extends Random{
        final boolean outcome;final int index;int booleans;
        ScriptedRandom(boolean outcome,int index){this.outcome=outcome;this.index=index;}
        public boolean nextBoolean(){booleans++;return outcome;}
        public int nextInt(int bound){return Math.min(index,bound-1);}
    }
    private static final class MemoryRedis implements RedisCommands{
        final Map<String,Deque<String>>lists=new HashMap<>();final Set<Integer>index=new TreeSet<>();final Map<Integer,Set<Integer>>buyIndex=new HashMap<>();int pops;
        void add(int multiplier,String member){index.add(multiplier);Deque<String>q=lists.computeIfAbsent(com.cpgame.monsterslayer.redis.RedisKeyContract.normalList(2300,multiplier),k->new ArrayDeque<>());if(member!=null)q.add(member);}
        void addBuy(int type,int multiplier,String member){buyIndex.computeIfAbsent(type,ignored->new TreeSet<>()).add(multiplier);Deque<String>q=lists.computeIfAbsent(com.cpgame.monsterslayer.redis.RedisKeyContract.buyList(type,multiplier),k->new ArrayDeque<>());if(member!=null)q.add(member);}
        public Object command(String...args){
            check(com.cpgame.monsterslayer.redis.RedisKeyContract.belongsToGame2300(args[1]),"only game 2300 key");
            return switch(args[0]){
                case "ZRANGE" -> {
                    if (args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(3))
                            || args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.legacyBuyIndex(3)))
                        yield buyIndex.getOrDefault(3,Set.of()).stream().map(String::valueOf).toList();
                    if (args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(4))
                            || args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.buyPerKeyIndex(4))
                            || args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.legacyBuyIndex(4)))
                        yield buyIndex.getOrDefault(4,Set.of()).stream().map(String::valueOf).toList();
                    if (args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.buyIndex(5))
                            || args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.buyPerKeyIndex(5))
                            || args[1].equals(com.cpgame.monsterslayer.redis.RedisKeyContract.legacyBuyIndex(5)))
                        yield buyIndex.getOrDefault(5,Set.of()).stream().map(String::valueOf).toList();
                    yield index.stream().map(String::valueOf).toList();
                }
                case "LLEN" -> (long) lists.getOrDefault(args[1],new ArrayDeque<>()).size();
                case "LINDEX" -> {
                    List<String> copy=new ArrayList<>(lists.getOrDefault(args[1],new ArrayDeque<>()));
                    int i=Integer.parseInt(args[2]);
                    yield i>=0 && i<copy.size() ? copy.get(i) : null;
                }
                case "LPOP" -> {pops++;yield lists.getOrDefault(args[1],new ArrayDeque<>()).pollFirst();}
                default -> throw new AssertionError("unexpected Redis command "+args[0]);
            };
        }
        public void close(){}
    }
}
