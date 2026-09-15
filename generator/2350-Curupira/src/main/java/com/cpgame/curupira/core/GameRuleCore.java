package com.cpgame.curupira.core;
import com.cpgame.curupira.model.*;
import com.cpgame.curupira.random.*;
import com.cpgame.curupira.verify.RoundVerifier;
import java.math.*;
import java.time.Instant;
/** 试玩 API 与 Loader 共用的唯一正式规则核心。 */
public final class GameRuleCore{
 private final RoundFactory factory;private final ConstructiveLossGenerator losses;private final RoundVerifier verifier;private final SpecialRoundFactory specials;private final RoundIdGenerator ids=new RoundIdGenerator();
 public GameRuleCore(){this(new SecureRandomSource(),GenerationPolicy.ordinaryPaidDefaults());}
 public GameRuleCore(RandomSource random,GenerationPolicy policy){ResultUtil util=new ResultUtil();WeightedSymbolSampler sampler=new WeightedSymbolSampler(random,policy.symbolWeights());CandidateBoardGenerator candidates=new CandidateBoardGenerator(sampler);factory=new RoundFactory(candidates,util,policy);losses=new ConstructiveLossGenerator(sampler,candidates,util,policy);verifier=new RoundVerifier(policy);specials=new SpecialRoundFactory(random,policy);}
 public CompleteRound generateCompleteRound(){CompleteRound r=factory.generateCompletePaidRound();verifier.verify(r);return r;}
 public com.cpgame.curupira.model.CompleteRoundFact generateFact(com.cpgame.curupira.model.CompleteRoundFact.Kind kind){
  com.cpgame.curupira.model.CompleteRoundFact fact=specials.generate(kind);
  verifier.verifyFact(fact);
  return fact;
 }
 public com.cpgame.curupira.model.CompleteRoundFact generateWinRange(int minMultiplier,int maxMultiplier){
  com.cpgame.curupira.model.CompleteRoundFact fact=specials.generateWinRange(minMultiplier,maxMultiplier);
  verifier.verifyFact(fact);
  return fact;
 }
 public RoundResult generatePaidRound(BigDecimal lineBet,int level,BigDecimal start,long user,String token){validate(lineBet,level);BigDecimal bet=money(lineBet.multiply(BigDecimal.valueOf((long)level*GameRules.PAYLINE_COUNT)));if(start.compareTo(bet)<0)throw new InsufficientBalanceException();EvaluatedBoard e=generateCompleteRound().deliveries().get(0).evaluatedBoard();BigDecimal win=money(lineBet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(e.multiplierSum()))),change=money(win.subtract(bet));return new RoundResult(ids.next(),1,money(lineBet),level,bet,money(start),change,money(start.add(change)),e.multiplierSum(),win,e,Instant.now(),user,token,true);}
 public RoundResult generateInitialRoomProjection(BigDecimal balance,long user,String token){EvaluatedBoard e=losses.nextLoss();return new RoundResult(0,0,GameRules.MINIMUM_LINE_BET,1,BigDecimal.ZERO.setScale(2),money(balance),BigDecimal.ZERO.setScale(2),money(balance),0,BigDecimal.ZERO.setScale(2),e,Instant.now(),user,token,false);}
 private static void validate(BigDecimal b,int l){if(b==null||b.compareTo(GameRules.MINIMUM_LINE_BET)<0||l<1)throw new IllegalArgumentException("Invalid Curupira bet or level");}
 public static BigDecimal money(BigDecimal v){return v.setScale(2,RoundingMode.HALF_UP);}
 public static final class InsufficientBalanceException extends RuntimeException{public InsufficientBalanceException(){super("Insufficient balance");}}
}
