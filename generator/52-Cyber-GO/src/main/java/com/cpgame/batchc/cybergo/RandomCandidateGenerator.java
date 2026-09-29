package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Rule-capped weighted deal. Each cell is sampled from legal symbols for that reel and entry,
 * with scatter/wild ceilings applied while drawing. Joint capture kernels are not used.
 */
public final class RandomCandidateGenerator {
    private final RandomGenerator random;
    private final SymbolWeights weights;

    public RandomCandidateGenerator(RandomGenerator random) {
        this(random, SymbolWeights.localDefaults());
    }

    public RandomCandidateGenerator(RandomGenerator random, SymbolWeights weights) {
        this.random = Objects.requireNonNull(random);
        this.weights = Objects.requireNonNull(weights);
    }

    public List<String> paidBoardCandidate() {
        return board(false);
    }

    public List<String> freeBoardCandidate() {
        return board(true);
    }

    public List<String> independentLossCandidate() {
        return IndependentLoss.candidate(random);
    }

    private List<String> board(boolean free) {
        Map<String, Integer> table = free ? weights.free() : weights.normal();
        String[] cells = new String[VISIBLE_CELLS];
        for (int reel = 0; reel < REELS; reel++) {
            boolean scatterUsed = false;
            boolean wildUsed = false;
            for (int row = 0; row < ROWS; row++) {
                String symbol = pick(table, free, reel, scatterUsed, wildUsed);
                cells[reel * ROWS + row] = symbol;
                if (SCATTER.equals(symbol)) scatterUsed = true;
                if (WILD.equals(symbol)) wildUsed = true;
            }
        }
        return IndependentLoss.board(cells);
    }

    private String pick(Map<String, Integer> table, boolean free, int reel, boolean scatterUsed, boolean wildUsed) {
        int total = 0;
        for (String symbol : PAYING_SYMBOLS) total += table.get(symbol);
        if (!free && !scatterUsed) total += table.get(SCATTER);
        if (allowsWild(reel) && !wildUsed) total += table.get(WILD);
        int ticket = random.nextInt(total);
        for (String symbol : PAYING_SYMBOLS) {
            ticket -= table.get(symbol);
            if (ticket < 0) return symbol;
        }
        if (!free && !scatterUsed) {
            ticket -= table.get(SCATTER);
            if (ticket < 0) return SCATTER;
        }
        return WILD;
    }
}
