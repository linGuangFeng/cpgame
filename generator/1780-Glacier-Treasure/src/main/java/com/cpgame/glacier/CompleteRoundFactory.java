package com.cpgame.glacier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class CompleteRoundFactory {
    public enum Outcome { LOSS, WIN, SPECIAL }
    private final GenerationModel model;
    private final int maxConsecutiveWins;
    private final int maxMarySpins;
    private final int lossAttempts;

    public CompleteRoundFactory() {
        this(new GenerationModel(), 14, 16, 200);
    }

    public CompleteRoundFactory(GenerationModel model, int maxConsecutiveWins, int maxMarySpins, int lossAttempts) {
        this.model = model;
        this.maxConsecutiveWins = maxConsecutiveWins;
        this.maxMarySpins = maxMarySpins;
        this.lossAttempts = lossAttempts;
    }

    public static final class GeneratedRound {
        public final CompleteRoundFact fact;
        public final int ratio;
        public final int maxPages;
        public GeneratedRound(CompleteRoundFact fact, int ratio, int maxPages) {
            this.fact=fact; this.ratio=ratio; this.maxPages=maxPages;
        }
        public boolean special() { return fact.special(); }
    }

    public static final class RoundRejectedException extends RuntimeException {
        public RoundRejectedException(String m) { super(m); }
    }

    public GeneratedRound generate(Random random, boolean featureBuy) {
        Outcome target = featureBuy ? Outcome.SPECIAL : sample(random);
        if(target==Outcome.LOSS)return generateTarget(random,featureBuy,target);
        for (int i=0;i<2500;i++) {
            try { return generateTarget(random, featureBuy, target); }
            catch (RoundRejectedException ignored) {}
        }
        throw new RoundRejectedException("cannot sample "+target);
    }

    public GeneratedRound generateTarget(Random random, boolean featureBuy, Outcome target) {
        BoardFactory boards = new BoardFactory(random, model);
        GameRuleCore core = new GameRuleCore();
        List<List<GameRuleCore.Board>> spins = new ArrayList<>();
        if (target==Outcome.LOSS) {
            GameRuleCore.Board lossBoard = new IndependentLossGenerator(random, model, lossAttempts).generate();
            var eval=core.evaluate(lossBoard, BigDecimal.ONE, 1);
            if (!eval.terminal() || core.initialFreeAward(core.scatterSymbolCount(lossBoard))>0)
                throw new RoundRejectedException("not loss");
            CompleteRoundFact fact=new CompleteRoundFact(false, List.of(List.of(lossBoard)));
            String encoded=new CompleteRoundCodec().encode(fact);
            if (verifyRatio(new CompleteRoundCodec().decode(encoded, false))!=0)
                throw new RoundRejectedException("loss codec");
            return new GeneratedRound(fact, 0, 1);
        }
        GameRuleCore.Board paidStart = featureBuy || target==Outcome.SPECIAL
            ? boards.featureTrigger() : boards.initial(false, false);
        Spin paid = spin(boards, core, paidStart, false, 1);
        if (paid.pages.size() > Math.min(model.maxCascadePages, maxConsecutiveWins))
            throw new RoundRejectedException("cascade cap");
        int award = core.initialFreeAward(core.scatterSymbolCount(paid.pages.get(paid.pages.size()-1)));
        boolean special = award>0;
        if (target==Outcome.WIN) {
            if (paid.ratio<=0 || special) throw new RoundRejectedException("not win");
        } else if (!special) throw new RoundRejectedException("not special");
        spins.add(paid.pages);
        int total = paid.ratio;
        int maxPages = paid.pages.size();
        int freeMult = 2;
        int remaining = award;
        int freeTotal = award;
        if (freeTotal > maxMarySpins || freeTotal > model.maxFreeSpins) throw new RoundRejectedException("free cap");
        while (remaining>0) {
            GameRuleCore.Board start = boards.initial(true, false);
            Spin free = spin(boards, core, start, true, freeMult);
            if (free.pages.size() > Math.min(model.maxCascadePages, maxConsecutiveWins))
                throw new RoundRejectedException("free cascade cap");
            int extra = core.initialFreeAward(core.scatterSymbolCount(free.pages.get(free.pages.size()-1)));
            if (extra>0) throw new RoundRejectedException("unverified retrigger");
            spins.add(free.pages);
            total = Math.addExact(total, free.ratio);
            maxPages = Math.max(maxPages, free.pages.size());
            freeMult = free.endingMultiplier;
            remaining--;
        }
        CompleteRoundFact fact = new CompleteRoundFact(featureBuy, spins);
        String encoded = new CompleteRoundCodec().encode(fact);
        CompleteRoundFact roundTrip = new CompleteRoundCodec().decode(encoded, featureBuy);
        int verified = verifyRatio(roundTrip);
        if (verified!=total) throw new RoundRejectedException("codec ratio mismatch");
        return new GeneratedRound(fact, total, maxPages);
    }

    private static Outcome sample(Random random) {
        int v=random.nextInt(100);
        if (v<62) return Outcome.LOSS;
        if (v<90) return Outcome.WIN;
        return Outcome.SPECIAL;
    }

    private record Spin(List<GameRuleCore.Board> pages, int ratio, int endingMultiplier) {}

    private Spin spin(BoardFactory boards, GameRuleCore core, GameRuleCore.Board start,
                      boolean free, int startMult) {
        var pages=new ArrayList<GameRuleCore.Board>();
        GameRuleCore.Board board=start;
        int mult=startMult;
        int ratio=0;
        ResultUtil oracle=new ResultUtil();
        var table=ResultUtil.paytable();
        for (int n=0;n<Math.min(model.maxCascadePages, maxConsecutiveWins);n++) {
            var eval=core.evaluate(board, BigDecimal.ONE, mult);
            oracle.verify(eval, oracle.evaluate(board, BigDecimal.ONE, mult, table));
            pages.add(board);
            ratio=Math.addExact(ratio, eval.unitProduct());
            if (eval.terminal()) return new Spin(List.copyOf(pages), ratio, mult);
            board=boards.cascadeLegal(board, eval, free);
            List<String> errors=oracle.verifyCascade(pages.get(pages.size()-1), board, eval.winningIds());
            if (!errors.isEmpty()) throw new RoundRejectedException(errors.get(0));
            mult=core.nextMultiplier(mult, free, true);
        }
        throw new RoundRejectedException("unterminated cascade");
    }

    static int verifyRatio(CompleteRoundFact fact) {
        GameRuleCore core=new GameRuleCore();
        ResultUtil oracle=new ResultUtil();
        var table=ResultUtil.paytable();
        int total=0;
        for (int si=0;si<fact.spins().size();si++) {
            boolean free=si>0;
            int mult=free?2:1;
            GameRuleCore.Board prev=null;
            var prevWin=java.util.Set.<Integer>of();
            for (var board:fact.spins().get(si)) {
                var eval=core.evaluate(board, BigDecimal.ONE, mult);
                oracle.verify(eval, oracle.evaluate(board, BigDecimal.ONE, mult, table));
                if (prev!=null) {
                    var err=oracle.verifyCascade(prev, board, prevWin);
                    if (!err.isEmpty()) throw new RoundRejectedException(err.get(0));
                }
                total=Math.addExact(total, eval.unitProduct());
                boolean won=!eval.terminal();
                prev=board; prevWin=eval.winningIds();
                if (won) mult=core.nextMultiplier(mult, free, true);
            }
        }
        return total;
    }
}
