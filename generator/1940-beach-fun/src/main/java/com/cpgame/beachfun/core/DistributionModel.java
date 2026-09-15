package com.cpgame.beachfun.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Weighted joint feature layouts and empirical column/refill vectors, without stored Round outcomes. */
public final class DistributionModel {
    public static final class RejectedCandidate extends RuntimeException {public RejectedCandidate(String s){super(s);}}
    private record Weighted(String[] values,long[] cumulative,long total) {
        String draw(SecureRandom random){long n=random.nextLong(total);int i=Arrays.binarySearch(cumulative,n+1);return values[i<0?-i-1:i];}
    }
    private record Limit(int scatter,int wild,int[]scatterByReel,int[]wildByReel,long scatterPositions,long wildPositions){}
    private final Map<String,Weighted> groups=new HashMap<>();
    private final Map<String,Limit> limits=new HashMap<>();
    private final Map<String,Integer> caps=new HashMap<>();
    private String sourceHash;private long conditionedColumnRetries;
    public DistributionModel(){
        String file=System.getProperty("beachfun.model");
        try(InputStream in=file==null?DistributionModel.class.getResourceAsStream("/beachfun-distribution.tsv"):Files.newInputStream(Path.of(file))){
            if(in==null)throw new IllegalStateException("distribution model missing; no fixture/random fallback");
            load(in);
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private void load(InputStream in)throws IOException{
        Map<String,List<String>> values=new HashMap<>();Map<String,List<Long>> counts=new HashMap<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.US_ASCII))){
            String line;boolean header=false;
            while((line=reader.readLine())!=null){
                String[] p=line.split("\t");
                switch(p[0]){
                    case "BFMODEL2" -> header=true;
                    case "RULES" -> {if(!GameRuleCore.RULES_HASH.equals(p[1]))throw new IllegalArgumentException("model rulesHash mismatch");}
                    case "SOURCE" -> sourceHash=p[1];
                    case "CAP" -> caps.put(p[1],Integer.parseInt(p[2]));
                    case "LIMIT" -> limits.put(p[1],new Limit(Integer.parseInt(p[2]),Integer.parseInt(p[3]),ints(p[4]),ints(p[5]),Long.parseLong(p[6],16),Long.parseLong(p[7],16)));
                    case "D" -> {values.computeIfAbsent(p[1],k->new ArrayList<>()).add(p[2]);counts.computeIfAbsent(p[1],k->new ArrayList<>()).add(Long.parseLong(p[3]));}
                    case "TRAIN","HOLDOUT" -> {}
                    default -> throw new IllegalArgumentException("unknown model row");
                }
            }
            if(!header||sourceHash==null)throw new IllegalArgumentException("incomplete model");
        }
        for(String key:values.keySet()){
            long total=0;long[] cumulative=new long[counts.get(key).size()];
            for(int i=0;i<cumulative.length;i++){long n=counts.get(key).get(i);if(n<=0)throw new IllegalArgumentException("model weight");cumulative[i]=total=Math.addExact(total,n);}
            groups.put(key,new Weighted(values.get(key).toArray(String[]::new),cumulative,total));
        }
        for(String mode:List.of("P","F"))if(!groups.containsKey(mode+":L"))throw new IllegalArgumentException("missing initial model "+mode);
    }
    private static int[]ints(String s){return Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray();}
    private String draw(String key,SecureRandom r){Weighted g=groups.get(key);if(g==null)throw new RejectedCandidate("unobserved entry "+key);return g.draw(r);}
    public GameRuleCore.Cascade initial(boolean free,SecureRandom r,GameRuleCore rules){
        String mode=free?"F":"P";String[] layout=draw(mode+":L",r).split("\\.");
        long scat=Long.parseLong(layout[0],16),goldMask=Long.parseLong(layout[1],16);
        for(int attempt=0;attempt<10000;attempt++){try{
        int[]board=new int[20];boolean[]gold=new boolean[20];int prefix=255;
        for(int reel=0;reel<5;reel++){
            int s=(int)((scat>>(reel*4))&15),g=(int)((goldMask>>(reel*4))&15);
            String vector=draw(mode+":M:"+reel+":"+s+":"+prefix,r);
            if(vector.length()!=4)throw new IllegalStateException("initial vector length");
            int columnMask=0;
            for(int row=0;row<4;row++){int p=reel*4+row;board[p]=Character.digit(vector.charAt(row),36);gold[p]=(g&(1<<row))!=0;if(board[p]<9)columnMask|=1<<(board[p]-1);}
            prefix&=columnMask;
        }
        var c=new GameRuleCore.Cascade(board,gold,rules.evaluate(board,rules.cascadeMultiplier(free,0)));
        check(c,free,0);return c;
        }catch(RejectedCandidate unsupported){conditionedColumnRetries++;}}
        throw new RejectedCandidate("no column chain for chosen feature layout");
    }
    public int[][] refill(boolean free,int[]counts,SecureRandom r){
        String mode=free?"F":"P";int[][] out=new int[5][];
        for(int reel=0;reel<5;reel++){
            int n=counts[reel];out[reel]=new int[n];if(n==0)continue;
            String v=draw(mode+":R:"+reel+":"+n,r);if(v.length()!=n)throw new IllegalStateException("refill vector length");
            for(int row=0;row<n;row++)out[reel][row]=Character.digit(v.charAt(row),36);
        }return out;
    }
    public void check(GameRuleCore.Cascade c,boolean free,int index){
        String entry=(free?"F":"P")+(index==0?"I":"C");Limit l=limits.get(entry);
        int scat=0,wild=0;int[]sr=new int[5],wr=new int[5],b=c.board();
        for(int p=0;p<20;p++){
            if(b[p]==9){scat++;sr[p/4]++;if((l.scatterPositions&(1L<<p))==0)throw new RejectedCandidate("unobserved Scatter position");}
            if(b[p]==10){wild++;wr[p/4]++;if((l.wildPositions&(1L<<p))==0)throw new RejectedCandidate("unobserved Wild position");}
        }
        if(scat>l.scatter||wild>l.wild)throw new RejectedCandidate("whole-board special limit");
        for(int reel=0;reel<5;reel++)if(sr[reel]>l.scatterByReel[reel]||wr[reel]>l.wildByReel[reel])throw new RejectedCandidate("reel special limit");
    }
    public int maxSteps(boolean free){return caps.get(free?"freeSteps":"paidSteps");}
    public int maxDeliveries(){return caps.get("deliveriesPerRound");}
    public String sourceHash(){return sourceHash;}
    public long conditionedColumnRetries(){return conditionedColumnRetries;}

    private final Map<String,Weighted> lossGroups=new java.util.concurrent.ConcurrentHashMap<>();
    private Weighted filtered(String key,java.util.function.Predicate<String> eligible){
        Weighted source=groups.get(key);if(source==null)throw new IllegalStateException("missing loss support "+key);
        List<String> values=new ArrayList<>();List<Long> counts=new ArrayList<>();long previous=0;
        for(int i=0;i<source.values.length;i++){
            long count=source.cumulative[i]-previous;previous=source.cumulative[i];
            if(eligible.test(source.values[i])){values.add(source.values[i]);counts.add(count);}
        }
        long total=0;long[] cumulative=new long[counts.size()];for(int i=0;i<counts.size();i++)cumulative[i]=total+=counts.get(i);
        if(total==0)throw new IllegalStateException("empty conditioned loss support "+key);
        return new Weighted(values.toArray(String[]::new),cumulative,total);
    }
    private static int regularMask(String vector){int mask=0;for(char v:vector.toCharArray()){int id=Character.digit(v,36);if(id<1||id>8)return -1;mask|=1<<(id-1);}return mask;}
    public GameRuleCore.Cascade lossInitial(SecureRandom random,GameRuleCore rules){
        Weighted layouts=lossGroups.computeIfAbsent("layout",k->filtered("P:L",v->Long.parseLong(v.split("\\.")[0],16)==0));
        String[] layout=layouts.draw(random).split("\\.");long goldMask=Long.parseLong(layout[1],16);
        int[] board=new int[20];boolean[] gold=new boolean[20];int prefix=255;
        for(int c=0;c<5;c++){
            final int col=c,prior=prefix;
            Weighted vectors=lossGroups.computeIfAbsent(c+":"+prefix,k->filtered("P:M:"+col+":0:"+prior,v->{
                int mask=regularMask(v);if(mask<0)return false;
                if(col==1)return (mask&prior)==0;
                if(col==0){Weighted next=groups.get("P:M:1:0:"+mask);if(next==null)return false;for(String w:next.values)if(regularMask(w)>=0&&(regularMask(w)&mask)==0)return true;return false;}
                return true;
            }));
            String v=vectors.draw(random);for(int row=0;row<4;row++){int pos=c*4+row;board[pos]=Character.digit(v.charAt(row),36);gold[pos]=(goldMask&(1L<<pos))!=0;}
            prefix&=regularMask(v);
        }
        var result=new GameRuleCore.Cascade(board,gold,rules.evaluate(board,1));check(result,false,0);return result;
    }
}
