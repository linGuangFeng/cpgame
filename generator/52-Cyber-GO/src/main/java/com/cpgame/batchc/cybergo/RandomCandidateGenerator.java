package com.cpgame.batchc.cybergo;
import java.util.*;
import java.util.random.RandomGenerator;
/** Joint three-cell reel transitions conditioned on observed left-to-right state. */
public final class RandomCandidateGenerator {
 private static final List<String> SYMBOLS=List.of("S1","S2","S3","S4","A","K","Q","J","WILD","SC");
 private record Window(String symbols,int count) { }
 private static final Map<String,List<Window>> STATES=new HashMap<>();
 static {
  for(String line:EmpiricalReelModel.STATES) {
   String[] parts=line.split("=",2);
   STATES.put(parts[0],Arrays.stream(parts[1].split(" ")).map(item->{String[] p=item.split(":");return new Window(p[0],Integer.parseInt(p[1]));}).toList());
  }
 }
 private final RandomGenerator random;
 public RandomCandidateGenerator(RandomGenerator random){this.random=Objects.requireNonNull(random);}
 public RandomCandidateGenerator(RandomGenerator random,SymbolWeights ignored){this(random);}
 public List<String> paidBoardCandidate(){return board(0);}
 public List<String> freeBoardCandidate(){return board(1);}
 private List<String> board(int entry){
  List<String> board=new ArrayList<>(15);int prefix=255,sc=0,wild=0,qualified=0;
  for(int reel=0;reel<5;reel++){
   String key=entry+","+reel+","+prefix+","+sc+","+wild+","+qualified;
   List<Window> windows=STATES.get(key);
   if(windows==null)throw new IllegalStateException("Unobserved generative state: "+key);
   int draw=random.nextInt(windows.stream().mapToInt(Window::count).sum());Window picked=null;
   for(Window w:windows){draw-=w.count();if(draw<0){picked=w;break;}}
   if(picked==null)throw new IllegalStateException("Empty transition distribution");
   int mask=0;
   for(char c:picked.symbols().toCharArray()){
    int symbol=c-'0';board.add(SYMBOLS.get(symbol));
    if(symbol==9)sc++;else if(symbol==8){wild++;mask=255;}else mask|=1<<symbol;
   }
   prefix&=mask;if(reel==2&&prefix!=0)qualified=1;
  }
  return List.copyOf(board);
 }

 // Loss-conditioned paths through the existing correlated-reel state graph.
 // Computed once; a request draws exactly five reel windows without rejection.
 private static final Map<String,List<Window>> LOSS_STATES=lossStates();
 private static Map<String,List<Window>> lossStates(){
  Map<String,List<Window>> out=new HashMap<>();
  eligible("0,0,255,0,0,0",out);
  return Map.copyOf(out);
 }
 private static boolean eligible(String key,Map<String,List<Window>> out){
  int[] state=Arrays.stream(key.split(",")).mapToInt(Integer::parseInt).toArray();
  if(state[3]>=3||state[5]!=0)return false;
  if(state[1]==5)return true;
  if(out.containsKey(key))return !out.get(key).isEmpty();
  List<Window> safe=new ArrayList<>();
  for(Window w:STATES.getOrDefault(key,List.of()))if(eligible(nextState(state,w),out))safe.add(w);
  out.put(key,List.copyOf(safe));return !safe.isEmpty();
 }
 private static String nextState(int[] s,Window w){
  int mask=0,sc=s[3],wild=s[4];
  for(char c:w.symbols().toCharArray()){
   int symbol=c-'0';if(symbol==9)sc++;else if(symbol==8){wild++;mask=255;}else mask|=1<<symbol;
  }
  int prefix=s[2]&mask,qualified=s[5];if(s[1]==2&&prefix!=0)qualified=1;
  return "0,"+(s[1]+1)+","+prefix+","+sc+","+wild+","+qualified;
 }
 public List<String> independentLossCandidate(){
  String key="0,0,255,0,0,0";List<String> board=new ArrayList<>(15);
  for(int reel=0;reel<5;reel++){
   List<Window> windows=LOSS_STATES.get(key);
   if(windows==null||windows.isEmpty())throw new IllegalStateException("loss-conditioned model empty");
   int ticket=random.nextInt(windows.stream().mapToInt(Window::count).sum());Window picked=windows.get(0);
   for(Window w:windows){ticket-=w.count();if(ticket<0){picked=w;break;}}
   for(char c:picked.symbols().toCharArray())board.add(SYMBOLS.get(c-'0'));
   key=nextState(Arrays.stream(key.split(",")).mapToInt(Integer::parseInt).toArray(),picked);
  }
  return List.copyOf(board);
 }
}
