package com.cpgame.fiesta;

import java.util.*;

/** Shared rules, using provider column-major / top-to-bottom positions. */
public final class GameRuleCore {
    public static final int GAME_ID=2410, ROWS=3, COLUMNS=3, LINE_COUNT=5;
    public static final String RULES_VERSION="2410-rules-v2";
    public static final String RULES_HASH="2ca7600deee92c264e979c2438b0173f273563e0c264f0e58a16067714cd1913";
    public static final int[] SYMBOLS={2,3,5,10,20,50};
    // RecordDetailArea and SymbolArea agree after converting reel visual row indices.
    private static final int[][] PAYLINES={{1,4,7},{0,3,6},{2,5,8},{0,4,8},{2,4,6}};
    private static final Set<Integer> SYMBOL_SET=Set.of(2,3,5,10,20,50);
    public record Win(int line,int symbol,int count,int odds) {}
    public List<Win> evaluateBoard(int[] board){
        validateBoard(board);List<Win> wins=new ArrayList<>();
        for(int i=0;i<PAYLINES.length;i++){int[] p=PAYLINES[i];int s=board[p[0]];if(s==board[p[1]]&&s==board[p[2]])wins.add(new Win(i+1,s,3,s));}
        return List.copyOf(wins);
    }
    public int payoutUnits(int[] board){return evaluateBoard(board).stream().mapToInt(Win::odds).sum();}
    public void validateBoard(int[] board){
        if(board==null||board.length!=9)throw new IllegalArgumentException("board must contain 9 positions");
        for(int s:board)if(!SYMBOL_SET.contains(s))throw new IllegalArgumentException("unknown symbol "+s);
    }
    public static boolean fullScreen(int[] b){for(int i=1;i<9;i++)if(b[i]!=b[0])return false;return true;}
    /** Returns the moving reel only for a non-winning two-full-reel trigger. */
    public int respinColumn(int[] b){
        if(payoutUnits(b)!=0)return -1;
        for(int c=0;c<3;c++){int a=(c+1)%3,z=(c+2)%3,target=b[a*3];boolean full=true;
            for(int r=0;r<3;r++)full&=b[a*3+r]==target&&b[z*3+r]==target;
            if(full)return c;
        }return -1;
    }
    public void validateRound(GameRound round){
        List<RoundState> states=round.states();
        if(states.size()>19)throw new IllegalArgumentException("observed complete round cap 19");
        int respins=0,multiplierSteps=0;
        for(int i=0;i<states.size();i++){
            RoundState s=states.get(i);int[] b=s.board(),m=s.multipliers();validateBoard(b);
            boolean terminal=i==states.size()-1;
            if(s.remaining()!=(terminal?0:1))throw new IllegalArgumentException("bad boundary");
            if(m.length!=9)throw new IllegalArgumentException("multiplier vector");
            for(int v:m)if(v!=0&&v!=2&&v!=3&&v!=5&&v!=10)throw new IllegalArgumentException("multiplier material");
            RoundState prev=i==0?null:states.get(i-1);
            if(prev!=null&&prev.mode()==RoundState.Mode.RESPIN_UNTIL_WIN){
                int col=prev.respinColumn();int[] before=prev.board();
                for(int p=0;p<9;p++)if(p/3!=col&&b[p]!=before[p])throw new IllegalArgumentException("changed retained reel");
                if(s.mode()==RoundState.Mode.NORMAL)throw new IllegalArgumentException("respin result must keep feature metadata");
                if(s.mode()==RoundState.Mode.MULTIPLIER_STICKY&&!fullScreen(b))throw new IllegalArgumentException("respin to multiplier requires full screen");
            }
            if(s.mode()==RoundState.Mode.NORMAL){
                if(states.size()!=1||fullScreen(b)||respinColumn(b)>=0)throw new IllegalArgumentException("normal board triggers feature");
            }else if(s.mode()==RoundState.Mode.RESPIN_UNTIL_WIN){
                respins++;int col=s.respinColumn();
                if(col<0||col>2||fullScreen(b))throw new IllegalArgumentException("invalid respin reel/full screen transition");
                int target=b[((col+1)%3)*3];
                for(int p=0;p<9;p++)if(p/3!=col&&b[p]!=target)throw new IllegalArgumentException("invalid locked reels");
                if(s.baseSymbol()!=target)throw new IllegalArgumentException("respin base symbol");
                if(terminal?payoutUnits(b)<=0:payoutUnits(b)!=0)throw new IllegalArgumentException("respin must end at first win");
                if(prev!=null&&(prev.mode()!=s.mode()||prev.respinColumn()!=col))throw new IllegalArgumentException("invalid respin transition");
            }else{
                multiplierSteps++;
                if(!fullScreen(b)||s.baseSymbol()!=b[0])throw new IllegalArgumentException("multiplier requires full screen");
                boolean entry=prev==null||prev.mode()!=RoundState.Mode.MULTIPLIER_STICKY;
                if(entry){if(terminal||Arrays.stream(m).anyMatch(v->v!=0)||s.addedPositions().length!=0)throw new IllegalArgumentException("multiplier entry");}
                else{
                    if(!Arrays.equals(prev.board(),b))throw new IllegalArgumentException("multiplier board changed");
                    int[] old=prev.multipliers();List<Integer> added=new ArrayList<>();
                    for(int p=0;p<9;p++){if(old[p]!=0&&old[p]!=m[p])throw new IllegalArgumentException("sticky regression");if(old[p]==0&&m[p]!=0)added.add(p);}
                    if(!Arrays.equals(added.stream().mapToInt(Integer::intValue).toArray(),s.addedPositions()))throw new IllegalArgumentException("added positions mismatch");
                    if(added.size()>1)throw new IllegalArgumentException("observed per-step addition cap 1");
                    int sum=Arrays.stream(m).sum();
                    if(terminal){if(sum<=0||(!added.isEmpty()&&payoutUnits(b)*sum<22500))throw new IllegalArgumentException("multiplier must end on no addition or maximum win");}
                    else if(added.isEmpty())throw new IllegalArgumentException("empty addition must terminate");
                }
            }
            if(s.mode()!=RoundState.Mode.MULTIPLIER_STICKY&&(Arrays.stream(m).anyMatch(v->v!=0)||s.addedPositions().length!=0))throw new IllegalArgumentException("unexpected multiplier material");
        }
        if(respins>11||multiplierSteps>11)throw new IllegalArgumentException("observed stage cap");
    }
}
