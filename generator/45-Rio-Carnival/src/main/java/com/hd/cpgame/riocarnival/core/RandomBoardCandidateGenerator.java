package com.hd.cpgame.riocarnival.core;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** One dealer: payline geometry plus scatter/wild caps. Paid, free and 0-loss use the same construction. */
public final class RandomBoardCandidateGenerator {
    private final RandomSource random;
    private final Map<String,Integer> normal;
    private final Map<String,Integer> free;
    public RandomBoardCandidateGenerator(RandomSource random) {
        this(random, GameRules.DEFAULT_NORMAL_WEIGHTS, GameRules.DEFAULT_FREE_WEIGHTS);
    }
    public RandomBoardCandidateGenerator(RandomSource random,Map<String,Integer> normal,Map<String,Integer> free) {
        if(random==null)throw new IllegalArgumentException("random required");this.random=random;
        this.normal=validated(normal,"normal");this.free=validated(free,"free");
    }
    public List<String> nextBoard(boolean prohibitTrigger) { return nextBoard(prohibitTrigger,false); }
    public List<String> nextBoard(boolean prohibitTrigger,boolean freeMode) {
        return nextBoard(0, prohibitTrigger ? 2 : GameRules.MAX_SCATTER_BOARD, freeMode);
    }
    public List<String> nextBoard(int minScatter, int maxScatter, boolean freeMode) {
        Map<String,Integer> weights = freeMode ? this.free : this.normal;
        return deal(random, weights, minScatter, maxScatter, false);
    }
    public List<String> lossBoard() { return deal(random, normal, 0, 2, true); }

    static List<String> constructLoss(RandomSource random, Map<String,Integer> weights) {
        return deal(random, weights, 0, 2, true);
    }

    static List<String> deal(RandomSource random, Map<String,Integer> weights, int maxScatterBoard, boolean breakLines) {
        return deal(random, weights, 0, maxScatterBoard, breakLines);
    }

    static List<String> deal(RandomSource random, Map<String,Integer> weights, int minScatter, int maxScatterBoard, boolean breakLines) {
        List<String> pays = payingSymbols();
        if (breakLines) {
            List<String> shuffled = new ArrayList<String>(pays);
            shuffle(shuffled, random);
            List<String> first = shuffled.subList(0, Math.min(5, shuffled.size()));
            List<String> second = shuffled.subList(Math.min(5, shuffled.size()), shuffled.size());
            List<String> board = new ArrayList<String>(15);
            for (int row=0; row<3; row++) board.add(pickWeighted(first, random, weights));
            for (int row=0; row<3; row++) board.add(pickWeighted(second.isEmpty() ? first : second, random, weights));
            for (int reel=2; reel<5; reel++)
                for (int row=0; row<3; row++) board.add(pickWeighted(pays, random, weights));
            return Collections.unmodifiableList(board);
        }
        String[] cells = new String[15];
        int[] scatReel = new int[5];
        int[] wildReel = new int[5];
        int scat = 0, wild = 0;
        int place = Math.max(0, Math.min(minScatter, Math.min(maxScatterBoard, 5)));
        List<Integer> reels = new ArrayList<Integer>();
        for (int reel=0; reel<5; reel++) reels.add(reel);
        shuffleInts(reels, random);
        for (int i=0; i<place; i++) {
            int reel = reels.get(i);
            int row = random.nextInt(3);
            cells[reel * 3 + row] = GameRules.SCATTER;
            scat++; scatReel[reel]++;
        }
        for (int reel=0; reel<5; reel++) {
            for (int row=0; row<3; row++) {
                int index = reel * 3 + row;
                if (cells[index] != null) continue;
                String symbol = pickWeighted(GameRules.SYMBOLS, random, weights);
                if (GameRules.SCATTER.equals(symbol)
                        && (scat >= maxScatterBoard || scatReel[reel] >= GameRules.MAX_SCATTER_REEL)) {
                    symbol = pickWeighted(pays, random, weights);
                }
                if (GameRules.WILD.equals(symbol)
                        && (wild >= GameRules.MAX_WILD_BOARD || wildReel[reel] >= GameRules.MAX_WILD_REEL)) {
                    symbol = pickWeighted(pays, random, weights);
                }
                if (GameRules.SCATTER.equals(symbol)) { scat++; scatReel[reel]++; }
                if (GameRules.WILD.equals(symbol)) { wild++; wildReel[reel]++; }
                cells[index] = symbol;
            }
        }
        List<String> board = new ArrayList<String>(15);
        for (String symbol : cells) board.add(symbol);
        return Collections.unmodifiableList(board);
    }

    private static void shuffleInts(List<Integer> values, RandomSource random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }

    private static List<String> payingSymbols() {
        List<String> pays = new ArrayList<String>();
        for (String symbol : GameRules.SYMBOLS)
            if (!GameRules.WILD.equals(symbol) && !GameRules.SCATTER.equals(symbol)) pays.add(symbol);
        return pays;
    }

    private static void shuffle(List<String> values, RandomSource random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }

    private static String pickWeighted(List<String> symbols, RandomSource random, Map<String,Integer> weights){
        int total=0;
        for(String symbol:symbols)total=Math.addExact(total,Math.max(1,weights.getOrDefault(symbol,1)));
        int draw=random.nextInt(total);
        for(String symbol:symbols){
            draw-=Math.max(1,weights.getOrDefault(symbol,1));
            if(draw<0)return symbol;
        }
        return symbols.get(symbols.size()-1);
    }
    private static Map<String,Integer> validated(Map<String,Integer> source,String mode){
        if(source==null||!source.keySet().equals(new LinkedHashSet<String>(GameRules.SYMBOLS)))
            throw new IllegalArgumentException(mode+" weights must contain every symbol");
        for(int value:source.values())if(value<=0)throw new IllegalArgumentException(mode+" weights must be positive");
        return Collections.unmodifiableMap(new LinkedHashMap<String,Integer>(source));
    }
}
