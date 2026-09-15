package com.cpgame.clubgoddess.core;

import com.cpgame.clubgoddess.core.GameModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Generates one complete paid round, including every awarded Free Spin delivery. */
public final class RoundFactory {
    private static final int MAX_CANDIDATES=200_000;
    private final BoardCandidateGenerator candidates;
    private final RandomGenerator roundKeyRandom;
    private final ZeroLossSupport<List<Integer>> lossBoards;

    public RoundFactory(BoardCandidateGenerator candidates, RandomGenerator roundKeyRandom) {
        this.candidates=Objects.requireNonNull(candidates); this.roundKeyRandom=Objects.requireNonNull(roundKeyRandom);
        var defaults=new RandomBoardCandidateGenerator(new java.security.SecureRandom());
        lossBoards=new ZeroLossSupport<>(defaults::nextLossBoard,b->ResultMath.scatterCount(b)<3&&ResultMath.evaluate(b,new BigDecimal("0.02"),1).isEmpty(),List::copyOf);
    }

    public RoundBundle create(BigDecimal bet,int level,BigDecimal startBalance) {
        GameRuleDefinition.validateBet(bet,level);
        List<Integer> trigger=candidates.nextBoard();
        int scatters=ResultMath.scatterCount(trigger);
        if(scatters>=3) return createSpecialFromTrigger(newRoundKey(),trigger,bet,level,startBalance);
        return build(newRoundKey(),List.of(trigger),bet,level,startBalance,false,true);
    }

    public RoundBundle createOrdinary(BigDecimal bet,int level,BigDecimal startBalance) {
        GameRuleDefinition.validateBet(bet,level);
        for(int i=0;i<MAX_CANDIDATES;i++) {
            List<Integer> board=candidates.nextBoard();
            if(ResultMath.scatterCount(board)<3) return build(newRoundKey(),List.of(board),bet,level,startBalance,false,true);
        }
        throw new IllegalStateException("Unable to generate ordinary round");
    }

    public RoundBundle createSpecial(BigDecimal bet,int level,BigDecimal startBalance) {
        GameRuleDefinition.validateBet(bet,level);
        for(int i=0;i<MAX_CANDIDATES;i++) {
            List<Integer> trigger=candidates.nextBoard();
            int scatters=ResultMath.scatterCount(trigger);
            if(scatters>=3&&scatters<=5) return createSpecialFromTrigger(newRoundKey(),trigger,bet,level,startBalance);
        }
        throw new IllegalStateException("Unable to generate special round");
    }

    private RoundBundle createSpecialFromTrigger(String key,List<Integer> trigger,BigDecimal bet,int level,BigDecimal balance) {
        int award=GameRuleDefinition.awardedFreeSpins(ResultMath.scatterCount(trigger));
        List<List<Integer>> boards=new ArrayList<>();boards.add(trigger);
        int collectedWilds=0;
        for(int step=0;step<award;step++){
            List<Integer> board=candidates.nextFreeBoard(RandomBoardCandidateGenerator.MAX_CUMULATIVE_WILDS-collectedWilds);
            collectedWilds+=(int)board.stream().filter(v->v==GameRuleDefinition.WILD).count();
            boards.add(board);
        }
        return build(key,boards,bet,level,balance,true,true);
    }

    public GameResult createIndependentLoss(BigDecimal bet,int level,BigDecimal startBalance,String oid) {
        GameRuleDefinition.validateBet(bet,level);
        List<Integer> board=lossBoards.generate(candidates::nextLossBoard,roundKeyRandom::nextInt);
        return projectBase(board,bet,level,startBalance,oid,false,0);
    }

    public static RoundBundle rebuildVerifiedRound(String roundKey,List<List<Integer>> boards,BigDecimal bet,int level,BigDecimal startBalance) {
        if(roundKey==null||roundKey.isBlank()||boards==null||boards.isEmpty())throw new IllegalArgumentException("round fact missing");
        boolean special=ResultMath.scatterCount(boards.get(0))>=3;
        return build(roundKey,boards,bet,level,startBalance,special,true);
    }
    public static RoundBundle rebuildVerifiedBaseRound(String roundKey,List<Integer> board,BigDecimal bet,int level,BigDecimal startBalance) {
        return rebuildVerifiedRound(roundKey,List.of(board),bet,level,startBalance);
    }

