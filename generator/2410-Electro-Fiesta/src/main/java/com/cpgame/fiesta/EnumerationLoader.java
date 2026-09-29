package com.cpgame.fiesta;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;

/** Finite payout-bucket coverage; never runs a user-specified billion-round rejection loop. */
final class EnumerationLoader {
    private EnumerationLoader() {}
    static RedisLoaderMain.GenerationSummary generate(Properties p, RedisLoaderMain.RoundSink sink){
        LoaderLimits limits=new LoaderLimits(p);
        int normalCap=positive(p,"generation.max-members-per-multiplier",300,1_000_000);
        int maxChain=positive(p,"generation.max-consecutive-wins",10,1000);
        int maxMode=positive(p,"generation.mode.MULTIPLIER_STICKY.max-spins",30,30);
        SecureRandom random=new SecureRandom();GameRuleCore rules=new GameRuleCore();ResultUtil util=new ResultUtil(rules);
        CandidateFactory factory=new CandidateFactory(random,rules,new Properties());
        OrdinaryBoardCatalog catalog=new OrdinaryBoardCatalog(new int[]{1,1,1,1,1,1});
        Map<Integer,Map<String,List<Supplier<GameRound>>>> normalPlans=new TreeMap<>();
        Map<Integer,Map<String,List<Supplier<GameRound>>>> maryPlans=new TreeMap<>();
        Map<Integer,Integer> directBoards=new TreeMap<>();
        if(limits.accepts(false,0))add(normalPlans,0,"LOSS",()->factory.ordinary(false));
        for(int b=0;b<catalog.bucketCount();b++)if(limits.accepts(false,catalog.ratio(b))){
            int bucket=b,ratio=catalog.ratio(b);
            Map<String,List<Integer>> patterns=new TreeMap<>();
            for(int i=0;i<catalog.size(b);i++){
                String signature=rules.evaluateBoard(catalog.board(b,i)).toString();
                patterns.computeIfAbsent(signature,k->new ArrayList<>()).add(i);
            }
            directBoards.merge(ratio,catalog.size(b),Integer::sum);
            for(List<Integer> indices:patterns.values()){
                Collections.shuffle(indices,random);int[] cursor={0};
                add(normalPlans,ratio,"DIRECT",()->{
                    if(cursor[0]>=indices.size())return null;
                    return new GameRound(List.of(new RoundState(RoundState.Mode.NORMAL,catalog.board(bucket,indices.get(cursor[0]++)),new int[9],new int[0],0,-1,0)));
                });
            }
        }
        int respinPages=Math.min(11,maxChain+1);
        boolean normalRespin=limits.respinShare.signum()>0;
        boolean maryRespin=limits.maryRespinShare.signum()>0;
        for(int base:GameRuleCore.SYMBOLS)for(int col=0;col<3;col++)for(int mask=1;mask<7;mask++){
            int[] board=new int[9];Arrays.fill(board,base);
            for(int row=0;row<3;row++)if((mask&(1<<row))==0)board[col*3+row]=base==2?3:2;
            int ratio=rules.payoutUnits(board),column=col,matchingMask=mask;
            if(normalRespin&&limits.accepts(false,ratio))add(normalPlans,ratio,"RESPIN",()->factory.enumeratedRespin(base,column,matchingMask,respinPages));
            if(maryRespin&&limits.accepts(true,ratio))add(maryPlans,ratio,"RESPIN",()->factory.enumeratedRespin(base,column,matchingMask,respinPages));
        }
        int maxAdditions=Math.min(9,Math.min(maxMode,maxChain+1)-2);
        for(int base:GameRuleCore.SYMBOLS)for(int count=Math.min(3,maxAdditions);count<=maxAdditions&&count>0;count++)for(int sum=2*count;sum<=10*count;sum++){
            int ratio=base*5*sum,n=count,total=sum;
            if(!limits.accepts(true,ratio)||!CandidateFactory.materialSumReachable(count,sum))continue;
            add(maryPlans,ratio,"FULL",()->factory.enumeratedMultiplier(base,n,total,false));
            add(maryPlans,ratio,"RESPIN_FULL",()->factory.enumeratedMultiplier(base,n,total,true));
        }
        Map<Integer,int[]> normalQuotas=winShareQuotas(normalPlans,directBoards,normalCap,limits.respinShare);
        List<Bucket> buckets=new ArrayList<>();
        for(var entry:normalPlans.entrySet()){
            int ratio=entry.getKey();
            int[] quota=ratio==0?new int[]{normalCap,0}:normalQuotas.get(ratio);
            if(quota==null)continue;
            Map<String,Integer> weights=new LinkedHashMap<>();
            Map<String,Integer> limitsByFamily=new LinkedHashMap<>();
            if(entry.getValue().containsKey("LOSS")){weights.put("LOSS",1);limitsByFamily.put("LOSS",normalCap);}
            if(quota[0]>0&&entry.getValue().containsKey("DIRECT")){weights.put("DIRECT",quota[0]);limitsByFamily.put("DIRECT",quota[0]);}
            if(quota[1]>0&&entry.getValue().containsKey("RESPIN")){weights.put("RESPIN",quota[1]);limitsByFamily.put("RESPIN",quota[1]);}
            int target=ratio==0?normalCap:quota[0]+quota[1];
            if(target<=0||weights.isEmpty())continue;
            buckets.add(new Bucket(false,ratio,target,mix(entry.getValue(),weights,limitsByFamily,random)));
        }
        for(var entry:maryPlans.entrySet()){
            boolean hasFull=entry.getValue().containsKey("FULL")||entry.getValue().containsKey("RESPIN_FULL");
            boolean hasRespin=entry.getValue().containsKey("RESPIN");
            int[] quota=quotas(hasFull,hasRespin,Integer.MAX_VALUE,limits.specialCap,limits.maryRespinShare.doubleValue());
            Map<String,Integer> weights=new LinkedHashMap<>();
            Map<String,Integer> limitsByFamily=new LinkedHashMap<>();
            if(quota[0]>0){
                int fullFamilies=(entry.getValue().containsKey("FULL")?1:0)+(entry.getValue().containsKey("RESPIN_FULL")?1:0);
                int per=fullFamilies==0?0:Math.max(1,quota[0]/fullFamilies);
                if(entry.getValue().containsKey("FULL")){weights.put("FULL",per);limitsByFamily.put("FULL",per);}
                if(entry.getValue().containsKey("RESPIN_FULL")){weights.put("RESPIN_FULL",per);limitsByFamily.put("RESPIN_FULL",per);}
            }
            if(quota[1]>0&&hasRespin){weights.put("RESPIN",quota[1]);limitsByFamily.put("RESPIN",quota[1]);}
            int target=Math.min(limits.specialCap,weights.values().stream().mapToInt(Integer::intValue).sum());
            if(target<=0||weights.isEmpty())continue;
            buckets.add(new Bucket(true,entry.getKey(),target,mix(entry.getValue(),weights,limitsByFamily,random)));
        }
        RoundCodec codec=new RoundCodec();int normal=0,mary=0;long attempts=0;
        System.out.printf(Locale.ROOT,"ENUMERATION_START ordinaryBoards=%d buckets=%d normalCap=%d specialCap=%d respinShare=%s maryRespinShare=%s%n",
                catalog.boardCount,buckets.size(),normalCap,limits.specialCap,limits.respinShare.toPlainString(),limits.maryRespinShare.toPlainString());
        while(!buckets.isEmpty()){
            for(Iterator<Bucket> it=buckets.iterator();it.hasNext();){
                Bucket bucket=it.next();
                if(++bucket.attempts>Math.max(10000L,100L*bucket.target))throw new IllegalStateException("Enumeration could not fill unique bucket "+bucket.ratio+" special="+bucket.special+" filled="+bucket.seen.size()+"/"+bucket.target);
                attempts++;GameRound round=bucket.next.get();if(round==null){it.remove();continue;}ResultUtil.Analysis analysis=util.analyze(round);
                if(analysis.integerMultiplier()!=bucket.ratio||!RedisKeys.allowedInPool(bucket.special,analysis.outcome()))throw new IllegalStateException("enumeration bucket mismatch");
                if(!bucket.seen.add(codec.encodeFull(round)))continue;
                sink.accept(round,analysis,bucket.special);if(bucket.special)mary++;else normal++;
                if(bucket.seen.size()>=bucket.target)it.remove();
            }
        }
        return new RedisLoaderMain.GenerationSummary(normal,mary,attempts);
    }
    /** Floor-weighted lock-column share among non-fullscreen normal wins, dropping direct-only ratios when the target is otherwise unreachable. */
    static Map<Integer,int[]> winShareQuotas(Map<Integer,Map<String,List<Supplier<GameRound>>>> plans,Map<Integer,Integer> directBoards,int cap,BigDecimal share){
        Set<Integer> active=new TreeSet<>();
        for(var entry:plans.entrySet()){
            int ratio=entry.getKey();if(ratio<=0)continue;
            boolean direct=entry.getValue().containsKey("DIRECT");
            boolean respin=entry.getValue().containsKey("RESPIN");
            if(direct||respin)active.add(ratio);
        }
        double target=share.doubleValue();
        if(target<=0){
            Map<Integer,int[]> out=new LinkedHashMap<>();
            for(int ratio:active)out.put(ratio,new int[]{cap,0});
            return out;
        }
        while(winMass(active,plans)[2]<target-1e-12 && dropDirectOnly(active,plans)){ /* drop highest-weight direct-only ratios until the target is reachable */ }
        double[] mass=winMass(active,plans);
        double mixedW=mass[0],respinOnlyW=mass[1],totalW=mass[3];
        double r=mixedW<=0?0:Math.min(1,Math.max(0,(target*totalW-respinOnlyW)/mixedW));
        Map<Integer,int[]> out=new LinkedHashMap<>();
        for(int ratio:active){
            boolean direct=plans.get(ratio).containsKey("DIRECT");
            boolean respin=plans.get(ratio).containsKey("RESPIN");
            out.put(ratio,quotas(direct,respin,directBoards.getOrDefault(ratio,0),cap,r));
        }
        return out;
    }
    static double floorWeightedShare(Map<Integer,int[]> counts){
        int[] ratios=counts.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        if(ratios.length==0)return 0;
        int top=ratios[ratios.length-1];
        double respin=0,direct=0;
        for(int i=0;i<ratios.length;i++){
            int w=(i+1<ratios.length?ratios[i+1]:top+1)-ratios[i];
            int[] c=counts.get(ratios[i]);int size=c[0]+c[1];if(size==0)continue;
            direct+=w*(c[0]/(double)size);respin+=w*(c[1]/(double)size);
        }
        return respin+direct==0?0:respin/(respin+direct);
    }
    private static int[] quotas(boolean direct,boolean respin,int boards,int cap,double r){
        if(direct&&!respin)return new int[]{Math.min(cap,Math.max(1,boards)),0};
        if(!direct&&respin)return new int[]{0,cap};
        if(r<=0)return new int[]{Math.min(cap,Math.max(1,boards)),0};
        if(r>=1)return new int[]{0,cap};
        int available=Math.min(cap,Math.max(0,boards));
        double total=Math.min(cap,available/(1-r));
        int directQ=Math.min(available,(int)Math.round(total*(1-r)));
        int respinQ=Math.min(cap-directQ,(int)Math.round(total*r));
        if(respinQ==0&&r>0){respinQ=1;if(directQ>0&&directQ+respinQ>cap)directQ--;}
        if(directQ==0&&r<1&&available>0){directQ=1;if(directQ+respinQ>cap)respinQ=Math.max(0,cap-directQ);}
        return new int[]{directQ,respinQ};
    }
    private static double[] winMass(Set<Integer> active,Map<Integer,Map<String,List<Supplier<GameRound>>>> plans){
        int[] ratios=active.stream().mapToInt(Integer::intValue).sorted().toArray();
        double mixed=0,respinOnly=0,total=0;
        if(ratios.length==0)return new double[]{0,0,0,0};
        int top=ratios[ratios.length-1];
        for(int i=0;i<ratios.length;i++){
            int w=(i+1<ratios.length?ratios[i+1]:top+1)-ratios[i];
            total+=w;
            boolean direct=plans.get(ratios[i]).containsKey("DIRECT");
            boolean respin=plans.get(ratios[i]).containsKey("RESPIN");
            if(direct&&respin)mixed+=w;else if(respin)respinOnly+=w;
        }
        return new double[]{mixed,respinOnly,total==0?0:(mixed+respinOnly)/total,total};
    }
    private static boolean dropDirectOnly(Set<Integer> active,Map<Integer,Map<String,List<Supplier<GameRound>>>> plans){
        int best=-1;double bestP=-1;double current=winMass(active,plans)[2];
        for(int ratio:List.copyOf(active)){
            if(!plans.get(ratio).containsKey("DIRECT")||plans.get(ratio).containsKey("RESPIN"))continue;
            active.remove(ratio);
            double p=winMass(active,plans)[2];
            active.add(ratio);
            if(p>bestP){bestP=p;best=ratio;}
        }
        if(best<0||bestP<=current+1e-15)return false;
        active.remove(best);return true;
    }
    private static void add(Map<Integer,Map<String,List<Supplier<GameRound>>>> plans,int ratio,String family,Supplier<GameRound> source){
        plans.computeIfAbsent(ratio,k->new TreeMap<>()).computeIfAbsent(family,k->new ArrayList<>()).add(source);
    }
    private static Supplier<GameRound> mix(Map<String,List<Supplier<GameRound>>> families,Map<String,Integer> weights,Map<String,Integer> quotas,SecureRandom random){
        List<String> names=new ArrayList<>(weights.keySet());Collections.shuffle(names,random);
        Map<String,Supplier<GameRound>> sources=new LinkedHashMap<>();
        for(String name:names)sources.put(name,cycle(families.getOrDefault(name,List.of()),random));
        int[] acc=new int[names.size()],used=new int[names.size()];
        return ()->{while(true){
            int pick=-1,best=Integer.MIN_VALUE,total=0;
            for(int i=0;i<names.size();i++){
                if(sources.get(names.get(i))==null)continue;
                if(used[i]>=quotas.getOrDefault(names.get(i),Integer.MAX_VALUE))continue;
                int w=Math.max(0,weights.getOrDefault(names.get(i),0));if(w==0)continue;
                total+=w;acc[i]+=w;if(acc[i]>best){best=acc[i];pick=i;}
            }
            if(pick<0||total==0)return null;
            acc[pick]-=total;
            GameRound round=sources.get(names.get(pick)).get();
            if(round==null){sources.put(names.get(pick),null);continue;}
            used[pick]++;return round;
        }};
    }
    private static Supplier<GameRound> cycle(List<Supplier<GameRound>> sources,SecureRandom random){
        List<Supplier<GameRound>> active=new ArrayList<>(sources);Collections.shuffle(active,random);int[] cursor={0};
        return ()->{while(!active.isEmpty()){
            int index=cursor[0]%active.size();GameRound round=active.get(index).get();
            if(round==null){active.remove(index);cursor[0]=index;continue;}
            cursor[0]=(index+1)%active.size();return round;
        }return null;};
    }
    private static int positive(Properties p,String key,int fallback,int max){int n=Integer.parseInt(p.getProperty(key,Integer.toString(fallback)));if(n<1||n>max)throw new IllegalArgumentException(key+" out of range");return n;}
    private static final class Bucket {
        final boolean special;final int ratio,target;final Supplier<GameRound> next;final Set<String> seen=new HashSet<>();long attempts;
        Bucket(boolean special,int ratio,int target,Supplier<GameRound> next){this.special=special;this.ratio=ratio;this.target=target;this.next=next;}
    }
}
