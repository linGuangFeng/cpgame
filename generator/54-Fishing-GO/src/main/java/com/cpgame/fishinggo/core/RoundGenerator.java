package com.cpgame.fishinggo.core;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class RoundGenerator {
    private final SecureRandom random;
    private final ResultUtil util = new ResultUtil();
    private final DealingModel model = new DealingModel();
    private final ZeroLossSupport<List<String>> losses;

    public RoundGenerator(SecureRandom random) { this.random = random;losses=new ZeroLossSupport<>(()->model.lossBoard(random),b->util.scatter(b)<5&&util.evaluate(b,1).payout().signum()==0,List::copyOf); }

    public CompleteRound loss(){
        List<String> board=losses.generate(()->model.lossBoard(random),random::nextInt);
        return ordinary(board,util.evaluate(board,1));
    }
    public List<String> lossBoardCandidate(){return model.lossBoard(random);}

    public CompleteRound win() {
        for (int i = 0; i < 200000; i++) {
            try {
                List<String> board = model.paidBoard(random);
                if (util.scatter(board) >= 5) continue;
                ResultUtil.Win w = util.evaluate(board, 1);
                if (w.payout().signum() <= 0) continue;
                return ordinary(board, w);
            } catch (DealingModel.Rejected ignored) { }
        }
        throw new IllegalStateException("win exhausted");
    }

    public CompleteRound special() { return special(model.pickEntryScatter(random)); }

    public CompleteRound special(int sc) {
        for (int i = 0; i < 200000; i++) {
            try {
                List<String> entry = model.scatterEntry(random, sc);
                if (util.scatter(entry) != sc) continue;
                List<List<String>> boards = new ArrayList<>();
                boards.add(entry);
                boolean ok = true;
                for (int f = 0; f < ProtocolConstants.FREE_SPINS; f++) {
                    List<String> board = model.freeBoard(random);
                    if (util.scatter(board) >= 5) { ok = false; break; }
                    boards.add(board);
                }
                if (!ok) continue;
                return rebuild(boards);
            } catch (DealingModel.Rejected ignored) { }
        }
        throw new IllegalStateException("special exhausted");
    }

    public CompleteRound natural() {
        int ticket = random.nextInt(1241);
        if (ticket < 41) return special();
        if (ticket < 41 + 185) return win();
        return loss();
    }

    public CompleteRound rebuild(List<List<String>> boards) {
        if (boards.size() == 1) {
            util.requireCaps(boards.get(0), false);
            return ordinary(boards.get(0), util.evaluate(boards.get(0), 1));
        }
        if (boards.size() != 13) throw new IllegalArgumentException("free round 13 steps");
        util.requireCaps(boards.get(0), false);
        int sc = util.scatter(boards.get(0));
        if (sc < 5 || sc > 7) throw new IllegalArgumentException("entry scatter");
        for (int i = 1; i < boards.size(); i++) {
            util.requireCaps(boards.get(i), true);
            if (util.scatter(boards.get(i)) >= 5) throw new IllegalArgumentException("retrigger disabled");
        }
        int apx = sc - 4;
        List<CompleteRound.Step> steps = new ArrayList<>();
        BigDecimal cum = BigDecimal.ZERO;
        ResultUtil.Win entry = util.evaluate(boards.get(0), 1);
        cum = cum.add(entry.payout());
        steps.add(step(boards.get(0), 1, 1, 12, 0, 1, 0, 1, ProtocolConstants.MIN_TOTAL_BET, entry.payout(), cum));
        for (int n = 1; n <= 12; n++) {
            int rpx = apx * n;
            ResultUtil.Win w = util.evaluate(boards.get(n), rpx);
            cum = cum.add(w.payout());
            steps.add(step(boards.get(n), rpx, apx, 12, n, 2, 2, n == 12 ? 0 : 1, BigDecimal.ZERO, w.payout(), cum));
        }
        CompleteRound round = new CompleteRound(steps);
        util.analyze(round);
        return round;
    }

    private CompleteRound ordinary(List<String> board, ResultUtil.Win win) {
        CompleteRound.Step step = step(board, 1, 1, 0, 0, 1, 0, 1,
                ProtocolConstants.MIN_TOTAL_BET, win.payout(), win.payout());
        CompleteRound round = new CompleteRound(List.of(step));
        util.analyze(round);
        return round;
    }

    private CompleteRound.Step step(List<String> board, int rpx, int apx, int fsn, int nfsc, int gt, int sgt, int ss,
                                    BigDecimal ba, BigDecimal wa, BigDecimal rwa) {
        return new CompleteRound.Step(board, rpx, apx, fsn, nfsc, gt, sgt, ss,
                ba.stripTrailingZeros(), wa.stripTrailingZeros(), rwa.stripTrailingZeros());
    }
}
