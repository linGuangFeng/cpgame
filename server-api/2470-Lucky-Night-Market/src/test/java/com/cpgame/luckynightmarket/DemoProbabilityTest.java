package com.cpgame.luckynightmarket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Measures the actual Controller demo sampler; no generator probabilities are involved. */
public final class DemoProbabilityTest {
    private static int assertions;
    private static final double WIN_PROBABILITY=.60,SMALL_PROBABILITY=.90;
    private static final List<String> SMALL=List.of(key(1),key(2),key(24));
    private static final List<String> LARGER=List.of(key(25),key(49),key(50),key(375));
    private static final List<String> ALL_KEYS=new ArrayList<>();
    static { ALL_KEYS.add(key(0));ALL_KEYS.addAll(SMALL);ALL_KEYS.addAll(LARGER); }

    public static void main(String[] args) throws Exception {
        boundaries();
        int windows=10000,allFour=0,atLeastThree=0,allWinnerModes=0;
        int minCommon=20,maxSpecial=0,ordinaryBandDraws=0,smallBandDraws=0,repeatedAdjacentModes=0;
        Map<String,Integer> total=new LinkedHashMap<>();
        Map<String,Integer> coverageHistogram=new LinkedHashMap<>();
        Set<String> distinctSequences=new HashSet<>();
        Random rng=new Random(247020092026L),bandRng=new Random(247090092026L);
        for(int window=0;window<windows;window++){
            Map<String,Integer> counts=new LinkedHashMap<>();
            StringBuilder sequence=new StringBuilder();String previous=null;
            for(int spin=0;spin<20;spin++){
                String mode=ControllerMain.chooseMode(counts,rng,WIN_PROBABILITY).name();
                counts.merge(mode,1,Integer::sum);total.merge(mode,1,Integer::sum);
                sequence.append(mode).append(';');
                if(mode.equals(previous))repeatedAdjacentModes++;
                previous=mode;
                if(mode.equals("ORDINARY_WIN")){
                    List<String> selected=ControllerMain.chooseOrdinaryBand(ALL_KEYS,bandRng,SMALL_PROBABILITY);
                    require(selected.equals(SMALL)||selected.equals(LARGER),"Only existing positive payout keys may be selected");
                    ordinaryBandDraws++;if(selected.equals(SMALL))smallBandDraws++;
                }
            }
            distinctSequences.add(sequence.toString());
            int common=counts.getOrDefault("ORDINARY_LOSS",0)+counts.getOrDefault("ORDINARY_WIN",0);
            minCommon=Math.min(minCommon,common);maxSpecial=Math.max(maxSpecial,20-common);
            require(common>10,"Ordinary wins and losses must be the majority in every 20-round test window: "+counts);
            int coverage=counts.size();coverageHistogram.merge(Integer.toString(coverage),1,Integer::sum);
            if(coverage==4)allFour++;if(coverage>=3)atLeastThree++;
            if(counts.containsKey("ORDINARY_WIN")&&counts.containsKey("LUCKY_WHEEL")&&counts.containsKey("LUCKY_FEATURE"))allWinnerModes++;
        }
        require(allFour>=windows*.98,"Four-mode coverage below 98%: "+allFour+"/"+windows);
        require(distinctSequences.size()>windows*.90&&repeatedAdjacentModes>0,"Demo must draw varied sequences, not a fixed rotation");
        Map<String,Double> modeRates=new LinkedHashMap<>();
        Map<String,Double> targets=Map.of("ORDINARY_LOSS",.40,"ORDINARY_WIN",.40,"LUCKY_WHEEL",.10,"LUCKY_FEATURE",.10);
        for(var target:targets.entrySet()){
            double rate=total.getOrDefault(target.getKey(),0)/(windows*20.0);modeRates.put(target.getKey(),rate);
            require(Math.abs(rate-target.getValue())<=.015,"Short-window mode share drift for "+target.getKey()+": "+rate);
        }
        // Fresh 20-round sessions include finite-window correction bias. A long session
        // independently checks convergence to the intended mode shares without resets.
        int longSessionRounds=200000;
        Map<String,Integer> longSessionCounts=new LinkedHashMap<>();
        Random longSessionRng=new Random(2470200000L);
        for(int spin=0;spin<longSessionRounds;spin++){
            String mode=ControllerMain.chooseMode(longSessionCounts,longSessionRng,WIN_PROBABILITY).name();
            longSessionCounts.merge(mode,1,Integer::sum);
        }
        Map<String,Double> longSessionRates=new LinkedHashMap<>();
        for(var target:targets.entrySet()){
            double rate=longSessionCounts.getOrDefault(target.getKey(),0)/(double)longSessionRounds;
            longSessionRates.put(target.getKey(),rate);
            require(Math.abs(rate-target.getValue())<=.002,"Long-session mode share drift for "+target.getKey()+": "+rate);
        }
        double lossRate=modeRates.get("ORDINARY_LOSS"),smallRate=smallBandDraws/(double)ordinaryBandDraws;
        require(Math.abs(smallRate-SMALL_PROBABILITY)<=.005,"Ordinary small-win band is not 90%: "+smallRate);
        Map<String,Object> report=Json.map("gameId",2470,"status","PASS","windowCount",windows,"paidRoundsPerWindow",20,
                "assertionsPassed",assertions,"modeCounts",total,"modeRates",modeRates,"targetModeRates",targets,
                "shortWindowModeRateTolerance",.015,"longSessionPaidRounds",longSessionRounds,
                "longSessionModeCounts",longSessionCounts,"longSessionModeRates",longSessionRates,
                "longSessionModeRateTolerance",.002,
                "coverageHistogram",coverageHistogram,"allFourModesWindows",allFour,
                "allFourModesRate",allFour/(double)windows,"atLeastThreeModesWindows",atLeastThree,
                "allWinnerModesWindows",allWinnerModes,"lossRate",lossRate,
                "minOrdinaryPlusLossPerWindow",minCommon,"maxSpecialModesPerWindow",maxSpecial,
                "distinct20RoundSequences",distinctSequences.size(),"repeatedAdjacentModes",repeatedAdjacentModes,
                "ordinarySmallBandDraws",smallBandDraws,"ordinaryBandDraws",ordinaryBandDraws,
                "ordinarySmallBandRate",smallRate,"ordinarySmallBandUnits","1..24",
                "boundaryCoverage",List.of("first random draw chooses win/loss, second chooses winner type",
                    "win probability 0 and 1 with contradictory historical counts","invalid probabilities rejected",
                    "small band includes 1 and 24, larger includes 25, zero excluded",
                    "small probability 0 and 1","missing small/larger band falls back to existing band",
                    "empty and zero-only pools stay empty","sampler does not mutate caller counts or keys"),
                "sampler","Actual ControllerMain sampler: target 60% wins, winner shares 4:1:1, deficit-weighted random draws; 90% of ordinary wins select existing 1..24-unit buckets",
                "scope","Demo sampler only. This test does not generate rounds, access Redis, or change formal Loader probabilities. Seeded Random is confined to repeatable tests.");
        String json=Json.stringify(report);
        if(args.length>0)Files.writeString(Path.of(args[0]),json+"\n");
        System.out.println(json);
    }

