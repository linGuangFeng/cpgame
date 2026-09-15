package com.cpgame.sharpshooter.core;

import java.security.SecureRandom;
import java.util.*;

/** New complete rounds from fitted dependent entries; no original round is replayed. */
public final class RoundGenerator {
    private final SecureRandom random;
    private final GameRuleCore rules;
    private final ZeroLossSupport<CompleteRound> losses;
    private final DealingModel model=new DealingModel();
    public RoundGenerator(SecureRandom random,GameRuleCore rules){
        this.random=Objects.requireNonNull(random);this.rules=Objects.requireNonNull(rules);
        losses=new ZeroLossSupport<>(this::lossCandidate,r->new ResultUtil(rules).analyze(r).outcome()==ResultUtil.Outcome.NORMAL_LOSS,r->r);
    }
    public CompleteRound ordinary(boolean win){
        if(!win)return losses.generate(this::lossCandidate,random::nextInt);
        CompleteRound.Spin spin=dealSpin(true,0,0,false,win);
        return checked(new CompleteRound(List.of(spin)));
    }
    public CompleteRound freeSpins(){
        CompleteRound.Spin trigger=dealSpin(true,0,0,true,false);
        int total=trigger.freeTotal();
        List<CompleteRound.Spin> spins=new ArrayList<>();spins.add(trigger);
        for(int remaining=total-1;remaining>=0;remaining--)
            spins.add(dealSpin(false,total,remaining,false,model.freeWin(random)));
        return checked(new CompleteRound(spins));
    }
    private CompleteRound.Spin dealSpin(boolean paid,int total,int remaining,boolean trigger,boolean win){
        String profile=trigger?"paid_trigger":(paid?"paid":"free")+(win?"_win":"_loss");
        DealingModel.Target target=model.target(profile,random);
        for(int attempt=0;attempt<100000;attempt++){
            DealingModel.Deal initial=model.initial(profile,random);
            int count=rules.scatterCount(initial.symbols());
            if(trigger?(count<3||count>4):count>=3)continue;
            if(!trigger&&(!rules.evaluate(initial.symbols()).isEmpty())!=win)continue;
            int awarded=trigger?rules.awardedFreeSpins(count):0;
            try{
                List<CompleteRound.Cascade> pages=pages(initial,!paid,target);
                return new CompleteRound.Spin(paid,trigger?awarded:total,trigger?awarded:remaining,awarded,pages);
            }catch(DealingModel.RejectedDeal rejected){
                // Reject the whole proposed spin; never modify retained cells to force termination.
            }
        }
        throw new IllegalStateException("empirical model exhausted legal proposals for "+profile);
    }
    private List<CompleteRound.Cascade> pages(DealingModel.Deal initial,boolean free,DealingModel.Target target){
        List<CompleteRound.Cascade> pages=new ArrayList<>();
        CompleteRound.Cascade current=new CompleteRound.Cascade(initial.symbols(),initial.gold());
        int total=0;
        for(int index=0;index<target.pages();index++){
            model.validatePage(current.symbols(),current.gold(),free,index);
            int award=rules.payoutUnits(current.symbols(),index,free);
            if((award>0)!=(index<target.pages()-1))throw new DealingModel.RejectedDeal("conditional cascade boundary");
            total+=award;
            if(total>target.maximumUnits()||(index>=target.pages()-2&&total<target.minimumUnits()))
                throw new DealingModel.RejectedDeal("conditional payout range");
            pages.add(current);
            if(index==target.pages()-1)return List.copyOf(pages);
            CompleteRound.Cascade selected=null;
            for(int proposal=0;proposal<1000;proposal++){
                CompleteRound.Cascade candidate=refill(current,free);
                try{model.validatePage(candidate.symbols(),candidate.gold(),free,index+1);}
                catch(DealingModel.RejectedDeal rejected){continue;}
                int nextAward=rules.payoutUnits(candidate.symbols(),index+1,free);
                if((nextAward>0)!=(index+1<target.pages()-1))continue;
                int sum=total+nextAward;
                if(sum>target.maximumUnits()||(index+1>=target.pages()-2&&sum<target.minimumUnits()))continue;
                selected=candidate;break;
            }
            if(selected==null)throw new DealingModel.RejectedDeal("no legal conditional refill proposal");
            current=selected;
        }
        throw new AssertionError();
    }
    private CompleteRound.Cascade refill(CompleteRound.Cascade current,boolean free){
        int[] board=current.symbols();boolean[] gold=current.gold(),winning=rules.winningPositions(board);
        int[] next=new int[20];boolean[] nextGold=new boolean[20];
        for(int col=0;col<5;col++){
            int kept=0;
            for(int row=0;row<4;row++){
                int pos=col*4+row;
                if(!winning[pos]||gold[pos]){
                    next[col*4+kept]=winning[pos]?GameRuleCore.WILD:board[pos];
                    nextGold[col*4+kept]=!winning[pos]&&gold[pos];kept++;
                }
            }
            int[] fresh=model.refill(free,col,4-kept,random);
            for(int row=0;row<fresh.length;row++)next[col*4+kept+row]=fresh[row];
        }
        return new CompleteRound.Cascade(next,nextGold);
    }
    private CompleteRound checked(CompleteRound round){rules.validateRound(round);return round;}

    public CompleteRound lossCandidate(){
        DealingModel.Deal deal=model.initial("paid_loss",random);
        int[] b=deal.symbols();boolean[] gold=deal.gold();int scatter=0;
        for(int i=0;i<b.length;i++)if(b[i]==10||(b[i]==9&&++scatter>=3)){b[i]=1+random.nextInt(8);gold[i]=false;}
        boolean[] first=new boolean[9];for(int i=0;i<4;i++)if(b[i]<=8)first[b[i]]=true;
        int[] allowed=new int[8];int n=0;for(int v=1;v<=8;v++)if(!first[v])allowed[n++]=v;
        for(int i=4;i<8;i++)if(b[i]<=8&&first[b[i]])b[i]=allowed[random.nextInt(n)];
        model.validatePage(b,gold,false,0);
        return checked(new CompleteRound(List.of(new CompleteRound.Spin(true,0,0,0,
                List.of(new CompleteRound.Cascade(b,gold))))));
    }
}
