package com.cpgame.fishinggo.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Empirical counts from 1241 training complete origin rounds (last 100 held out).
 * paid 18615: A=1945 J=1977 K=2008 Q=1996 S1=2462 S2=2335 S3=2098 S4=2160 SC=1119 WILD=515
 * free 7380: A=822 J=824 K=898 Q=877 S1=856 S2=859 S3=865 S4=862 SC=392 WILD=125
 * entry SC 5/6/7 = 25/12/4
 */
public final class DealingModel {
    static final class Rejected extends RuntimeException { Rejected(String m) { super(m); } }

    private static final int[] PAID = {1945, 1977, 2008, 1996, 2462, 2335, 2098, 2160, 1119, 515};
    private static final int[] FREE = {822, 824, 898, 877, 856, 859, 865, 862, 392, 125};
    private static final int[] ENTRY_FILL = {47, 45, 50, 43, 46, 49, 52, 52, 0, 6};
    private static final int[] ENTRY_SC = {0, 0, 0, 0, 0, 25, 12, 4};

    String pickPaid(SecureRandom r, int reel) { return pick(PAID, r, reel, true, true); }
    String pickFree(SecureRandom r, int reel) { return pick(FREE, r, reel, true, true); }

    List<String> paidBoard(SecureRandom random) {
        return constrained(random, true, ProtocolConstants.MAX_SCATTER_PAID,
                ProtocolConstants.MAX_SCATTER_PER_REEL, ProtocolConstants.MAX_WILD_PAID, false);
    }

    List<String> freeBoard(SecureRandom random) {
        return constrained(random, true, ProtocolConstants.MAX_SCATTER_FREE_BOARD,
                ProtocolConstants.MAX_SCATTER_FREE_REEL, ProtocolConstants.MAX_WILD_FREE, true);
    }

    List<String> scatterEntry(SecureRandom random, int sc) {
        if (sc < 5 || sc > 7) throw new IllegalArgumentException("entry sc");
        List<String> board = new ArrayList<>(Collections.nCopies(ProtocolConstants.CELLS, ""));
        List<Integer> reels = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        for (int reel = 0; reel < 5; reel++)
            board.set(reel * 3 + random.nextInt(3), "SC");
        Collections.shuffle(reels, random);
        for (int extra = 0; extra < sc - 5; extra++) {
            int reel = reels.get(extra);
            List<Integer> empty = new ArrayList<>();
            for (int row = 0; row < 3; row++) if (board.get(reel * 3 + row).isEmpty()) empty.add(row);
            board.set(reel * 3 + empty.get(random.nextInt(empty.size())), "SC");
        }
        int wilds = 0;
        int[] wildCol = new int[5];
        for (int i = 0; i < board.size(); i++) {
            if (!board.get(i).isEmpty()) continue;
            int reel = i / 3;
            boolean wildOk = wilds < ProtocolConstants.MAX_WILD_PAID && wildCol[reel] < 2 && reel != 0 && reel != 4;
            String s = pick(ENTRY_FILL, random, reel, false, wildOk);
            board.set(i, s);
            if (s.equals("WILD")) { wilds++; wildCol[reel]++; }
        }
        return List.copyOf(board);
    }

    int pickEntryScatter(SecureRandom random) {
        int total = 0;
        for (int i = 5; i <= 7; i++) total += ENTRY_SC[i];
        int t = random.nextInt(total);
        for (int i = 5; i <= 7; i++) { t -= ENTRY_SC[i]; if (t < 0) return i; }
        return 5;
    }

    private List<String> constrained(SecureRandom random, boolean allowSc, int scBoard, int scReel, int wildBoard, boolean free) {
        List<String> board = new ArrayList<>(15);
        int scTotal = 0, wildTotal = 0;
        for (int reel = 0; reel < 5; reel++) {
            int scOn = 0, wildOn = 0;
            for (int row = 0; row < 3; row++) {
                boolean scOk = allowSc && scTotal < scBoard && scOn < scReel;
                boolean wildOk = wildTotal < wildBoard && wildOn < 2 && reel != 0 && reel != 4;
                String s = pick(free ? FREE : PAID, random, reel, scOk, wildOk);
                board.add(s);
                if (s.equals("SC")) { scTotal++; scOn++; }
                if (s.equals("WILD")) { wildTotal++; wildOn++; }
            }
        }
        return List.copyOf(board);
    }

    private String pick(int[] weights, SecureRandom random, int reel, boolean scOk, boolean wildOk) {
        int total = 0;
        for (int i = 0; i < ProtocolConstants.ORDER.size(); i++) {
            String s = ProtocolConstants.ORDER.get(i);
            if (s.equals("SC") && !scOk) continue;
            if (s.equals("WILD") && !wildOk) continue;
            total += weights[i];
        }
        if (total <= 0) throw new Rejected("no symbol");
        int t = random.nextInt(total);
        for (int i = 0; i < ProtocolConstants.ORDER.size(); i++) {
            String s = ProtocolConstants.ORDER.get(i);
            if (s.equals("SC") && !scOk) continue;
            if (s.equals("WILD") && !wildOk) continue;
            t -= weights[i];
            if (t < 0) return s;
        }
        return "A";
    }

    List<String> lossBoard(SecureRandom random){
        List<String> b=new ArrayList<>(15);java.util.Set<String> first=new java.util.HashSet<>();int sc=0,wild=0;
        for(int c=0;c<5;c++){
            int cs=0,cw=0;
            for(int r=0;r<3;r++){
                int[] weights=PAID.clone();
                if(c==1)for(int i=0;i<ProtocolConstants.ORDER.size();i++)if(first.contains(ProtocolConstants.ORDER.get(i)))weights[i]=0;
                String symbol=pick(weights,random,c,sc<4&&cs<ProtocolConstants.MAX_SCATTER_PER_REEL,
                        c>=2&&wild<ProtocolConstants.MAX_WILD_PAID&&cw<2&&c!=4);
                b.add(symbol);if(c==0)first.add(symbol);
                if(symbol.equals("SC")){sc++;cs++;}if(symbol.equals("WILD")){wild++;cw++;}
            }
        }
        return List.copyOf(b);
    }
}
