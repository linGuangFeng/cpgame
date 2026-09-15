package com.cpgame.hiddenrealm.core;

import java.security.SecureRandom;

/**
 * Empirical weights from 695 true paid origin rounds (type=1, type_skill=0).
 * Last 100 true paid rounds held out. Source: reports/1380-Hidden-Realm/dealing-model.json
 *
 * paid_initial 17375 cells: 1=2120 2=2073 3=2150 4=2077 5=2102 6=2089 7=2180 8=2204 9=380
 * cascade_refill 2260: 1=284 2=252 3=265 4=224 5=264 6=282 7=277 8=308 9=104
 * feature_refill 7280: 1=791 2=900 3=886 4=921 5=865 6=915 7=878 8=931 9=193
 * lord_transform 1932: 5=486 6=465 7=480 8=501 (wild never observed)
 * fire_symbol 72: 1=6 2=8 3=3 4=11 5=7 6=9 7=11 8=17
 */
public final class DealingModel {
    static final class RejectedDeal extends RuntimeException {
        RejectedDeal(String message) { super(message); }
    }

    public static final int WILD_INIT_COL = 3;
    public static final int WILD_INIT_PAGE = 5;
    public static final int WILD_COL = 3;
    public static final int WILD_PAGE = 6;

    private static final int[] INITIAL = {0, 2120, 2073, 2150, 2077, 2102, 2089, 2180, 2204, 380};
    private static final int[] CASCADE = {0, 284, 252, 265, 224, 264, 282, 277, 308, 104};
    private static final int[] FEATURE = {0, 791, 900, 886, 921, 865, 915, 878, 931, 193};
    private static final int[] LORD = {0, 0, 0, 0, 0, 486, 465, 480, 501, 0};
    private static final int[] FIRE = {0, 6, 8, 3, 11, 7, 9, 11, 17, 0};
    /** Rare-special upweight only: same family as paid_initial, wilds doubled, still under observed caps. */
    private static final int[] SPECIAL_INITIAL = {0, 1900, 1900, 1900, 1900, 2300, 2300, 2400, 2500, 760};

    int pickFire(SecureRandom random) { return pick(FIRE, random); }
    int pickLord(SecureRandom random) { return pick(LORD, random); }

    int[][] initialBoard(SecureRandom random, boolean specialEntry) {
        int[][] board = new int[5][5];
        int[] weights = specialEntry ? SPECIAL_INITIAL : INITIAL;
        for (int attempt = 0; attempt < 20000; attempt++) {
            for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++) board[c][r] = pick(weights, random);
            if (legalInitial(board)) return board;
        }
        throw new RejectedDeal("initial cap");
    }

    void fillEmpty(int[][] board, SecureRandom random, boolean feature) {
        int[] weights = feature ? FEATURE : CASCADE;
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++)
            if (board[c][r] == 0) board[c][r] = pick(weights, random);
        if (!legalPage(board)) throw new RejectedDeal("refill cap");
    }

    boolean legalInitial(int[][] board) {
        int wilds = 0;
        for (int c = 0; c < 5; c++) {
            int col = 0;
            for (int r = 0; r < 5; r++) if (board[c][r] == 9) { col++; wilds++; }
            if (col > WILD_INIT_COL) return false;
        }
        return wilds <= WILD_INIT_PAGE;
    }

    boolean legalPage(int[][] board) {
        int wilds = 0;
        for (int c = 0; c < 5; c++) {
            int col = 0;
            for (int r = 0; r < 5; r++) if (board[c][r] == 9) { col++; wilds++; }
            if (col > WILD_COL) return false;
        }
        return wilds <= WILD_PAGE;
    }

    private int pick(int[] weights, SecureRandom random) {
        int total = 0;
        for (int i = 1; i < weights.length; i++) total += weights[i];
        int ticket = random.nextInt(total);
        for (int i = 1; i < weights.length; i++) {
            ticket -= weights[i];
            if (ticket < 0) return i;
        }
        return weights.length - 1;
    }

    int[][] lossBoard(SecureRandom random){
        int[][] b=new int[5][5];
        for(int c=0;c<5;c++)for(int r=0;r<5;r++){
            int[] allowed=INITIAL.clone();allowed[9]=0;
            if(c>0)allowed[b[c-1][r]]=0;
            if(r>0)allowed[b[c][r-1]]=0;
            b[c][r]=pick(allowed,random);
        }
        return b;
    }
}
