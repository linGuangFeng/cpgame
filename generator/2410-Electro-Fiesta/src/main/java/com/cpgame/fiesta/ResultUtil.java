package com.cpgame.fiesta;

import java.util.*;

/** 独立结果投影器：候选生成器不向它传入期望模式或倍率。 */
public final class ResultUtil {
    private final GameRuleCore rules;
    public ResultUtil(GameRuleCore rules){ this.rules=Objects.requireNonNull(rules); }
    public record Analysis(RoundOutcome outcome, int payoutUnits, int integerMultiplier, int stepCount, List<Integer> stateElementCountVector) {}
    public Analysis analyze(GameRound round){
        rules.validateRound(round); List<RoundState> states=round.states(); RoundState first=states.get(0), last=states.get(states.size()-1);
        int payout; RoundOutcome outcome;
        switch(first.mode()){
            case NORMAL -> { payout=rules.payoutUnits(last.board()); outcome=payout==0?RoundOutcome.ORDINARY_LOSS:RoundOutcome.ORDINARY_WIN; }
            case RESPIN_UNTIL_WIN -> { payout=rules.payoutUnits(last.board()); if(payout<=0) throw new IllegalArgumentException("respin must terminate in win"); outcome=RoundOutcome.RESPIN_UNTIL_WIN; }
            case MULTIPLIER_STICKY -> { int sum=Arrays.stream(last.multipliers()).sum(); if(sum<=0) throw new IllegalArgumentException("multiplier terminal sum"); payout=Math.multiplyExact(rules.payoutUnits(last.board()),sum); outcome=RoundOutcome.MULTIPLIER_STICKY; }
            default -> throw new IllegalStateException();
        }
        // Redis 的内部整数倍率采用协议天然整数的赔率单位 sum(wa.o)；金额仍由 b*l*payoutUnits 投影。
        // 这样所有正奖严格 >0，且 0 只可能来自无中奖线的自然未中奖局。
        int multiplier=payout;
        List<Integer> vector=states.stream().map(s->(int)Arrays.stream(s.multipliers()).filter(v->v>0).count()).toList();
        return new Analysis(outcome,payout,multiplier,states.size(),vector);
    }
}
