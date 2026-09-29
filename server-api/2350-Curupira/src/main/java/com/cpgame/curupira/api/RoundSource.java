package com.cpgame.curupira.api;

import com.cpgame.curupira.model.CompleteRoundFact;

public interface RoundSource extends AutoCloseable {
    CompleteRoundFact peekLoss();
    CompleteRoundFact claimPaidAtOrBelow(int targetMultiplier);
    CompleteRoundFact claimMaryAtOrBelow(CompleteRoundFact.Kind kind, int targetMultiplier);

    @Override
    default void close() { }
}
