package com.cpgame.luckynightmarket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** A fitted conditional reel-vector Markov model; never loads provider responses. */
public final class DealingModel {
    private final Map<String,Object> model, entries;
    private final SecureRandom random;
    private final double backoff;
    private final int attemptLimit;
    private final Properties config;
    private final ZeroLossSupport<RoundFact.Step> losses;
    private final ZeroLossSupport<RoundFact.Step> featureLosses;
    public long rejectedSteps, rejectedRounds;
    public DealingModel(Properties config) throws IOException {
        this.config=config; this.random=new SecureRandom();
        try(InputStream in=DealingModel.class.getResourceAsStream("/dealing-model.json")) {
            if(in==null)throw new IOException("Embedded fitted model missing");
            model=Json.object(Json.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8)));
        }
        entries=Json.object(model.get("entries"));
        backoff=Double.parseDouble(config.getProperty("model.column-backoff-probability",model.get("columnBackoffProbability").toString()));
        attemptLimit=Integer.parseInt(config.getProperty("generation.attempt-limit","100000"));
        if(!Double.isFinite(backoff)||backoff<0||backoff>1||attemptLimit<100)throw new IllegalArgumentException("Invalid model limits");
        if(Integer.parseInt(config.getProperty("generation.feature-steps","8"))!=8)throw new IllegalArgumentException("The original feature has exactly eight steps");
        losses=new ZeroLossSupport<>(this::lossCandidate,s->validLoss(s,false),s->s);
        // Initialize ten verified reserves once. Runtime feature markers try at most five raw proposals.
        featureLosses=new ZeroLossSupport<>(()->step("featureLater",0),s->validLoss(s,true),s->s);
    }
    public Map<String,Object> metadata(){return model;}
    public static int bin(long units){int[] bounds={0,49,174,249,499,999,2499,16000};for(int i=0;i<bounds.length;i++)if(units<=bounds[i])return i;throw new IllegalArgumentException("Units exceed model support");}
    private int n(Object value){return ((Number)value).intValue();}
    private String pick(Map<String,Object> weights){
        double sum=0;for(Object v:weights.values()){double weight=((Number)v).doubleValue();if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("Invalid sampling weight");sum+=weight;}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Empty sampling support");
        double draw=random.nextDouble()*sum;String last=null;
        for(var e:weights.entrySet()){last=e.getKey();draw-=((Number)e.getValue()).doubleValue();if(draw<0)return last;}return last;
    }
    private List<Integer> ints(String text){return Arrays.stream(text.split(",")).map(Integer::parseInt).toList();}
    private Map<String,Object> objectAt(Object list,int index){return Json.object(((List<?>)list).get(index));}
    private boolean inCaps(RoundFact.Step s,Map<String,Object> e){
        int[] counts=new int[7];List<?> allowed=(List<?>)e.get("positionSymbols");
        for(int p=0;p<9;p++){int symbol=s.ps().get(p);counts[symbol]++;if(!((List<?>)allowed.get(p)).stream().anyMatch(x->n(x)==symbol))return false;}
        if(counts[0]>n(e.get("wildBoardMax")))return false;
        for(int c=0;c<3;c++){int wild=0;for(int r=0;r<3;r++)if(s.ps().get(c*3+r)==0)wild++;if(wild>n(((List<?>)e.get("wildColumnMax")).get(c)))return false;}
        for(int sId=0;sId<7;sId++)if(counts[sId]>n(((List<?>)e.get("symbolBoardMax")).get(sId)))return false;
        if(s.muls().stream().filter(x->x==0).count()>n(e.get("maxTickets")))return false;
        List<?> ticketPositions=(List<?>)e.get("ticketPositions");
        for(int p=0;p<3;p++)if(s.muls().get(p)==0){final int position=p;if(ticketPositions.stream().noneMatch(x->n(x)==position))return false;}
        return true;
    }
    public RoundFact.Step step(String entry,int targetBin){
        Map<String,Object> e=Json.object(entries.get(entry));
        Map<String,Object> groups=Json.object(e.get("groups"));
        Map<String,Object> g=Json.object(groups.get(Integer.toString(targetBin)));
        for(int attempt=0;attempt<attemptLimit;attempt++){
            RoundFact.Step s=stepCandidate(entry,e,g);
            long units=GameRuleCore.evaluate(s,entry.startsWith("feature")).units();
            if(inCaps(s,e)&&units<=n(g.get("maxUnits"))&&bin(units)==targetBin)return s;
            rejectedSteps++;
        }
        throw new IllegalStateException("Unable to generate entry "+entry+" bin "+targetBin+" within bounded attempts");
    }
    private RoundFact.Step stepCandidate(String entry, Map<String,Object> e, Map<String,Object> g) {
        // Preserve the original conditional reel vectors and joint multiplier triples.
        Map<String,Object> reels=entry.equals("wheel")?Json.object(e.get("reelStatistics")):g;
        double entryBackoff=entry.equals("wheel")?1:backoff;
        List<Integer> ps=new ArrayList<>();String previous=null;
        for(int col=0;col<3;col++){
            Map<String,Object> weights=objectAt(reels.get("columns"),col);
            if(col>0&&random.nextDouble()>=entryBackoff){Object conditioned=objectAt(reels.get("transitions"),col-1).get(previous);if(conditioned!=null)weights=Json.object(conditioned);}
            previous=pick(weights);ps.addAll(ints(previous));
        }
        List<Integer> muls=ints(pick(Json.object(g.get("multipliers"))));
        int wem=entry.equals("wheel")?Integer.parseInt(pick(Json.object(g.get("wheelPrizes")))):0;
        return new RoundFact.Step(ps,muls,wem);
    }

    private boolean validLoss(RoundFact.Step step, boolean feature) {
        return step != null && !step.wheel()
                && inCaps(step,Json.object(entries.get(feature?"featureLater":"ordinary")))
                && GameRuleCore.evaluate(step,feature).units()==0
                && ResultUtil.evaluate(step,feature).units()==0;
    }

    /** Only ordinary loss or featureLater; featureStart and wheel never use a marker. */
    public RoundFact.Step independentLoss(boolean feature) {
        if(!feature)return ordinaryStep(false);
        Map<String,Object> e=Json.object(entries.get("featureLater"));
        Map<String,Object> g=Json.object(Json.object(e.get("groups")).get("0"));
        return featureLosses.generate(()->stepCandidate("featureLater",e,g),random::nextInt);
    }

    /** Natural entry-level draws. Rejection classifies an already dealt board; no symbols are patched. */
    private RoundFact.Step ordinaryStep(boolean winning){
        if(!winning)return losses.generate(this::lossCandidate,random::nextInt);
        Map<String,Object> e=Json.object(entries.get("ordinary"));
        Map<String,Object> reels=Json.object(e.get("reelStatistics"));
        for(int attempt=0;attempt<attemptLimit;attempt++){
            List<Integer> ps=new ArrayList<>();String previous=null;
            for(int col=0;col<3;col++){
                Map<String,Object> weights=objectAt(reels.get("columns"),col);
                if(col>0&&random.nextDouble()>=backoff){Object conditioned=objectAt(reels.get("transitions"),col-1).get(previous);if(conditioned!=null)weights=Json.object(conditioned);}
                previous=pick(weights);ps.addAll(ints(previous));
            }
            RoundFact.Step step=new RoundFact.Step(ps,ints(pick(Json.object(reels.get("multipliers")))),0);
            long units=GameRuleCore.evaluate(step,false).units();
            if(inCaps(step,e)&&units<=n(reels.get("maxUnits"))&&(units>0)==winning)return step;
            rejectedSteps++;
        }
        throw new IllegalStateException("Unable to generate ordinary "+(winning?"win":"loss")+" within bounded attempts");
    }
    public RoundFact.Mode mode(){
        Map<String,Object> weights=new LinkedHashMap<>(Json.object(model.get("modeCounts")));
        for(String key:new ArrayList<>(weights.keySet())){double scale=Double.parseDouble(config.getProperty("mode.weight."+key,"1"));if(scale<0||!Double.isFinite(scale))throw new IllegalArgumentException("Invalid mode weight");weights.put(key,((Number)weights.get(key)).doubleValue()*scale);}
        return RoundFact.Mode.valueOf(pick(weights));
    }
    public RoundFact.Mode poolMode(){
        Map<String,Object> weights=new LinkedHashMap<>(Json.object(model.get("modeCounts")));
        if(Long.parseLong(config.getProperty("range.normal-min","1"))>0)weights.remove("ORDINARY_LOSS");
        for(String key:new ArrayList<>(weights.keySet())){double scale=Double.parseDouble(config.getProperty("mode.weight."+key,"1"));if(scale<0||!Double.isFinite(scale))throw new IllegalArgumentException("Invalid mode weight");weights.put(key,((Number)weights.get(key)).doubleValue()*scale);}
        return RoundFact.Mode.valueOf(pick(weights));
    }
    private int randomBin(String entry,boolean excludeZero){Map<String,Object>w=new LinkedHashMap<>(Json.object(Json.object(entries.get(entry)).get("binCounts")));if(excludeZero)w.remove("0");return Integer.parseInt(pick(w));}
    public RoundFact generate(RoundFact.Mode mode){
        for(int attempt=0;attempt<attemptLimit;attempt++){
            List<RoundFact.Step> steps=new ArrayList<>();
            switch(mode){
                case ORDINARY_LOSS->steps.add(ordinaryStep(false));
                case ORDINARY_WIN->steps.add(ordinaryStep(true));
                case LUCKY_WHEEL->steps.add(step("wheel",randomBin("wheel",false)));
                case LUCKY_FEATURE->{
                    steps.add(step("featureStart",0));int prior=0;
                    for(int i=0;i<7;i++){
                        Map<String,Object> transition=objectAt(model.get("featureBinTransitions"),i);
                        Object weights=transition.get(Integer.toString(prior));
                        prior=weights==null?randomBin("featureLater",false):Integer.parseInt(pick(Json.object(weights)));
                        steps.add(step("featureLater",prior));
                    }
                }
            }
            RoundFact round=new RoundFact(mode,steps);long units=GameRuleCore.totalUnits(round);
            if(mode==RoundFact.Mode.LUCKY_FEATURE){long wins=steps.stream().filter(s->GameRuleCore.evaluate(s,true).units()>0).count();
                if(units<=0||units>n(model.get("featureMaxTotalUnits"))||wins<n(model.get("featureWinCountMin"))||wins>n(model.get("featureWinCountMax"))){rejectedRounds++;continue;}}
            GameRuleCore.validate(round);
            if(GameRuleCore.totalUnits(round)!=ResultUtil.totalUnits(round))throw new IllegalStateException("Independent oracle disagreement");return round;
        }
        throw new IllegalStateException("Unable to generate complete round within bounded attempts");
    }
    public RoundFact generate(){return generate(mode());}
    public RoundFact generateForPool(RoundFact.Mode mode){
        boolean special=mode==RoundFact.Mode.LUCKY_FEATURE||mode==RoundFact.Mode.LUCKY_WHEEL;
        long min=Long.parseLong(config.getProperty(special?"range.special-min":"range.normal-min",special?"25":"1"));
        long max=Long.parseLong(config.getProperty(special?"range.special-max":"range.normal-max",special?"500":"375"));
        for(int i=0;i<attemptLimit;i++){RoundFact r=generate(mode);long u=GameRuleCore.totalUnits(r);if(u>=min&&u<=max)return r;rejectedRounds++;}
        throw new IllegalStateException("Configured payout range has insufficient generation support for "+mode);
    }

    private final Map<String,List<List<Integer>>> lossColumns=new java.util.concurrent.ConcurrentHashMap<>();
    public RoundFact.Step lossCandidate(){
        Map<String,Object> reels=Json.object(Json.object(entries.get("ordinary")).get("reelStatistics"));
        List<Integer> ps=new ArrayList<>(9);int first=0;
        for(int c=0;c<3;c++){
            final int col=c,forbidden=c==1?first:0;
            List<List<Integer>> pool=lossColumns.computeIfAbsent(c+":"+forbidden,key->{
                List<List<Integer>> out=new ArrayList<>();
                for(var e:objectAt(reels.get("columns"),col).entrySet()){
                    List<Integer> tuple=ints(e.getKey());if(new HashSet<>(tuple).size()!=3)continue;
                    if(tuple.stream().anyMatch(v->v==0||(forbidden&(1<<v))!=0))continue;
                    for(int i=0;i<n(e.getValue());i++)out.add(tuple);
                }
                if(out.isEmpty())throw new IllegalStateException("no loss reel support");return List.copyOf(out);
            });
            List<Integer> tuple=pool.get(random.nextInt(pool.size()));ps.addAll(tuple);if(c==0)for(int v:tuple)first|=1<<v;
        }
        return new RoundFact.Step(ps,ints(pick(Json.object(reels.get("multipliers")))),0);
    }
}
