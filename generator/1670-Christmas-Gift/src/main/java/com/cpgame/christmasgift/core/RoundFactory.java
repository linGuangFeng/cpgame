package com.cpgame.christmasgift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds one complete round exactly once; no runtime continuation is possible. */
public final class RoundFactory {
    private final GenerationModel model;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;

    public RoundFactory(GenerationModel model) { this.model = model;losses=new ZeroLossSupport<>(this::lossCandidate,r->ResultUtil.multiplier(r)==0,r->r); }

    public GameRuleCore.CompleteRound ordinary() {
        LinkedHashMap<Integer, Integer> board = new LinkedHashMap<>();
        int previous = 0;
        for (int position = 1; position <= 9; position++) {
            int symbol = model.ordinarySymbol(position, previous);
            board.put(position, symbol);
            previous = symbol;
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY, 0,
            List.of(new GameRuleCore.Deal(board)));
    }

    public GameRuleCore.CompleteRound christmasGiftFeature() {
        int target = model.featureTarget();
        boolean fullScreen = model.featureIsFullScreen();
        int stepCount = fullScreen ? 5 : model.featureStepCount();
        List<Integer> remaining = model.positions();
        model.shuffle(remaining);
        List<GameRuleCore.Deal> steps = new ArrayList<>();

        int initial = model.initialStateSize();
        if (fullScreen) initial = Math.min(4, Math.max(2, initial));
        initial = Math.min(initial, 9 - (fullScreen ? stepCount - 1 : stepCount));
        initial = Math.max(2, initial);
        steps.add(positiveDeal(remaining, initial, target));

        int positiveStepsRemaining = fullScreen ? stepCount - 1 : stepCount - 2;
        for (int step = 0; step < positiveStepsRemaining; step++) {
            int after = positiveStepsRemaining - step - 1;
            int count = model.boundedIncrement(model.nextIncrementSize(), remaining.size(), after, !fullScreen);
            steps.add(positiveDeal(remaining, count, target));
        }
        if (!fullScreen) {
            LinkedHashMap<Integer, Integer> sentinel = new LinkedHashMap<>();
            sentinel.put(model.choose(remaining), 0);
            steps.add(new GameRuleCore.Deal(sentinel));
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.CHRISTMAS_GIFT_FEATURE, target, steps);
    }

    private GameRuleCore.Deal positiveDeal(List<Integer> remaining, int count, int target) {
        LinkedHashMap<Integer, Integer> dealt = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int position = remaining.remove(remaining.size() - 1);
            dealt.put(position, model.featureSymbolIsWild() ? GameRuleCore.WILD : target);
        }
        return new GameRuleCore.Deal(dealt);
    }

    public GameRuleCore.CompleteRound ordinaryLoss(){return losses.generate(this::lossCandidate,model::randomIndex);}
    public GameRuleCore.CompleteRound lossCandidate(){
        LinkedHashMap<Integer,Integer> board=new LinkedHashMap<>();boolean[] first=new boolean[8],none=new boolean[8];int prev=0;
        for(int pos=1;pos<=9;pos++){
            int symbol=model.lossSymbol(pos,prev,pos>=4&&pos<=6?first:none);
            board.put(pos,symbol);if(pos<=3)first[symbol]=true;prev=symbol;
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY,0,List.of(new GameRuleCore.Deal(board)));
    }
}
