package com.cpgame.luckynightmarket;

import java.util.*;

/** Loader failure and sparse-entry boundaries; no Redis connection or mutation. */
public final class LoaderBoundaryTest {
    private static int checks;
    private interface Operation { void run() throws Exception; }
    private static void fails(Operation operation) throws Exception {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; }
        throw new AssertionError("Expected rejected boundary");
    }
    private static void require(boolean value) { checks++; if(!value)throw new AssertionError("Boundary mismatch"); }
    public static void main(String[] args)throws Exception {
        require(GeneratorMain.index(false,2470).equals("PerKeyList_000002470"));
        require(GeneratorMain.key(false,2470,0).equals("BetLog:000002470:000000"));
        require(GeneratorMain.key(true,2470,500).equals("MaryLog:000002470:000500"));
        require(GeneratorMain.index(GeneratorMain.Pool.WHEEL,2470).equals("PreKeyList_100002470"));
        require(GeneratorMain.key(GeneratorMain.Pool.WHEEL,2470,500).equals("PreLog:100002470:000500"));
        require(GeneratorMain.pool(RoundFact.Mode.LUCKY_WHEEL)==GeneratorMain.Pool.WHEEL);
        require(GeneratorMain.pool(RoundFact.Mode.LUCKY_FEATURE)==GeneratorMain.Pool.FEATURE);
        fails(()->GeneratorMain.key(false,2471,0));
        fails(()->GeneratorMain.key(false,2470,-1));
        fails(()->GeneratorMain.key(false,2470,1000000));
        for(var property:Map.of("seed","1","retention.special-per-multiplier","0","retention.normal-per-multiplier","0","generation.count","0","generation.batch-size","1001","range.special-min","501","range.normal-max","0").entrySet()) {
            Properties p=new Properties();p.setProperty(property.getKey(),property.getValue());fails(()->GeneratorMain.validateConfig(p));
        }
        Properties nan=new Properties();nan.setProperty("model.column-backoff-probability","NaN");fails(()->new DealingModel(nan));
        List<List<String>> commands=List.of(List.of("MULTI"),List.of("ZADD","index","0","0"),List.of("RPUSH","list","member"),List.of("LTRIM","list","-100","-1"),List.of("EXEC"));
        GeneratorMain.verifyTransaction(commands,List.of("OK","QUEUED","QUEUED","QUEUED",List.of(1L,1L,"OK")));checks++;
        fails(()->GeneratorMain.verifyTransaction(commands,Arrays.asList("OK","QUEUED","QUEUED","QUEUED",null)));
        fails(()->GeneratorMain.verifyTransaction(commands,List.of("OK","QUEUED","QUEUED","QUEUED",List.of(1L,1L))));
        fails(()->GeneratorMain.verifyTransaction(commands,List.of("OK","QUEUED","OK","QUEUED",List.of(1L,1L,"OK"))));
        fails(()->GeneratorMain.verifyTransaction(commands,List.of("OK","QUEUED","QUEUED","QUEUED",List.of(1L,1L,0L))));
        DealingModel model=new DealingModel(new Properties());
        Set<List<Integer>> wheelBoards=new HashSet<>();Set<Integer> prizes=new HashSet<>();
        for(int i=0;i<256;i++) {
            RoundFact round=model.generate(RoundFact.Mode.LUCKY_WHEEL);
            RoundFact.Step step=round.steps().get(0);wheelBoards.add(step.ps());prizes.add(step.wheelMultiplier());
            require(step.muls().get(0)>0&&step.muls().get(1)==0&&step.muls().get(2)>0);
            require(step.ps().subList(0,6).stream().noneMatch(s->s==0));
            require(GameRuleCore.totalUnits(round)==ResultUtil.totalUnits(round));
        }
        require(wheelBoards.size()>2);require(prizes.equals(Set.of(100,200)));
        System.out.println(Json.stringify(Json.map("status","PASS","checks",checks,"wheelProbeRounds",256,"uniqueWheelBoards",wheelBoards.size(),"observedPrizes",prizes,"redisWrites",0)));
    }
}
