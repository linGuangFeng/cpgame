package com.cpgame.g2110.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The only production rules authority for gid 2110. */
public final class GameRuleCore {
    public static final int GAME_ID=2110,REELS=5,ROWS=3,CELLS=15,PAYLINES=20,WILD=8,SCATTER=9;
    public static final String GAME_NAME="Bee Workshop";
    public static final String ORIGINAL_BUNDLE_SHA256="a7afb966d24388b875efa8242f62260a35ad33fb489577d84f6dfa25e2766fce";
    public static final String RULES_HASH="bw2110-lines20-paytable-v2-a7afb966-deal-3d5f4e28";
    private static final int[][] VISUAL_LINES={
        {1,1,1,1,1},{2,2,2,2,2},{0,0,0,0,0},{2,1,0,1,2},{0,1,2,1,0},
        {2,2,1,2,2},{0,0,1,0,0},{1,0,0,0,1},{1,2,2,2,1},{2,1,1,1,2},
        {0,1,1,1,0},{1,1,2,1,1},{1,1,0,1,1},{1,2,1,2,1},{1,0,1,0,1},
        {2,1,2,1,2},{0,1,0,1,0},{2,2,1,0,0},{0,0,1,2,2},{1,2,1,0,1}};
    private static final int[][] PAY={{},{100,300,1000},{30,60,300},{10,30,100},{8,20,80},{6,10,60},{5,8,50},{5,8,40},{},{}};