    private static RoundBundle build(String key,List<List<Integer>> boards,BigDecimal bet,int level,BigDecimal start,boolean special,boolean verify) {
        GameRuleDefinition.validateBet(bet,level);boards.forEach(ResultMath::validateBoardSymbols);
        int scatters=ResultMath.scatterCount(boards.get(0));
        if(special!=(scatters>=3))throw new IllegalArgumentException("special classification mismatch");
        int award=special?GameRuleDefinition.awardedFreeSpins(scatters):0;
        if(boards.size()!=award+1)throw new IllegalArgumentException("complete round delivery count mismatch");
        List<Delivery> deliveries=new ArrayList<>();BigDecimal balance=start;
        GameResult trigger=projectBase(boards.get(0),bet,level,balance,key,special,award);balance=trigger.end_gold();
        deliveries.add(new Delivery(0,trigger,!special));int wilds=0;BigDecimal accumulated=BigDecimal.ZERO;
        for(int i=1;i<boards.size();i++){
            List<Integer> board=boards.get(i);wilds+=(int)board.stream().filter(v->v==GameRuleDefinition.WILD).count();
            int multiplier=GameRuleDefinition.freeMultiplier(wilds);
            List<WinItem>wins=ResultMath.evaluate(board,bet,level).stream().map(w->new WinItem(w.odd(),w.pos_arr(),ResultMath.money(w.tw().multiply(BigDecimal.valueOf(multiplier))),w.ways(),w.wp())).toList();
            BigDecimal total=ResultMath.total(wins);accumulated=ResultMath.money(accumulated.add(total));BigDecimal end=ResultMath.money(balance.add(total));int remaining=award-i;
            Frees frees=new Frees(GameRuleDefinition.stake(bet,level),bet,level,multiplier,wilds,remaining,award,accumulated);
            Props props=new Props(0,multiplier,board,wilds,total,wins);BigDecimal odds=total.divide(GameRuleDefinition.stake(bet,level),14,RoundingMode.HALF_UP).stripTrailingZeros();
            GameResult result=new GameResult(bet,GameRuleDefinition.stake(bet,level),total,end,frees,level,odds,key+"-"+i,props,total.signum()==0?1:2,2,balance,total,2);
            deliveries.add(new Delivery(i,result,remaining==0));balance=end;
        }
        BigDecimal total=deliveries.stream().map(d->d.result().total_win()).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal stake=GameRuleDefinition.stake(bet,level);
        ResultMode mode=special?ResultMode.FREE_SPINS:(total.signum()==0?ResultMode.ORDINARY_PAID_LOSS:ResultMode.ORDINARY_PAID_WIN);
        ResultAnalysis analysis=new ResultAnalysis(mode,ResultMath.money(total),total.divide(stake,14,RoundingMode.HALF_UP).stripTrailingZeros(),true,special,scatters,List.of());
        RoundBundle round=new RoundBundle(key,GameRuleDefinition.RULES_HASH,analysis,deliveries);if(verify)RoundVerifier.verify(round);return round;
    }

    private static GameResult projectBase(List<Integer> board,BigDecimal bet,int level,BigDecimal start,String oid,boolean special,int award){
        List<WinItem>wins=ResultMath.evaluate(board,bet,level);BigDecimal total=ResultMath.total(wins),stake=GameRuleDefinition.stake(bet,level),change=ResultMath.money(total.subtract(stake)),end=ResultMath.money(start.add(change));
        BigDecimal odds=total.divide(stake,14,RoundingMode.HALF_UP).stripTrailingZeros();int scatters=ResultMath.scatterCount(board);
        Frees frees=special?new Frees(stake,bet,level,1,0,award,award,BigDecimal.ZERO):Frees.disabledBaseState();Props props=new Props(scatters,1,board,0,total,wins);
        return new GameResult(bet,stake,change,end,frees,level,odds,oid,props,special?3:(total.signum()==0?1:2),0,start,total,1);
    }
    private String newRoundKey(){return Long.toUnsignedString(Instant.now().toEpochMilli())+String.format("%06d",roundKeyRandom.nextInt(1_000_000));}
}
