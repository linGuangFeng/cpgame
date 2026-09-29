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
        require(GeneratorMain.index(false,8002470).equals("PerKeyList_008002470"));
        require(GeneratorMain.key(false,8002470,0).equals("BetLog:008002470:000000"));
        require(GeneratorMain.key(true,8002470,500).equals("MaryLog:008002470:000500"));
        require(GeneratorMain.index(GeneratorMain.Pool.WHEEL,8002470).equals("PerKeyList_108002470"));
        require(GeneratorMain.key(GeneratorMain.Pool.WHEEL,8002470,500).equals("BetLog:108002470:000500"));
        require(GeneratorMain.pool(RoundFact.Mode.LUCKY_WHEEL)==GeneratorMain.Pool.WHEEL);
        require(GeneratorMain.pool(RoundFact.Mode.LUCKY_FEATURE)==GeneratorMain.Pool.FEATURE);
        require(GeneratorMain.retention(new Properties(),GeneratorMain.Pool.WHEEL)==300);
        require(GeneratorMain.retention(new Properties(),GeneratorMain.Pool.FEATURE)==50);
        fails(()->GeneratorMain.key(false,8002471,0));
        fails(()->GeneratorMain.key(false,8002470,-1));
        fails(()->GeneratorMain.key(false,8002470,1000000));
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
        Set<List<Integer>> wheelBoards=new HashSet<>();Set<Integer> prizes=new HashSet<>();Set<Long> wheelUnits=new HashSet<>();
        for(int i=0;i<4096;i++) {
            DealingModel.Attempt generated=model.attemptScenario(DealingModel.Scenario.LUCKY_WHEEL,false);
            require(generated.accepted());RoundFact round=generated.round();
            RoundFact.Step step=round.steps().get(0);wheelBoards.add(step.ps());prizes.add(step.wheelMultiplier());
            require(step.muls().get(0)>0&&step.muls().get(1)==0&&step.muls().get(2)>0);
            require(GameRuleCore.totalUnits(round)==ResultUtil.totalUnits(round));
            wheelUnits.add(ResultUtil.totalUnits(round));
        }
        require(wheelBoards.size()>3000);require(prizes.equals(Arrays.stream(RuleBasedBoardGenerator.WHEEL_PRIZES).boxed().collect(java.util.stream.Collectors.toSet())));require(wheelUnits.size()>40);
        System.out.println(Json.stringify(Json.map("status","PASS","checks",checks,"wheelProbeRounds",4096,"uniqueWheelBoards",wheelBoards.size(),"uniqueWheelPayouts",wheelUnits.size(),"observedPrizes",prizes,"redisWrites",0)));
    }
}
