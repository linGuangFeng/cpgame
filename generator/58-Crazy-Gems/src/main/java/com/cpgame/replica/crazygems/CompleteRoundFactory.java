package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsResultUtil;

import java.util.Random;

/** One paid start through terminal: a single spin. No cascade, no free, no buy. */
public final class CompleteRoundFactory {
    private final int[] symbolWeights;
    private final int[] ordinaryRpxWeights;
    private final int[] specialRpxWeights;

    public CompleteRoundFactory() {
        this(CrazyGemsBoardGenerator.defaultSymbolWeights(), CrazyGemsBoardGenerator.defaultRpxWeights());
    }

    public CompleteRoundFactory(int[] symbolWeights, int[] rpxWeights) {
        // Validate before opening Redis (and before any clear-existing operation).
        new CrazyGemsBoardGenerator(new Random(0), symbolWeights, rpxWeights);
        this.symbolWeights = symbolWeights.clone();
        this.ordinaryRpxWeights = rpxWeights.clone();
        this.specialRpxWeights = CrazyGemsBoardGenerator.specialEntryRpxWeights(rpxWeights);
    }

    public GeneratedRound generate(Random random, boolean specialEntry) {
        return generate(random, specialEntry, false);
    }

    public GeneratedRound generate(Random random, boolean specialEntry, boolean forceLoss) {
        if (random == null) throw new IllegalArgumentException("random is required");
        int[] rpxWeights = specialEntry ? specialRpxWeights : ordinaryRpxWeights;
        CrazyGemsBoardGenerator boards = new CrazyGemsBoardGenerator(
                random, symbolWeights, rpxWeights);
        CrazyGemsBoard board = forceLoss
                ? new CrazyGemsIndependentLossGenerator().generate(random)
                : boards.generate();
        if (forceLoss) {
            board = new CrazyGemsBoard(board.rskl(), boards.nextRpx());
        }
        CrazyGemsEvaluation evaluation = CrazyGemsResultUtil.evaluate(board);
        if (forceLoss && !evaluation.loss()) {
            throw new IllegalStateException("forced loss was not 0x");
        }
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION, board.rskl(), board.rpx());
        String member = new CompleteRoundCodec().encode(fact);
        CrazyGemsEvaluation verified = new CompleteRoundCodec().verify(member);
        if (verified.multiplierDeci() != evaluation.multiplierDeci()) {
            throw new IllegalStateException("codec multiplier mismatch");
        }
        return new GeneratedRound(fact, evaluation, member);
    }

    public record GeneratedRound(CompleteRoundFact fact, CrazyGemsEvaluation evaluation, String member) {
        public boolean special() { return fact.board().minecartSpecial(); }
        public int multiplierDeci() { return evaluation.multiplierDeci(); }
    }
}
