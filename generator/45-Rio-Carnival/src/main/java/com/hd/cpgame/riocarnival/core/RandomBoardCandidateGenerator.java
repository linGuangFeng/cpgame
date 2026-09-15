package com.hd.cpgame.riocarnival.core;
import java.util.List;
import java.util.Map;
/** Draws correlated reels from aggregated mode-specific training statistics. */
public final class RandomBoardCandidateGenerator {
    private final RandomSource random;
    public RandomBoardCandidateGenerator(RandomSource random) {
        if(random==null)throw new IllegalArgumentException("random required");this.random=random;
    }
    public RandomBoardCandidateGenerator(RandomSource random,Map<String,Integer> normal,Map<String,Integer> free) {
        this(random);
        if(!GameRules.DEFAULT_NORMAL_WEIGHTS.equals(normal)||!GameRules.DEFAULT_FREE_WEIGHTS.equals(free))
            throw new IllegalArgumentException("Per-cell weight overrides are not supported by the dealing model");
    }
    public List<String> nextBoard(boolean prohibitTrigger) { return nextBoard(prohibitTrigger,false); }
    public List<String> nextBoard(boolean prohibitTrigger,boolean free) {
        for(int i=0;i<10000;i++) {
            List<String> board=DealingModel.board(free,random);
            if(!prohibitTrigger||ResultUtil.scatterCount(board)<3)return board;
        }
        throw new IllegalStateException("No eligible board candidate");
    }
}
