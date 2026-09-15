package com.cpgame.fiesta;

import java.util.*;

/** 当前游戏唯一规则核心；服务端通过此 Maven artifact 复用，不另建判奖规则。 */
public final class GameRuleCore {
    public static final int GAME_ID=2410, ROWS=3, COLUMNS=3, LINE_COUNT=5;
    public static final String RULES_VERSION="2410-rules-v1";
    public static final String RULES_HASH="0c99b115e7f083bea9aea3d198ae64bb6b3f1685c31fccdaf84aa159cf1726ac";
    public static final int[] SYMBOLS={2,3,5,10,20,50};
    private static final int[][] PAYLINES={{1,4,7},{2,5,8},{0,3,6},{0,4,8},{2,4,6}};
    private static final Set<Integer> SYMBOL_SET=Set.of(2,3,5,10,20,50);

    public record Win(int line, int symbol, int count, int odds) {}
    public List<Win> evaluateBoard(int[] board){
        validateBoard(board); List<Win> result=new ArrayList<>();
        for(int i=0;i<PAYLINES.length;i++){ int[] p=PAYLINES[i]; int s=board[p[0]]; if(s==board[p[1]] && s==board[p[2]]) result.add(new Win(i+1,s,3,s)); }
        return List.copyOf(result);
    }
    public int payoutUnits(int[] board){ return evaluateBoard(board).stream().mapToInt(Win::odds).sum(); }
    public void validateBoard(int[] board){
        if(board==null || board.length!=9) throw new IllegalArgumentException("board must contain 9 positions");
        int[] total=new int[51]; int[][] reel=new int[3][51];
        for(int i=0;i<board.length;i++){ int s=board[i]; if(!SYMBOL_SET.contains(s)) throw new IllegalArgumentException("unknown symbol "+s); total[s]++; reel[i/3][s]++; }
        for(int s:SYMBOLS){ if(total[s]>9) throw new IllegalArgumentException("symbol total cap"); for(int r=0;r<3;r++) if(reel[r][s]>3) throw new IllegalArgumentException("symbol reel cap"); }
    }
    public void validateRound(GameRound round){
        List<RoundState> states=round.states(); if(states.size()>11) throw new IllegalArgumentException("continuation cap");
        RoundState.Mode mode=states.get(0).mode();
        for(int i=0;i<states.size();i++){
            RoundState s=states.get(i); validateBoard(s.board());
            if(s.mode()!=mode) throw new IllegalArgumentException("mode switch");
            if(s.remaining() != (i==states.size()-1?0:1)) throw new IllegalArgumentException("bad boundary");
            if(mode==RoundState.Mode.RESPIN_UNTIL_WIN && i>0){ int c=s.respinColumn(); int[] a=states.get(i-1).board(), b=s.board(); for(int p=0;p<9;p++) if(p/3!=c && a[p]!=b[p]) throw new IllegalArgumentException("respin changed retained cell"); }
            if(mode==RoundState.Mode.MULTIPLIER_STICKY){ int[] m=s.multipliers(); if(m.length!=9) throw new IllegalArgumentException("multiplier vector"); for(int v:m) if(v!=0&&v!=2&&v!=3&&v!=5&&v!=10) throw new IllegalArgumentException("multiplier material"); if(i>0){int[] prev=states.get(i-1).multipliers();for(int p=0;p<9;p++)if(prev[p]!=0&&prev[p]!=m[p])throw new IllegalArgumentException("sticky regression");} }
        }
        if(states.get(states.size()-1).remaining()!=0) throw new IllegalArgumentException("unterminated round");
    }
}

