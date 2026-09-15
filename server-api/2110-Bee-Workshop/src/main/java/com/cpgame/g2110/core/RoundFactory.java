package com.cpgame.g2110.core;
import java.security.SecureRandom;
import java.util.*;

/** New complete-round simulation using aggregate column models, never source responses. */
public final class RoundFactory {
    private final GameRuleCore rules;
    private final Random random;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;
    private final EmpiricalDealModel model=EmpiricalDealModel.shared();
    private long attemptedCandidates;
    private final Map<Integer,Integer> selectedFreeScatters=new TreeMap<>();
    public RoundFactory(GameRuleCore rules){this(rules,new SecureRandom());}
    public RoundFactory(GameRuleCore rules,Random random){this.rules=rules;this.random=random;losses=new ZeroLossSupport<>(this::lossCandidate,r->rules.payoutUnits(r.steps().get(0).board())==0,r->r);}
    public long attemptedCandidates(){return attemptedCandidates;}
    public Map<Integer,Integer> selectedFreeScatterCounts(){return Map.copyOf(selectedFreeScatters);}

    public GameRuleCore.CompleteRound generate(GameRuleCore.RoundKind kind){
        if(kind==GameRuleCore.RoundKind.ORDINARY_LOSS)return losses.generate(this::lossCandidate,random::nextInt);
        // Choose the source-weighted entry once; retries must not bias shorter free awards.
        int selectedScatters=kind==GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS?model.triggerScatterCount(random):0;
        if(selectedScatters>0)selectedFreeScatters.merge(selectedScatters,1,Integer::sum);
        EmpiricalDealModel.Overlay selectedOverlay=kind==GameRuleCore.RoundKind.MYSTERY_BOX?model.mystery(random):null;
        for(int attempt=0;attempt<50000;attempt++){
            attemptedCandidates++;
            try{
            var round=switch(kind){
                case ORDINARY_LOSS->ordinary(false);
                case ORDINARY_WIN->ordinary(true);
                case MYSTERY_BOX->mystery(selectedOverlay);
                case FREE_STICKY_SYMBOLS->freeRound(selectedScatters);
            };
            if(round==null)continue;
            rules.validate(round);return round;}
            catch(IllegalArgumentException rejected){/* Reject the whole candidate; never patch winning cells. */}
        }
        throw new IllegalStateException("Empirical model exhausted for "+kind+"; no fallback is permitted");
    }
    private GameRuleCore.CompleteRound ordinary(boolean win){
        int[] board=model.board("ORDINARY",0,true,random);
        if(board==null||win!=(rules.payoutUnits(board)>0))return null;
        return new GameRuleCore.CompleteRound(win?GameRuleCore.RoundKind.ORDINARY_WIN:GameRuleCore.RoundKind.ORDINARY_LOSS,
                List.of(new GameRuleCore.Step(board,List.of())));
    }
    private GameRuleCore.CompleteRound mystery(EmpiricalDealModel.Overlay overlay){
        int mask=0;
        for(int p:overlay.positions())mask|=1<<p;
        int[] board=model.board("MYSTERY_BOX",mask,true,random);
        if(board==null)return null;
        for(int p:overlay.positions())board[p]=overlay.symbol();
        return new GameRuleCore.CompleteRound(GameRuleCore.RoundKind.MYSTERY_BOX,
                List.of(new GameRuleCore.Step(board,overlay.positions())));
    }
    private GameRuleCore.CompleteRound freeRound(int wantedScatters){
        int[] trigger=model.board("FREE_TRIGGER",0,true,random);
        if(trigger==null||rules.scatterCount(trigger)!=wantedScatters)return null;
        rules.validateDealtStep("FREE_TRIGGER",new GameRuleCore.Step(trigger,List.of()));
        int award=rules.awardedFreeSpins(wantedScatters);
        List<GameRuleCore.Step> steps=new ArrayList<>();
        steps.add(new GameRuleCore.Step(trigger,List.of()));
        int stickyMask=1<<7;
        for(int spin=0;spin<award;spin++){
            String entry=spin==0?"FREE_INITIAL":"FREE_CONTINUATION";
            int[] board=model.board(entry,stickyMask,false,random);
            if(board==null)return null;
            int target=model.target(entry,random);
            int previousMask=stickyMask;
            for(int p=0;p<15;p++)if((stickyMask&(1<<p))!=0)board[p]=target;
            // Original help Game2110_18: all matching cells become sticky.
            List<Integer> positions=new ArrayList<>();
            for(int p=0;p<15;p++)if(board[p]==target){stickyMask|=1<<p;positions.add(p);}
            var step=new GameRuleCore.Step(board,positions);
            rules.validateDealtStep(spin==award-1?"FREE_TERMINAL":entry,step);
            rules.validateStickyTransition(previousMask,step,spin==0);
            steps.add(step);
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS,steps);
    }

    public GameRuleCore.CompleteRound lossCandidate(){
        var round=new GameRuleCore.CompleteRound(GameRuleCore.RoundKind.ORDINARY_LOSS,List.of(new GameRuleCore.Step(model.lossBoard(random),List.of())));
        rules.validate(round);return round;
    }
}
