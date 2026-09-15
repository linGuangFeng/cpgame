package com.cpgame.beachfun.core;

import java.security.SecureRandom;
import java.util.*;

/** Every candidate is generated to its true terminal state; no boards are patched to force an outcome. */
public final class RoundFactory {
    public enum Requested {LOSS,WIN,CASCADE,GOLD,FREE}
    private final GameRuleCore rules;private final SecureRandom random;private final DistributionModel model;
    private final Map<String,Long> rejected=new TreeMap<>();
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;
    public RoundFactory(GameRuleCore rules){this(rules,new SecureRandom());}
    public RoundFactory(GameRuleCore rules,SecureRandom random){this.rules=rules;this.random=random;this.model=new DistributionModel();losses=new ZeroLossSupport<>(this::lossCandidate,r->!r.win()&&!r.freeFeature(),r->new GameRuleCore.CompleteRound(UUID.randomUUID().toString().replace("-",""),r.win(),r.freeFeature(),r.cascadeFeature(),r.goldFeature(),r.totalUnits(),r.deliveries()));}
    public GameRuleCore.CompleteRound generate(Requested requested){return generateMatching(requested,false);}
    public GameRuleCore.CompleteRound generateForPool(Requested requested){return generateMatching(requested,true);}
    public GameRuleCore.CompleteRound generateNatural(){return generateMatching(null,false);}
    private GameRuleCore.CompleteRound generateMatching(Requested requested,boolean integerOnly){
        if(requested==Requested.LOSS)return losses.generate(this::lossCandidate,random::nextInt);
        for(int attempt=0;attempt<20000;attempt++){
            GameRuleCore.CompleteRound r;
            try{r=candidate();}catch(DistributionModel.RejectedCandidate e){rejected.merge(e.getMessage(),1L,Long::sum);continue;}
            boolean accept=requested==null||switch(requested){
                case LOSS->!r.win()&&!r.freeFeature();case WIN->r.win()&&!r.freeFeature();
                case CASCADE->r.win()&&r.cascadeFeature()&&!r.freeFeature();
                case GOLD->r.win()&&r.goldFeature()&&!r.freeFeature();case FREE->r.freeFeature();
            };
            if(accept&&(!integerOnly||r.totalUnits()%20==0))return r;
        }
        throw new IllegalStateException("No complete candidate for "+requested+" within attempt limit; "+rejected);
    }
    private GameRuleCore.CompleteRound candidate(){
        List<GameRuleCore.Delivery> deliveries=new ArrayList<>();int remaining=0;
        do{
            boolean free=!deliveries.isEmpty();
            if(deliveries.size()>=model.maxDeliveries())throw new DistributionModel.RejectedCandidate("observed delivery limit");
            List<GameRuleCore.Cascade> steps=new ArrayList<>();
            var c=model.initial(free,random,rules);steps.add(c);
            while(!c.wins().isEmpty()){
                if(steps.size()>=model.maxSteps(free))throw new DistributionModel.RejectedCandidate("observed cascade limit");
                c=rules.transition(c,model.refill(free,rules.refillCounts(c),random),free,steps.size());
                model.check(c,free,steps.size());steps.add(c);
            }
            int award=rules.awardedFreeSpins(rules.scatterCount(c.board()));
            remaining=(free?remaining-1:0)+award;
            deliveries.add(new GameRuleCore.Delivery(free,award,remaining,steps));
        }while(remaining>0);
        long units=0;boolean gold=false,cascade=false;
        for(var d:deliveries){cascade|=d.cascades().size()>1;for(var c:d.cascades()){units+=rules.payoutUnits(c.wins());for(boolean g:c.gold())gold|=g;}}
        var round=new GameRuleCore.CompleteRound(UUID.randomUUID().toString().replace("-",""),units>0,deliveries.size()>1,cascade,gold,units,deliveries);
        rules.validate(round);ResultUtil.verify(round);return round;
    }
    public Map<String,Long> rejectedCandidates(){Map<String,Long> out=new TreeMap<>(rejected);out.put("conditioned column retries with fixed feature layout",model.conditionedColumnRetries());return Map.copyOf(out);}

    public GameRuleCore.CompleteRound lossCandidate(){
        var first=model.lossInitial(random,rules);
        var round=new GameRuleCore.CompleteRound(UUID.randomUUID().toString().replace("-",""),false,false,false,
                java.util.stream.IntStream.range(0,20).anyMatch(i->first.gold()[i]),0,List.of(new GameRuleCore.Delivery(false,0,0,List.of(first))));
        rules.validate(round);ResultUtil.verify(round);return round;
    }
}
