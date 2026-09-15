package com.cpgame.curupira.core;
import com.cpgame.curupira.model.CompleteRound;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.RoundStep;
import java.util.List;
/** 一次调用覆盖从付费起点到 Round 终止；当前只启用已确认的普通单步模式。 */
public final class RoundFactory {
 public static final String ORDINARY_POOL=GameRules.ORDINARY_POOL;private final CandidateBoardGenerator candidates;private final ResultUtil util;private final GenerationPolicy policy;private final RoundIdGenerator ids=new RoundIdGenerator();
 public RoundFactory(CandidateBoardGenerator c,ResultUtil u,GenerationPolicy p){candidates=c;util=u;policy=p;}
 public CompleteRound generateCompletePaidRound(){for(int i=0;i<10000;i++){EvaluatedBoard e=util.evaluate(candidates.nextBoard());try{util.assertOrdinaryTerminal(e);}catch(IllegalArgumentException unresolved){continue;}CompleteRound r=new CompleteRound(ids.next(),RulesContract.GAME_ID,RulesContract.RULES_VERSION,RulesContract.RULES_HASH,ORDINARY_POOL,List.of(new RoundStep(1,e)));if(r.totalMultiplier()<=policy.maxRoundMultiplier())return r;}throw new IllegalStateException("Unable to generate complete ordinary Round");}
}
