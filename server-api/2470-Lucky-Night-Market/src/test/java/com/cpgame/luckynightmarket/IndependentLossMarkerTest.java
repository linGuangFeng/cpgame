package com.cpgame.luckynightmarket;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/** Standalone regression, like the existing core tests; no external Redis connection. */
public final class IndependentLossMarkerTest {
    private static int checks;
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejected(String member){
        checks++;try{RoundCodec.decode(member);}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Accepted malformed marker: "+member);
    }
    public static void main(String[] args)throws Exception {
        DealingModel model=new DealingModel(new Properties());
        Set<RoundFact.Step> ordinary=new HashSet<>(),feature=new HashSet<>();
        for(int i=0;i<10000;i++){
            for(boolean free:new boolean[]{false,true}){
                RoundFact.Step step=model.independentLoss(free);
                require(ResultUtil.evaluate(step,free).units()==0,"Actual zero payout");
                require(!step.wheel(),"Marker must not become a wheel");
                caps(model,step,free?"featureLater":"ordinary");
                (free?feature:ordinary).add(step);
            }
        }
        require(ordinary.size()>100,"Ordinary marker variability");
        require(feature.size()>100,"Feature marker variability");
        RoundFact loss=model.generate(RoundFact.Mode.ORDINARY_LOSS);
        require(RoundCodec.encode(loss).equals("LNM1|L|#"),"Ordinary marker format");
        RoundCodec.verifyEquivalent(loss,RoundCodec.decode("LNM1|L|#"));
        Map<RoundFact.Mode,Integer> rounds=new EnumMap<>(RoundFact.Mode.class);
        int featureMarkers=0;
        String mixed=null;
        for(RoundFact.Mode mode:RoundFact.Mode.values()){
            for(int n=0;n<200;n++){
                RoundFact before=model.generate(mode);
                String full=RoundCodec.encodeFull(before),compact=RoundCodec.encode(before);
                require(before.equals(RoundCodec.decode(full)),"Legacy byte/fact round trip");
                RoundFact after=RoundCodec.decode(compact);
                RoundCodec.verifyEquivalent(before,after);
                require(compact.equals(RoundCodec.encode(after)),"Compact canonical round trip");
                require(ResultUtil.totalUnits(before)==ResultUtil.totalUnits(after),"Total payout preserved");
                long a=0,b=0;
                for(int i=0;i<before.steps().size();i++){
                    a+=ResultUtil.evaluate(before.steps().get(i),before.feature()).units();
                    b+=ResultUtil.evaluate(after.steps().get(i),after.feature()).units();
                    require(a==b,"Cumulative payout at every step");
                    if(!RoundCodec.markerEligible(before,i))require(before.steps().get(i).equals(after.steps().get(i)),"Retain trigger/wheel/wins exactly");
                }
                if(mode==RoundFact.Mode.LUCKY_FEATURE){
                    String[] tokens=compact.split("\\|",-1)[2].split(";",-1);
                    require(tokens.length==8 && !tokens[0].contains("#"),"Feature start remains full; eight steps");
                    for(String token:tokens)if(token.equals("#"))featureMarkers++;
                    if(compact.contains("#"))mixed=compact;
                }
                if(mode==RoundFact.Mode.LUCKY_WHEEL)require(full.equals(compact),"Wheel remains full");
                rounds.merge(mode,1,Integer::sum);
            }
        }
        require(featureMarkers>100 && mixed!=null,"Exercise later-feature zero markers");
        for(String bad:List.of("#","0","LNM1|L|0","LNM1|L|#1","LNM1|L|##","LNM1|L|#;", "LNM1|B|#","LNM1|W|#","LNM1|F|#;#;#;#;#;#;#;#"))rejected(bad);
        rejected(mixed+";");
        rejected(mixed.replaceFirst(";#",";#1"));
        String member=mixed;
        long units=ResultUtil.totalUnits(RoundCodec.decode(member));
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try{
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++)tasks.add(()->{for(int j=0;j<100;j++){
                RoundFact value=RoundCodec.decode(member);
                if(value.steps().size()!=8 || ResultUtil.totalUnits(value)!=units)throw new AssertionError("Concurrent decode state leak");
            }return null;});
            for(Future<Void> result:pool.invokeAll(tasks))result.get();
        }finally{pool.shutdownNow();}
        Map<String,List<String>> retained=new LinkedHashMap<>();
        RoundFact small=new RoundFact(RoundFact.Mode.ORDINARY_WIN,List.of(new RoundFact.Step(
                List.of(6,1,2,6,3,4,6,5,1),List.of(1,1,1),0)));
        for(RoundFact round:List.of(loss,small,RoundCodec.decode(mixed),model.generate(RoundFact.Mode.LUCKY_WHEEL))){
            String key=GeneratorMain.key(GeneratorMain.pool(round.mode()),2470,ResultUtil.totalUnits(round));
            retained.put(key,List.of(RoundCodec.encode(round)));
        }
        require(Boolean.TRUE.equals(PoolInstaller.verifyCoverageBeforeRedisWrite(new Properties(),retained)
                .get("coverageVerifiedBeforeRedisWrite")),"Installer preflight accepts regenerated markers without Redis writes");
        if(args.length==1){
            Path out=Path.of(args[0]);Files.createDirectories(out);
            Files.writeString(out.resolve("sample-marker.txt"),mixed+"\n");
            Files.writeString(out.resolve("sample-full.txt"),RoundCodec.encodeFull(RoundCodec.decode(mixed))+"\n");
        }
        System.out.println(Json.stringify(Json.map("status","PASS","checks",checks,"zeroResults",20000,
                "ordinaryDistinct",ordinary.size(),"featureDistinct",feature.size(),"rounds",rounds,
                "featureZeroMarkers",featureMarkers,"concurrentDecodes",800)));
    }
    private static int integer(Object n){return ((Number)n).intValue();}
    private static void caps(DealingModel model,RoundFact.Step step,String name){
        Map<String,Object> e=Json.object(Json.object(model.metadata().get("entries")).get(name));
        int[] counts=new int[7];
        for(int p=0;p<9;p++){
            int symbol=step.ps().get(p);counts[symbol]++;
            require(((List<?>)((List<?>)e.get("positionSymbols")).get(p)).stream().anyMatch(v->integer(v)==symbol),"Entry position symbols");
        }
        require(counts[0]<=integer(e.get("wildBoardMax")),"Wild total cap");
        for(int c=0;c<3;c++){
            int wild=0;for(int row=0;row<3;row++)if(step.ps().get(c*3+row)==0)wild++;
            require(wild<=integer(((List<?>)e.get("wildColumnMax")).get(c)),"Wild column cap");
        }
        for(int s=0;s<7;s++)require(counts[s]<=integer(((List<?>)e.get("symbolBoardMax")).get(s)),"Symbol board cap");
        require(step.muls().stream().filter(v->v==0).count()<=integer(e.get("maxTickets")),"Ticket count cap");
    }
}
