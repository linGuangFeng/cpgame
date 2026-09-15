package com.cpgame.sharpshooter.core;
import java.util.*;

/** Shared executable rules and observable state transitions for Sharpshooter. */
public final class GameRuleCore {
 public static final int GAME_ID=1090,COLUMNS=5,ROWS=4,CELL_COUNT=20,SCATTER=9,WILD=10;
 public static final String RULES_VERSION="1090-rules-v2";
 public static final String RULES_HASH="e2d48bc36060cfa82c86468e67d20617b0f137bae5ed9986541823c558c7daff";
 public static final int[] NORMAL_MULTIPLIERS={1,2,3,5},FREE_MULTIPLIERS={2,4,6,10};
 private static final int[][] PAY={{},{0,0,0,2,5,10},{0,0,0,2,5,10},{0,0,0,4,10,20},{0,0,0,4,10,20},{0,0,0,6,15,40},{0,0,0,8,20,60},{0,0,0,10,40,80},{0,0,0,15,60,100}};
 public record Win(int symbol,int reels,int ways,int odds,boolean[] positions){
  public Win{positions=positions.clone();}
  @Override public boolean[] positions(){return positions.clone();}
  public int payoutUnits(){return Math.multiplyExact(odds,ways);}
 }
 public List<Win> evaluate(int[] board){
  validateBoard(board);List<Win> out=new ArrayList<>();
  for(int symbol=1;symbol<=8;symbol++){
   int ways=1,reels=0;boolean[] positions=new boolean[20];
   for(int col=0;col<5;col++){
    int count=0;
    for(int row=0;row<4;row++){int p=col*4+row;if(board[p]==symbol||board[p]==WILD){count++;positions[p]=true;}}
    if(count==0)break;ways=Math.multiplyExact(ways,count);reels++;
   }
   if(reels>=3)out.add(new Win(symbol,reels,ways,PAY[symbol][reels],positions));
  }
  return List.copyOf(out);
 }
 public boolean[] winningPositions(int[] board){
  boolean[] mask=new boolean[20];for(Win win:evaluate(board)){boolean[] p=win.positions();for(int i=0;i<20;i++)mask[i]|=p[i];}return mask;
 }
 public int payoutUnits(int[] board,int cascadeIndex,boolean free){
  return Math.multiplyExact(evaluate(board).stream().mapToInt(Win::payoutUnits).sum(),multiplier(cascadeIndex,free));
 }
 public int multiplier(int index,boolean free){
  if(index<0)throw new IllegalArgumentException("negative cascade index");
  int[] values=free?FREE_MULTIPLIERS:NORMAL_MULTIPLIERS;return values[Math.min(index,values.length-1)];
 }
 public int scatterCount(int[] board){validateBoard(board);int n=0;for(int s:board)if(s==SCATTER)n++;return n;}
 public int awardedFreeSpins(int count){
  if(count<0||count>4)throw new IllegalArgumentException("unobserved scatter count");
  return count<3?0:count==3?12:14;
 }
 public void validateBoard(int[] board){
  if(board==null||board.length!=20)throw new IllegalArgumentException("board must be 5x4");
  for(int symbol:board)if(symbol<1||symbol>10)throw new IllegalArgumentException("unknown symbol "+symbol);
 }
 public void validatePage(CompleteRound.Cascade page,boolean free,int index){
  int[] b=page.symbols();boolean[] gold=page.gold();validateBoard(b);
  if(gold.length!=20)throw new IllegalArgumentException("gold mask");
  int scatter=0,wild=0,totalGold=0;
  for(int col=0;col<5;col++){
   int sc=0,gc=0;
   for(int row=0;row<4;row++){
    int p=col*4+row;
    if(b[p]==9)sc++;if(b[p]==10)wild++;
    if(gold[p]){gc++;if(col==0||col==4||b[p]>8)throw new IllegalArgumentException("illegal gold symbol or column");}
   }
   if(sc>1||gc>(free&&col==3?2:3))throw new IllegalArgumentException("column cap");
   scatter+=sc;totalGold+=gc;
  }
  if(scatter>(free?2:4)||wild>(index==0?0:free?3:4)||totalGold>(index==0?(free?5:6):(free?4:5)))
   throw new IllegalArgumentException("whole-page cap");
 }
 /** Source position for each retained cell; -1 denotes a newly dealt suffix cell. */
 public int[] predecessorPositions(CompleteRound.Cascade previous,CompleteRound.Cascade next){
  int[] old=previous.symbols(),now=next.symbols();boolean[] gold=previous.gold(),nextGold=next.gold(),win=winningPositions(old);
  validateBoard(now);int[] source=new int[20];Arrays.fill(source,-1);
  for(int col=0;col<5;col++){
   int kept=0;
   for(int row=0;row<4;row++){
    int from=col*4+row;
    if(!win[from]||gold[from]){
     int to=col*4+kept++;int expected=win[from]?WILD:old[from];boolean expectedGold=!win[from]&&gold[from];
     if(now[to]!=expected||nextGold[to]!=expectedGold)throw new IllegalArgumentException("retained symbol/order changed");
     source[to]=from;
    }
   }
   for(int row=kept;row<4;row++){int p=col*4+row;if(now[p]>8||nextGold[p])throw new IllegalArgumentException("illegal fresh refill symbol/material");}
  }
  return source;
 }
 public void validateRound(CompleteRound round){
  List<CompleteRound.Spin> spins=round.spins();CompleteRound.Spin first=spins.get(0);
  if(!first.paid())throw new IllegalArgumentException("first spin must be paid");
  int total=first.freeTotal(),awarded=awardedFreeSpins(scatterCount(first.cascades().get(0).symbols()));
  if(total!=awarded||first.newFree()!=awarded||spins.size()!=total+1)throw new IllegalArgumentException("free award or full-round boundary");
  for(int i=0;i<spins.size();i++){
   CompleteRound.Spin spin=spins.get(i);boolean free=i>0;
   if(spin.paid()!=!free||spin.freeTotal()!=total||spin.freeRemaining()!=(total==0?0:total-i)||(free&&spin.newFree()!=0))
    throw new IllegalArgumentException("paid/free counter continuity");
   List<CompleteRound.Cascade> pages=spin.cascades();
   if(pages.size()>(free?10:11))throw new IllegalArgumentException("observed cascade cap");
   for(int c=0;c<pages.size();c++){
    CompleteRound.Cascade page=pages.get(c);validatePage(page,free,c);
    if(evaluate(page.symbols()).isEmpty()!=(c==pages.size()-1))throw new IllegalArgumentException("cascade termination");
    if(c>0)predecessorPositions(pages.get(c-1),page);
   }
  }
 }
}
