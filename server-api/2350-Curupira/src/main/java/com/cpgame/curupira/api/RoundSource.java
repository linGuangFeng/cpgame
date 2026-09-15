package com.cpgame.curupira.api;

import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;

public interface RoundSource extends AutoCloseable {
    CompleteRoundFact peekLoss();
    CompleteRoundFact claim(Kind kind);
    CompleteRoundFact claim(Kind kind, int minMultiplier, int maxMultiplier);
    CompleteRoundFact claimBuy(int gameType);

    @Override
    default void close() { }
}
