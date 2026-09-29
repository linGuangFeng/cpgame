package com.cpgame.luckydragon.api;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.MinimalFactCodec;
import com.cpgame.luckydragon.core.RandomRoundGenerator;
import com.cpgame.luckydragon.core.RoundFacts;
import com.cpgame.luckydragon.core.RoundRequest;
import com.cpgame.luckydragon.core.SpinResult;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Properties;

/** Demo realtime provider: floor requested odd, one catalog combo, no Redis cache. */
final class RealtimeRoundStore implements LuckyDragonService.RoundProvider {
    private final GameRuleCore rules = new GameRuleCore();
    private final IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
    private final MinimalFactCodec codec = new MinimalFactCodec();
    private final RandomRoundGenerator generator;
    private final SecureRandom random = new SecureRandom();

    private RealtimeRoundStore(RandomRoundGenerator generator) {
        this.generator = generator;
    }

    static RealtimeRoundStore create(Properties config) {
        String model = config.getProperty("generation.joint-state-model", "").trim();
        GameRuleCore rules = new GameRuleCore();
        RandomRoundGenerator generator = model.isEmpty()
            ? new RandomRoundGenerator(rules)
            : new RandomRoundGenerator(rules, new SecureRandom(), model);
        return new RealtimeRoundStore(generator);
    }

    @Override
    public LuckyDragonService.ClaimedRound claim(RoundRequest request, int requestedOdd) {
        SpinResult result = generator.generate(request, requestedOdd);
        verifier.verify(request, result);
        String roundKey = "42-rt-" + Long.toUnsignedString(random.nextLong());
        RoundFacts facts = new RoundFacts(
            request.betSize(),
            request.betLevel(),
            result.symbols(),
            result.reelMultiplier(),
            roundKey,
            0,
            true);
        String member = new String(codec.encode(facts), StandardCharsets.US_ASCII);
        return new LuckyDragonService.ClaimedRound(roundKey, result, member);
    }
}
