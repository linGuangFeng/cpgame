package com.cpgame.sambasensation;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.generator.CompleteRoundFactory;
import com.cpgame.sambasensation.generator.GeneratorConfig;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/** 显式测试入口；固定seed只用于复现统计，不被正式Loader读取。 */
public final class DistributionProbe {
    public static void main(String[] args) throws Exception {
        int count = args.length == 0 ? 10000 : Integer.parseInt(args[0]);
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        CompleteRoundFactory factory = new CompleteRoundFactory(config);
        SplittableRandom random = new SplittableRandom(22900001L);
        EnumMap<GameRuleCore.RoundClass, Integer> outcomes = new EnumMap<>(GameRuleCore.RoundClass.class);
        int[] stepCounts = new int[7];
        int[] betTypes = new int[4];
        long[][][] paidSymbols = new long[3][3][11];
        int[][] paidBoards = new int[3][3];
        long[][] freeOuter = new long[3][10];
        long[][] freeBig = new long[3][10];
        long[][] featureBuySymbols = new long[3][11];
        int featureBuyRounds = 0;
        int freePages = 0;
        Map<String, Integer> stateElementCountVectors = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            CompleteRoundFactory.GeneratedRound generated = factory.generateNatural(random);
            GameRuleCore.CompleteRoundFact fact = generated.fact();
            outcomes.merge(generated.evaluation().roundClass(), 1, Integer::sum);
            stepCounts[fact.steps().size()]++;
            betTypes[fact.betType()]++;
            String stateVector = generated.evaluation().roundClass() + "|" + fact.steps().size()
                    + "|PAID:" + fact.betType() + "x15"
                    + (fact.steps().size() == 6 ? ";FREE:5x3x(OUTER6+BIG1)" : "");
            stateElementCountVectors.merge(stateVector, 1, Integer::sum);
            if (fact.entryKind() == GameRuleCore.EntryKind.PAID_INITIAL) for (int axis = 0; axis < fact.steps().get(0).boards().size(); axis++) {
                paidBoards[fact.betType() - 1][axis]++;
                for (int symbol : fact.steps().get(0).boards().get(axis)) paidSymbols[fact.betType() - 1][axis][symbol]++;
            }
            if (fact.entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL) {
                featureBuyRounds++;
                for (int axis = 0; axis < 3; axis++) for (int symbol : fact.steps().get(0).boards().get(axis)) featureBuySymbols[axis][symbol]++;
            }
            for (int step = 1; step < fact.steps().size(); step++) for (int axis = 0; axis < fact.steps().get(step).boards().size(); axis++) {
                int[] board = fact.steps().get(step).boards().get(axis);
                int big = board[1];
                freeBig[axis][big]++;
                int[] outer = {0,4,5,9,10,14};
                for (int position : outer) freeOuter[axis][board[position]]++;
                freePages++;
            }
        }
        System.out.println("generated=" + count);
        System.out.println("outcomes=" + outcomes);
        System.out.println("stepCounts=" + Arrays.toString(stepCounts));
        System.out.println("betTypes=" + Arrays.toString(betTypes));
        System.out.println("paidBoards=" + Arrays.deepToString(paidBoards));
        System.out.println("paidSymbols=" + Arrays.deepToString(paidSymbols));
        System.out.println("freeOuter=" + Arrays.deepToString(freeOuter));
        System.out.println("freeBig=" + Arrays.deepToString(freeBig));
        System.out.println("featureBuyRounds=" + featureBuyRounds);
        System.out.println("featureBuySymbols=" + Arrays.deepToString(featureBuySymbols));
        System.out.println("freeAxisBoards=" + freePages);
        System.out.println("stateElementCountVectors=" + stateElementCountVectors);
    }
}
