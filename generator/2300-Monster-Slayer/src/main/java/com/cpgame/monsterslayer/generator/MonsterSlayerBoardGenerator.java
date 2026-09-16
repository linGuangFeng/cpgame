package com.cpgame.monsterslayer.generator;

import com.cpgame.monsterslayer.core.GameRuleCore;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Independent per-cell board sampler. This class only draws symbols; win/loss
 * evaluation stays in ResultUtil. Weights are sample counts from 1,237 ordinary
 * paid first pages (15 cells each = 18,555), not a claimed long-run RTP.
 */
public final class MonsterSlayerBoardGenerator {
    public static final int SCATTER = GameRuleCore.SCATTER;
    public static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;
    /** IDs 1..10 then Scatter=100 at index 10. */
    public static final int[] DEFAULT_NORMAL_WEIGHTS = {
            2701, 2042, 1699, 1788, 1668, 1670, 1651, 1680, 1611, 1640, 405};
    public static final int[] DEFAULT_MARY_WEIGHTS = DEFAULT_NORMAL_WEIGHTS.clone();

    private final Random random;
    private final int[] normalWeights;
    private final int[] maryWeights;

    public MonsterSlayerBoardGenerator() {
        this(new SecureRandom());
    }

    public MonsterSlayerBoardGenerator(Random random) {
        this(random, DEFAULT_NORMAL_WEIGHTS, DEFAULT_MARY_WEIGHTS);
    }

    public MonsterSlayerBoardGenerator(Random random, int[] normalWeights) {
        this(random, normalWeights, DEFAULT_MARY_WEIGHTS);
    }

    public MonsterSlayerBoardGenerator(Random random, int[] normalWeights, int[] maryWeights) {
        if (random == null) throw new IllegalArgumentException("random is required");
        this.random = random;
        this.normalWeights = validatedWeights(normalWeights, "normal");
        this.maryWeights = validatedWeights(maryWeights, "Mary");
    }

    public static int[] defaultNormalWeights() { return DEFAULT_NORMAL_WEIGHTS.clone(); }
    public static int[] defaultMaryWeights() { return DEFAULT_MARY_WEIGHTS.clone(); }

    public static int[] specialEntryOpeningWeights(int[] ordinary) {
        int[] boosted = validatedWeights(ordinary, "ordinary opening");
        boosted[10] = Math.multiplyExact(boosted[10], SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        return boosted;
    }

    public int[] generateOrdinary() {
        return generate(false, false);
    }

    public int[] generate(boolean freeMode, boolean specialOpening) {
        int[] ordinary = freeMode ? maryWeights : normalWeights;
        int[] first = (!freeMode && specialOpening) ? specialEntryOpeningWeights(ordinary) : ordinary;
        int scatterCap = specialOpening ? 2 : 1;
        int[] board = new int[GameRuleCore.CELLS];
        int scatters = 0;
        boolean[] seenTrigger = new boolean[GameRuleCore.COLS];
        for (int cell = 0; cell < board.length; cell++) {
            int col = cell / GameRuleCore.ROWS;
            boolean scatterAllowed = cell >= 3 && cell < 12 && scatters < scatterCap;
            int[] weights = seenTrigger[col] ? ordinary : first;
            board[cell] = nextSymbol(weights, scatterAllowed);
            if (board[cell] == SCATTER) {
                scatters++;
                seenTrigger[col] = true;
            }
        }
        return board;
    }

    public int nextSymbol(boolean freeMode) {
        return nextSymbol(freeMode ? maryWeights : normalWeights, true);
    }

    private int nextSymbol(int[] weights, boolean scatterAllowed) {
        int total = 0;
        for (int i = 0; i < 10; i++) total += weights[i];
        if (scatterAllowed) total += weights[10];
        if (total <= 0) throw new IllegalStateException("no positive symbol weight for this cell");
        int value = random.nextInt(total);
        for (int i = 0; i < 10; i++) {
            value -= weights[i];
            if (value < 0) return i + 1;
        }
        return SCATTER;
    }

    static boolean legalOrdinary(int[] board) {
        if (board == null || board.length != GameRuleCore.CELLS) return false;
        int scatters = 0;
        for (int i = 0; i < board.length; i++) {
            int symbol = board[i];
            if (symbol == SCATTER) {
                if (i < 3 || i >= 12 || ++scatters > 1) return false;
            } else if (symbol < 1 || symbol > 10) {
                return false;
            }
        }
        return true;
    }

    static int[] validatedWeights(int[] values, String mode) {
        if (values == null || values.length != 11) {
            throw new IllegalArgumentException(mode + " symbol weights must contain IDs 1..10 and Scatter 100");
        }
        int total = 0;
        int[] copy = values.clone();
        for (int value : copy) {
            if (value < 0) throw new IllegalArgumentException(mode + " symbol weight must be >= 0");
            total = Math.addExact(total, value);
        }
        if (total <= 0) throw new IllegalArgumentException(mode + " symbol weight total must be > 0");
        return copy;
    }

    public int[] generateLossCandidate(){
        int[] board=new int[15];boolean[] first=new boolean[11];int scatter=0;
        for(int i=0;i<15;i++){
            int[] w=normalWeights.clone();
            if(i>=3&&i<6)for(int v=1;v<=10;v++)if(first[v])w[v-1]=0;
            boolean allow=i>=3&&i<12&&scatter<1;
            board[i]=nextSymbol(w,allow);
            if(i<3)first[board[i]]=true;
            if(board[i]==SCATTER)scatter++;
        }
        return board;
    }
}