    public List<Win> evaluate(int[] board){
        requireBoard(board);List<Win>wins=new ArrayList<>();
        for(int line=0;line<VISUAL_LINES.length;line++){
            int target=0,count=0;List<Integer>positions=new ArrayList<>();
            for(int reel=0;reel<REELS;reel++){
                int position=reel*ROWS+(ROWS-1-VISUAL_LINES[line][reel]),symbol=board[position];
                if(target==0&&symbol!=WILD)target=symbol;
                if(symbol==WILD||(target!=0&&symbol==target)){count++;positions.add(position);}else break;
            }
            if(count>=3&&target>=1&&target<=7)wins.add(new Win(line+1,count,PAY[target][count-3],target,positions));
        }
        return List.copyOf(wins);
    }
    public long payoutUnits(int[]board){return evaluate(board).stream().mapToLong(Win::odd).sum();}
    public int scatterCount(int[]board){requireBoard(board);int count=0;for(int symbol:board)if(symbol==SCATTER)count++;return count;}
    /** Original help Game2110_15; five-Scatter generation is separately sample-gated. */
    public int awardedFreeSpins(int count){return count==3?8:count==4?10:count==5?15:0;}
    public void validate(CompleteRound round){
        if(round==null||round.steps().isEmpty())throw new IllegalArgumentException("empty complete round");
        int expected=switch(round.kind()){case ORDINARY_LOSS,ORDINARY_WIN,MYSTERY_BOX->1;case FREE_STICKY_SYMBOLS->awardedFreeSpins(scatterCount(round.steps().get(0).board()))+1;};
        if(round.kind()==RoundKind.FREE_STICKY_SYMBOLS&&expected<=1)throw new IllegalArgumentException("free round has no trigger");
        if(round.steps().size()!=expected)throw new IllegalArgumentException("incomplete round");
        long units=0;LinkedHashSet<Integer>previous=new LinkedHashSet<>();
        for(int index=0;index<round.steps().size();index++){
            Step step=round.steps().get(index);requireBoard(step.board());units+=payoutUnits(step.board());
            String entry=round.kind()==RoundKind.FREE_STICKY_SYMBOLS?
                    index==0?"FREE_TRIGGER":index==1?"FREE_INITIAL":index==round.steps().size()-1?"FREE_TERMINAL":"FREE_CONTINUATION":round.kind().name();
            validateDealtStep(entry,step);
            for(int p:step.specialPositions())if(p<0||p>=CELLS)throw new IllegalArgumentException("special position");
            if((round.kind()==RoundKind.ORDINARY_LOSS||round.kind()==RoundKind.ORDINARY_WIN)&&!step.specialPositions().isEmpty())throw new IllegalArgumentException("ordinary feature positions");
            if(round.kind()==RoundKind.MYSTERY_BOX){if(step.specialPositions().isEmpty())throw new IllegalArgumentException("mystery positions");int symbol=step.board()[step.specialPositions().get(0)];for(int p:step.specialPositions())if(step.board()[p]!=symbol)throw new IllegalArgumentException("mystery reveal");}
            if(round.kind()==RoundKind.FREE_STICKY_SYMBOLS){
                if(index==0&&!step.specialPositions().isEmpty())throw new IllegalArgumentException("trigger sticky positions");
                if(index>0){int previousMask=index==1?1<<7:0;for(int p:previous)previousMask|=1<<p;validateStickyTransition(previousMask,step,index==1);previous=new LinkedHashSet<>(step.specialPositions());}
            }
        }
        if(round.kind()==RoundKind.ORDINARY_LOSS&&units!=0)throw new IllegalArgumentException("loss pays");
        if(round.kind()!=RoundKind.ORDINARY_LOSS&&units<=0)throw new IllegalArgumentException("feature/win pool must pay");
    }
    /** Conservative observed entry limits, always below or equal to documented rule limits. */
    public void validateDealtStep(String entry,Step step){
        int[] board=step.board();requireBoard(board);
        var limits=EmpiricalDealModel.shared().bounds(entry);
        var positions=new LinkedHashSet<>(step.specialPositions());
        if(positions.size()!=step.specialPositions().size())throw new IllegalArgumentException("duplicate feature position");
        for(int p:positions)if(p<0||p>=CELLS)throw new IllegalArgumentException("feature position");
        if(positions.size()<limits.specialMin()||positions.size()>limits.specialMax())throw new IllegalArgumentException("observed feature count bound");
        int wild=0,scatter=0;
        for(int reel=0;reel<REELS;reel++){
            int reelWild=0,reelScatter=0;
            for(int row=0;row<ROWS;row++){int symbol=board[reel*ROWS+row];if(symbol==WILD)reelWild++;if(symbol==SCATTER)reelScatter++;}
            if((reel==0||reel==4)&&reelWild>0)throw new IllegalArgumentException("Wild is legal only on reels 2, 3, 4 (Game2110_9)");
            if(reelWild>limits.wildPerReel()[reel]||reelScatter>limits.scatterPerReel()[reel])throw new IllegalArgumentException("observed reel symbol bound");
            wild+=reelWild;scatter+=reelScatter;
        }
        if(wild>limits.wildBoardMax()||scatter>limits.scatterBoardMax())throw new IllegalArgumentException("observed board symbol bound");
    }
    public void validateStickyTransition(int previousMask,Step step,boolean initial){
        int[] board=step.board();int target=board[7],mask=0;
        if(target<1||target>7)throw new IllegalArgumentException("sticky reveal excludes Wild and Scatter");
        for(int p:step.specialPositions())mask|=1<<p;
        if((mask&(1<<7))==0||(previousMask&~mask)!=0)throw new IllegalArgumentException("sticky state");
        for(int p=0;p<CELLS;p++)if((board[p]==target)!=((mask&(1<<p))!=0))throw new IllegalArgumentException("all matching symbols must become sticky (Game2110_18)");
        if(Integer.bitCount(mask&~previousMask)>EmpiricalDealModel.shared().newStickyLimit(initial))throw new IllegalArgumentException("observed new sticky count bound");
    }
    private void requireBoard(int[]board){if(board==null||board.length!=CELLS)throw new IllegalArgumentException("board must be 5x3");for(int symbol:board)if(symbol<1||symbol>9)throw new IllegalArgumentException("symbol");}
    public int[]linePositions(int oneBasedLine){if(oneBasedLine<1||oneBasedLine>PAYLINES)throw new IllegalArgumentException("line");int[]out=new int[REELS];for(int reel=0;reel<REELS;reel++)out[reel]=reel*ROWS+(ROWS-1-VISUAL_LINES[oneBasedLine-1][reel]);return out;}
    public int pay(int symbol,int count){return PAY[symbol][count-3];}
    public enum RoundKind{ORDINARY_LOSS,ORDINARY_WIN,MYSTERY_BOX,FREE_STICKY_SYMBOLS}
    public record Win(int line,int num,int odd,int wp,List<Integer>positions){public Win{positions=List.copyOf(positions);}}
    public record Step(int[]board,List<Integer>specialPositions){public Step{board=board.clone();specialPositions=List.copyOf(specialPositions);}@Override public int[]board(){return board.clone();}}
    public record CompleteRound(RoundKind kind,List<Step>steps){public CompleteRound{steps=List.copyOf(steps);}}
}
