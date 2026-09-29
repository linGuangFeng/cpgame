package com.cpgame.luckywheel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 零奖独立填充；中奖从该档 &lt;=1000 的中奖形表抽取，空白落盘时随机填。
 */
public final class RealtimeRoundGenerator {
    private final RandomGenerator random;

    public RealtimeRoundGenerator(RandomGenerator random) {
        this.random = random;
    }

    public RoundFacts generate(RoundRequest request) {
        int bl = request.betLevel();
        if (random.nextInt(100) < 40) {
            return loss(BetUnlock.profile(bl), BetUnlock.cells(bl));
        }
        return LuckyWheelMultiplierCatalog.pickAnyWin(bl, random);
    }

    public RoundFacts generate(RoundRequest request, int requestedRatio) {
        int floored = LuckyWheelMultiplierCatalog.floorOdd(request.betLevel(), requestedRatio);
        if (floored <= 0) {
            return loss(BetUnlock.profile(request.betLevel()), BetUnlock.cells(request.betLevel()));
        }
        return LuckyWheelMultiplierCatalog.pick(request.betLevel(), requestedRatio, random);
    }

    private RoundFacts loss(int profile, int cells) {
        List<String> board = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) board.add(random.nextBoolean() ? "H0" : "H1");
        return RoundFacts.ordinary(profile, board);
    }
}