    private static void boundaries(){
        Map<String,Integer> empty=new LinkedHashMap<>();
        require(ControllerMain.chooseMode(empty,new Draws(.10),.60)==RoundFact.Mode.ORDINARY_LOSS,"Loss needs only the first draw");
        require(ControllerMain.chooseMode(empty,new Draws(.90,.20),.60)==RoundFact.Mode.ORDINARY_WIN,"Winner second draw chooses ordinary");
        require(ControllerMain.chooseMode(empty,new Draws(.90,.75),.60)==RoundFact.Mode.LUCKY_WHEEL,"Winner second draw chooses wheel");
        require(ControllerMain.chooseMode(empty,new Draws(.90,.99),.60)==RoundFact.Mode.LUCKY_FEATURE,"Winner second draw chooses feature");
        require(empty.isEmpty(),"chooseMode must not modify session counts");
        Random rng=new Random(24700001L);
        Map<String,Integer> zero=new LinkedHashMap<>(Map.of("ORDINARY_LOSS",10000));
        Map<String,Integer> one=new LinkedHashMap<>(Map.of("ORDINARY_WIN",10000,"LUCKY_WHEEL",10000,"LUCKY_FEATURE",10000));
        for(int i=0;i<2000;i++){
            RoundFact.Mode loss=ControllerMain.chooseMode(zero,rng,0),win=ControllerMain.chooseMode(one,rng,1);
            require(loss==RoundFact.Mode.ORDINARY_LOSS,"Zero win probability must forbid every winner mode");
            require(win!=RoundFact.Mode.ORDINARY_LOSS,"One win probability must forbid losses");
            zero.merge(loss.name(),1,Integer::sum);one.merge(win.name(),1,Integer::sum);
        }
        List<String> before=List.copyOf(ALL_KEYS);
        require(ControllerMain.chooseOrdinaryBand(ALL_KEYS,new Draws(.999999),1).equals(SMALL),"100% small selects exactly units 1..24");
        require(ControllerMain.chooseOrdinaryBand(ALL_KEYS,new Draws(0),0).equals(LARGER),"0% small selects units >=25");
        require(ControllerMain.chooseOrdinaryBand(ALL_KEYS,new Draws(.8999),.90).equals(SMALL),"Draw below .90 selects small band");
        require(ControllerMain.chooseOrdinaryBand(ALL_KEYS,new Draws(.90),.90).equals(LARGER),"Draw at .90 selects larger band");
        require(ControllerMain.chooseOrdinaryBand(SMALL,new Draws(),0).equals(SMALL),"Missing larger band falls back to existing small keys even at p=0");
        require(ControllerMain.chooseOrdinaryBand(LARGER,new Draws(),1).equals(LARGER),"Missing small band falls back to existing larger keys even at p=1");
        require(ControllerMain.chooseOrdinaryBand(List.of(),new Draws(),.90).isEmpty(),"Empty pool must not invent fallback keys");
        require(ControllerMain.chooseOrdinaryBand(List.of(key(0)),new Draws(),.90).isEmpty(),"Loss key cannot be substituted for an ordinary win");
        require(ALL_KEYS.equals(before),"Band selection must not mutate its source keys");
        for(double p:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.001,1.001}){
            rejected(()->ControllerMain.chooseMode(Map.of(),rng,p));
            rejected(()->ControllerMain.chooseOrdinaryBand(ALL_KEYS,rng,p));
        }
    }
    private static String key(int units){return String.format(java.util.Locale.ROOT,"BetLog:000002470:%06d",units);}
    private static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable action){assertions++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid probability was accepted");}
    /** Exact draw sequence verifies the two-stage contract without copying the weighting formula. */
    private static final class Draws extends Random {
        private final double[] values;private int index;
        Draws(double... values){this.values=values;}
        @Override public double nextDouble(){if(index==values.length)throw new AssertionError("Unexpected extra random draw");return values[index++];}
    }
}
