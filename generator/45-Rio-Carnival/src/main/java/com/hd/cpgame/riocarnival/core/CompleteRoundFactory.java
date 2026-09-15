package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 从一个付费起点一次性组装至合法终点，后续 Delivery 不再随机生成。 */
public final class CompleteRoundFactory {
    private final RandomBoardCandidateGenerator candidates;
    private final RandomSource random;
    private final ZeroLossSupport<List<String>> lossBoards;

    public CompleteRoundFactory(RandomBoardCandidateGenerator candidates, RandomSource random) {
        if (candidates == null || random == null) throw new IllegalArgumentException("完整局工厂依赖不能为空");
        this.candidates = candidates;
        this.random = random;
        lossBoards=new ZeroLossSupport<>(()->DealingModel.lossBoard(random),b->ResultUtil.scatterCount(b)<3&&ResultUtil.evaluate(b,new BigDecimal("0.02"),1,0).award.signum()==0,b->Collections.unmodifiableList(new ArrayList<String>(b)));
    }

    public GeneratedRound create(BigDecimal betSize, int betLevel) {
        requireBet(betSize, betLevel);
        for (int attempt=0;attempt<10000;attempt++) {
            List<List<String>> boards = new ArrayList<List<String>>();
            List<String> paid = candidates.nextBoard(false,false);boards.add(paid);
            int scatters=ResultUtil.scatterCount(paid),initial=0,multiplier=0;
            int scheduled=0,delivered=0,retriggers=0;boolean reject=false;
            if(scatters>=3) {
                int[] choice=DealingModel.initial(scatters,random);
                initial=scheduled=choice[0];multiplier=choice[1];
                while(delivered<scheduled) {
                    List<String> board=candidates.nextBoard(false,true);boards.add(board);delivered++;
                    int n=ResultUtil.scatterCount(board);
                    if(n>=3){scheduled+=GameRules.scatterAward(n);retriggers++;}
                    if(scheduled+1>DealingModel.MAX_STEPS||retriggers>DealingModel.MAX_RETRIGGERS){reject=true;break;}
                }
            }
            if(reject)continue;
            GeneratedRound round=rebuild(betSize,betLevel,initial,multiplier,boards);
            RoundVerifier.verify(round);
            return round;
        }
        throw new IllegalStateException("No complete round within evidenced generation constraints");
    }

    public static GeneratedRound rebuild(BigDecimal betSize, int betLevel, int initialFreeSpins,
                                         int freeMultiplier, List<List<String>> boards) {
        requireBet(betSize, betLevel);
        if (boards == null || boards.isEmpty()) throw new IllegalArgumentException("完整局事实缺少牌面");
        GeneratedRound round = new GeneratedRound();
        round.roundKey = GameRules.GAME_ID + "-" + UUID.randomUUID().toString();
        round.createdAt = System.currentTimeMillis() / 1000L;
        round.betSize = betSize;
        round.betLevel = betLevel;

        int paidScatters = ResultUtil.scatterCount(boards.get(0));
        boolean freeMode = paidScatters >= 3;
        if (freeMode != (initialFreeSpins > 0) || freeMode != (freeMultiplier > 0))
            throw new IllegalArgumentException("免费局事实与付费牌面不一致");
        int scheduled = initialFreeSpins;
        BigDecimal cumulative = BigDecimal.ZERO;
        SpinStep paid = step(round.totalBet(), scheduled, 1, 0, freeMultiplier, boards.get(0),
            cumulative, 0, 1, betSize, betLevel);
        cumulative = paid.rwa;
        round.steps.add(paid);

        for (int i = 1; i < boards.size(); i++) {
            if (!freeMode) throw new IllegalArgumentException("普通局不能包含后续 Delivery");
            List<String> board = boards.get(i);
            int scatters = ResultUtil.scatterCount(board);
            if (scatters >= 3) scheduled += GameRules.scatterAward(scatters);
            int delivered = i;
            int ss = delivered == scheduled ? 1 : 0;
            SpinStep free = step(BigDecimal.ZERO, scheduled, 2, delivered, freeMultiplier, board,
                cumulative, 2, ss, betSize, betLevel);
            cumulative = free.rwa;
            round.steps.add(free);
        }
        return round;
    }

    private static SpinStep step(BigDecimal ba, int fsn, int gt, int nfsc, int rpx, List<String> board,
                                 BigDecimal previousAward, int smallGameType, int ss,
                                 BigDecimal betSize, int betLevel) {
        WinEvaluation evaluation = CorePayout.evaluate(board, betSize, betLevel, rpx);
        SpinStep step = new SpinStep();
        step.ba = ResultUtil.money(ba);
        step.fsn = fsn;
        step.gt = gt;
        step.nfsc = nfsc;
        step.rpx = rpx;
        step.rskl = Collections.unmodifiableList(new ArrayList<String>(board));
        step.wa = evaluation.award;
        step.rwa = ResultUtil.money(previousAward.add(step.wa));
        step.small_game_type = smallGameType;
        step.ss = ss;
        step.wmkl = evaluation.matches.isEmpty() ? Collections.emptyList() : evaluation.matches;
        return step;
    }

    private static void requireBet(BigDecimal betSize, int betLevel) {
        if (!GameRules.BET_SIZES.contains(betSize) || !GameRules.BET_LEVELS.contains(betLevel))
            throw new IllegalArgumentException("不支持的 bs/bl");
    }

    public GeneratedRound createIndependentLoss(BigDecimal bs,int bl){
        requireBet(bs,bl);List<String> board=lossBoards.generate(()->DealingModel.lossBoard(random),random::nextInt);
        GeneratedRound round=rebuild(bs,bl,0,0,Collections.singletonList(board));RoundVerifier.verify(round);return round;
    }
}
