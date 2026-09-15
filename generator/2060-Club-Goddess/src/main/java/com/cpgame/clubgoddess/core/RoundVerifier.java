package com.cpgame.clubgoddess.core;

import com.cpgame.clubgoddess.core.GameModels.*;
import java.math.BigDecimal;

/** Independently verifies all steps and the paid-round boundary. */
public final class RoundVerifier {
    private RoundVerifier(){}
    public static ResultAnalysis verify(RoundBundle round){
        if(round==null||!GameRuleCore.RULES_HASH.equals(round.rulesHash()))throw new IllegalArgumentException("rulesHash mismatch");
        BigDecimal total=BigDecimal.ZERO,previousEnd=null,acc=BigDecimal.ZERO;int wilds=0,award=0;
        for(int i=0;i<round.deliveries().size();i++){Delivery d=round.deliveries().get(i);GameResult r=d.result();if(d.deliveryIndex()!=i||d.terminal()!=(i==round.deliveries().size()-1))throw new IllegalArgumentException("delivery boundary");ResultUtil.analyze(r);
            if(i==0){
                if(!round.roundKey().equals(r.oid())||r.type()!=1||r.small_game_type()!=0)throw new IllegalArgumentException("paid start identity");
                int scatters=(int)r.props().prop().stream().filter(v->v==9).count();
                award=switch(scatters){case 3->12;case 4->15;case 5->20;default->0;};
                if(r.frees().tt()!=award||r.frees().st()!=award||r.props().m()!=1||r.props().spe_num()!=0||r.frees().spe_num()!=0||!same(r.frees().twa(),BigDecimal.ZERO))throw new IllegalArgumentException("trigger state");
                if(r.frees().m()!=(award>0?1:2))throw new IllegalArgumentException("trigger multiplier");
            }
            else{if(r.type()!=2||r.small_game_type()!=2||!same(previousEnd,r.start_gold()))throw new IllegalArgumentException("free continuity");wilds+=(int)r.props().prop().stream().filter(v->v==10).count();if(r.frees().spe_num()!=wilds||r.props().spe_num()!=wilds||r.frees().m()!=Math.min(20,2+2*(wilds/3))||r.props().m()!=r.frees().m())throw new IllegalArgumentException("free multiplier progression");if(wilds>15)throw new IllegalArgumentException("observed cumulative Wild ceiling 15");if(r.frees().st()!=award-i)throw new IllegalArgumentException("free countdown");acc=acc.add(r.total_win());if(!same(acc,r.frees().twa()))throw new IllegalArgumentException("free accumulator");}
            if(i>0){GameResult first=round.deliveries().get(0).result();if(r.frees().tt()!=award||r.level()!=first.level()||!same(r.bet(),first.bet())||!same(r.frees().ba(),first.bet_gold())||!same(r.frees().bet(),first.bet())||r.frees().l()!=first.level())throw new IllegalArgumentException("free bet metadata");}
            previousEnd=r.end_gold();total=total.add(r.total_win());}
        boolean special=round.deliveries().size()>1;if(special&&round.deliveries().size()!=award+1)throw new IllegalArgumentException("incomplete free round");if(!special&&award!=0)throw new IllegalArgumentException("missing free steps");if(!same(total,round.analysis().totalWin()))throw new IllegalArgumentException("round total");ResultUtil.integerMultiplier(round);
        ResultMode expected=special?ResultMode.FREE_SPINS:(total.signum()==0?ResultMode.ORDINARY_PAID_LOSS:ResultMode.ORDINARY_PAID_WIN);if(round.analysis().mode()!=expected||!round.analysis().terminal())throw new IllegalArgumentException("analysis mismatch");return round.analysis();
    }
    private static boolean same(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}
}
