package com.hd.cpgame.jungleparty;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Constructed 0-win board. Does not load generation-model-v37.tsv. */
final class IndependentLoss {
    private IndependentLoss() {}

    static GameRuleCore.Board board(SecureRandom random) {
        return POOL.generate(() -> candidate(random), random::nextInt);
    }

    static GameRuleCore.Board candidate(SecureRandom random) {
        GameRuleCore.Symbol[] pays = {
                GameRuleCore.Symbol.N9, GameRuleCore.Symbol.A, GameRuleCore.Symbol.H1, GameRuleCore.Symbol.H2,
                GameRuleCore.Symbol.H3, GameRuleCore.Symbol.H4, GameRuleCore.Symbol.H5, GameRuleCore.Symbol.J,
                GameRuleCore.Symbol.K, GameRuleCore.Symbol.Q, GameRuleCore.Symbol.T};
        List<GameRuleCore.Symbol> shuffled = new ArrayList<>(List.of(pays));
        Collections.shuffle(shuffled, random);
        List<GameRuleCore.Symbol> first = shuffled.subList(0, 5);
        List<GameRuleCore.Symbol> second = shuffled.subList(5, shuffled.size());
        GameRuleCore.Symbol[] cells = new GameRuleCore.Symbol[15];
        for (int row = 0; row < 3; row++) {
            cells[row] = first.get(random.nextInt(first.size()));
            cells[3 + row] = second.get(random.nextInt(second.size()));
            for (int reel = 2; reel < 5; reel++) cells[reel * 3 + row] = pays[random.nextInt(pays.length)];
        }
        return new GameRuleCore.Board(cells);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZeroLossSupport<GameRuleCore.Board> POOL = new ZeroLossSupport<>(
            () -> candidate(RANDOM),
            board -> java.util.Arrays.stream(board.cells()).filter(v -> v == GameRuleCore.Symbol.Scat).count() < 3
                    && GameRuleCore.evaluate(board, 1, new java.math.BigDecimal("0.02"), 0).award().signum() == 0,
            board -> board);
}
