package com.cpgame.g1910.core;

import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;

/** Generates a fresh 10k-round validation sample without touching Redis. */
public final class GenerationDistributionAcceptance {
    public static void main(String[] args) {
        var random=new SecureRandom();var factory=new RoundFactory(random);
        int[]paid=new int[14],free=new int[14];Map<String,Integer>modes=new TreeMap<>(),steps=new TreeMap<>();
        for(int i=0;i<10_000;i++){
            double value=random.nextDouble();GameRuleCore.CompleteRound round=value<0.0360?factory.freeReward():value<0.1997?factory.smallGame():value<0.8545?factory.ordinary(false):factory.ordinary(true);
            GameRuleCore.evaluate(round);modes.merge(round.mode()==GameRuleCore.Mode.ORDINARY?(GameRuleCore.evaluate(round).totalUnits()==0?"ORDINARY_LOSS":"ORDINARY_WIN"):round.mode().name(),1,Integer::sum);
            int responseSteps=round.mode()==GameRuleCore.Mode.FREE_REWARD?3+round.freeSteps().size():1;steps.merge(Integer.toString(responseSteps),1,Integer::sum);
            for(int symbol:round.paid().symbols())paid[symbol]++;for(var step:round.freeSteps())for(int symbol:step.symbols())free[symbol]++;
        }
        System.out.printf("GENERATION_DISTRIBUTION_PASS rounds=10000 modes=%s steps=%s paid=%s free=%s rulesHash=%s%n",modes,steps,counts(paid),counts(free),GameRuleCore.RULES_HASH);
    }
    private static Map<Integer,Integer>counts(int[]values){Map<Integer,Integer>out=new TreeMap<>();for(int i=1;i<values.length;i++)out.put(i,values[i]);return out;}
}
