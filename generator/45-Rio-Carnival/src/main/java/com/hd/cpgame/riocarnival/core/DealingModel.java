package com.hd.cpgame.riocarnival.core;

import java.util.List;
import java.util.Map;

/** Rule caps and free-entry choices. No capture histogram. */
public final class DealingModel {
    public static final int MAX_STEPS = GameRules.MAX_STEPS;
    public static final int MAX_RETRIGGERS = GameRules.MAX_RETRIGGERS;
    private DealingModel() {}

    public static List<String> board(boolean free, RandomSource random) {
        return board(free, random, GameRules.DEFAULT_NORMAL_WEIGHTS, GameRules.DEFAULT_FREE_WEIGHTS);
    }
    public static List<String> board(boolean free, RandomSource random, Map<String,Integer> normalWeights,
                                     Map<String,Integer> freeWeights) {
        return RandomBoardCandidateGenerator.deal(random, free ? freeWeights : normalWeights,
                0, GameRules.MAX_SCATTER_BOARD, false);
    }
    public static int[] initial(int scatters, RandomSource random) {
        int[] counts = GameRules.initialChoices(scatters);
        if (counts.length == 0) throw new IllegalArgumentException("No free-spin award for scatter count");
        int spins = counts[random.nextInt(counts.length)];
        int multiplier = GameRules.FREE_MULTIPLIERS[random.nextInt(GameRules.FREE_MULTIPLIERS.length)];
        return new int[]{spins, multiplier};
    }
    public static void checkGeneratedBoard(List<String> board, boolean free) {
        if (board == null || board.size() != 15) throw new IllegalArgumentException("board must contain 15 symbols");
        int scat = 0, wild = 0;
        for (int reel = 0; reel < 5; reel++) {
            int scatReel = 0, wildReel = 0;
            for (int row = 0; row < 3; row++) {
                String symbol = board.get(reel * 3 + row);
                if (!GameRules.SYMBOLS.contains(symbol)) throw new IllegalArgumentException("unknown symbol");
                if (GameRules.SCATTER.equals(symbol)) { scat++; scatReel++; }
                if (GameRules.WILD.equals(symbol)) { wild++; wildReel++; }
            }
            if (scatReel > GameRules.MAX_SCATTER_REEL || wildReel > GameRules.MAX_WILD_REEL)
                throw new IllegalArgumentException("reel special cap");
        }
        if (scat > GameRules.MAX_SCATTER_BOARD || wild > GameRules.MAX_WILD_BOARD)
            throw new IllegalArgumentException("board special cap");
    }

    public static List<String> lossBoard(RandomSource random){
        return lossBoard(random, GameRules.DEFAULT_NORMAL_WEIGHTS);
    }
    public static List<String> lossBoard(RandomSource random, Map<String,Integer> weights){
        return RandomBoardCandidateGenerator.constructLoss(random, weights);
    }
}
