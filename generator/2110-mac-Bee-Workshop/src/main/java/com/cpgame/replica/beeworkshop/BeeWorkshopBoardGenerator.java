package com.cpgame.replica.beeworkshop;

import java.util.Random;

/**
 * Weighted 5×3 deal used when generator.properties symbol weights are loaded.
 * Wild is illegal on reels 1 and 5. Scatter/Wild board caps come from the empirical model.
 */
public final class BeeWorkshopBoardGenerator {
    public static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;
    public static final int SYMBOLS = 9;

    private final int[] weights;

    public BeeWorkshopBoardGenerator(int[] weights) {
        if (weights == null || weights.length != SYMBOLS) throw new IllegalArgumentException("Bee Workshop has 9 symbols");
        this.weights = weights.clone();
    }

    public static int[] specialEntryOpeningWeights(int[] ordinary) {
        int[] out = ordinary.clone();
        out[GameRuleCore.SCATTER - 1] = Math.multiplyExact(out[GameRuleCore.SCATTER - 1], SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        return out;
    }

    public int[] generate(Random random) {
        return generate(random, false);
    }

    public int[] generate(Random random, boolean specialOpening) {
        int[] ordinary = weights;
        int[] first = specialOpening ? specialEntryOpeningWeights(ordinary) : ordinary;
        int[] board = new int[GameRuleCore.CELLS];
        for (int reel = 0; reel < GameRuleCore.REELS; reel++) {
            boolean seenTrigger = false;
            for (int row = 0; row < GameRuleCore.ROWS; row++) {
                int symbol = draw(random, reel, seenTrigger ? ordinary : first);
                if (symbol == GameRuleCore.SCATTER) seenTrigger = true;
                board[reel * GameRuleCore.ROWS + row] = symbol;
            }
        }
        return board;
    }

    private int draw(Random random, int reel, int[] source) {
        int total = 0;
        int[] local = source.clone();
        if (reel == 0 || reel == 4) local[GameRuleCore.WILD - 1] = 0;
        for (int weight : local) {
            if (weight < 0) throw new IllegalArgumentException("negative symbol weight");
            total = Math.addExact(total, weight);
        }
        if (total <= 0) throw new IllegalStateException("no legal symbols for reel " + (reel + 1));
        int pick = random.nextInt(total);
        for (int symbol = 1; symbol <= SYMBOLS; symbol++) {
            pick -= local[symbol - 1];
            if (pick < 0) return symbol;
        }
        throw new IllegalStateException("weighted draw failed");
    }
}
