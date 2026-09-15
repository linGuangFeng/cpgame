package com.cpgame.g1910.core;

import java.security.SecureRandom;

/** Formal-evidence lifecycle weights. They are adjustable model inputs, not an RTP claim. */
public final class GenerationModel {
    public static final int[] DEFAULT_PAID = {1912,1808,1686,1577,1649,1548,1614,1630,1579,1587,1656,621,603};
    public static final int[] DEFAULT_FREE = {986,976,915,932,962,918,926,908,917,934,977,245,324};
    private GenerationModel() { }
    public static int choose(SecureRandom random, int[] weights) {
        if(weights.length!=13)throw new IllegalArgumentException("exactly 13 symbol weights are required");
        int total=0; for(int weight:weights) total+=weight;
        int value=random.nextInt(total);
        for(int i=0;i<weights.length;i++){ value-=weights[i]; if(value<0)return i+1; }
        throw new IllegalStateException("weight selection failed");
    }
}
