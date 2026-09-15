package com.cpgame.christmasgift.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Evidence-derived hierarchical sampler; it intentionally preserves position and adjacency dependence. */
public final class GenerationModel {
    private static final int[][] POSITION_WEIGHTS = {
        {210,216,198,223,223,233,132}, {254,229,247,192,226,213,74},
        {209,212,218,219,209,234,134}, {228,216,233,210,231,236,81},
        {199,184,197,209,205,249,192}, {233,217,238,226,201,241,79},
        {231,213,207,231,196,216,141}, {236,222,241,237,205,204,90},
        {228,216,204,196,224,229,138}
    };
    private static final int[][] TRANSITION_WEIGHTS = {
        {251,234,254,248,255,230,128}, {229,250,220,220,226,247,118},
        {260,225,258,235,234,255,140}, {251,220,254,217,254,248,101},
        {263,223,237,241,200,235,119}, {245,239,247,252,236,282,140},
        {123,119,133,107,115,141,145}
    };
    private static final int[] FEATURE_STEP_COUNTS = {0,0,0,0,1,20,14,9,1};
    private static final int[] FEATURE_TARGETS = {7,11,6,8,6,7};
    private static final int[] INITIAL_COUNTS = {0,0,42,1,2};
    private static final int[] NEXT_COUNTS = {0,151,50,13};

    private final SecureRandom random;
    private final int[] symbolAdjustments;

    public GenerationModel(SecureRandom random, int[] symbolAdjustments) {
        if (symbolAdjustments.length != 7) throw new IllegalArgumentException("seven symbol weights required");
        this.random = random;
        this.symbolAdjustments = symbolAdjustments.clone();
        for (int value : this.symbolAdjustments) if (value <= 0) {
            throw new IllegalArgumentException("symbol weights must be positive");
        }
    }

    public int ordinarySymbol(int position, int previousSymbol) {
        int[] combined = new int[7];
        for (int symbol = 1; symbol <= 7; symbol++) {
            long weight = (long) POSITION_WEIGHTS[position - 1][symbol - 1] * symbolAdjustments[symbol - 1];
            if (previousSymbol > 0) weight = weight * TRANSITION_WEIGHTS[previousSymbol - 1][symbol - 1] / 220L;
            combined[symbol - 1] = (int) Math.max(1, Math.min(Integer.MAX_VALUE, weight));
        }
        return weightedIndex(combined) + 1;
    }

    public int featureTarget() { return weightedIndex(FEATURE_TARGETS) + 1; }
    public int featureStepCount() { return weightedIndex(FEATURE_STEP_COUNTS); }
    public int initialStateSize() { return weightedIndex(INITIAL_COUNTS); }
    public int nextIncrementSize() { return weightedIndex(NEXT_COUNTS); }
    public boolean featureIsFullScreen() { return random.nextInt(45) < 2; }
    public boolean featureSymbolIsWild() { return random.nextInt(10) < 2; }

    public int choose(List<Integer> values) {
        return values.get(random.nextInt(values.size()));
    }

    public <T> void shuffle(List<T> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T value = values.get(i); values.set(i, values.get(j)); values.set(j, value);
        }
    }

    public int boundedIncrement(int preferred, int remaining, int stepsAfter, boolean leaveBlank) {
        int finalReserved = leaveBlank ? 1 : 0;
        int minNow = Math.max(1, remaining - finalReserved - 3 * stepsAfter);
        int maxNow = Math.min(3, remaining - finalReserved - stepsAfter);
        if (maxNow < minNow) throw new IllegalStateException("infeasible feature macro state");
        return Math.max(minNow, Math.min(maxNow, preferred));
    }

    public List<Integer> positions() {
        List<Integer> positions = new ArrayList<>();
        for (int position = 1; position <= 9; position++) positions.add(position);
        return positions;
    }

    private int weightedIndex(int[] weights) {
        long total = 0;
        for (int weight : weights) total += Math.max(0, weight);
        if (total <= 0) throw new IllegalArgumentException("at least one positive weight required");
        long draw = random.nextLong(total);
        long cursor = 0;
        for (int index = 0; index < weights.length; index++) {
            cursor += Math.max(0, weights[index]);
            if (draw < cursor) return index;
        }
        return weights.length - 1;
    }

    public int lossSymbol(int position,int previousSymbol,boolean[] forbidden){
        int[] combined=new int[7];
        for(int symbol=1;symbol<=6;symbol++)if(!forbidden[symbol]){
            long weight=(long)POSITION_WEIGHTS[position-1][symbol-1]*symbolAdjustments[symbol-1];
            if(previousSymbol>0)weight=weight*TRANSITION_WEIGHTS[previousSymbol-1][symbol-1]/220L;
            combined[symbol-1]=(int)Math.max(1,Math.min(Integer.MAX_VALUE,weight));
        }
        return weightedIndex(combined)+1;
    }
    public int randomIndex(int bound){return random.nextInt(bound);}
}
