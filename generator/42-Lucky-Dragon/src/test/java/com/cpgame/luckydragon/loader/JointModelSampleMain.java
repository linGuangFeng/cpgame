package com.cpgame.luckydragon.loader;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.RandomRoundGenerator;
import com.cpgame.luckydragon.core.RoundRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Emits fresh Java-generated complete INITIAL states for the independent v37 validator. */
public final class JointModelSampleMain {
    private JointModelSampleMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("config and sample count required");
        LoaderMain.LoaderConfig config = LoaderMain.LoaderConfig.load(Path.of(args[0]));
        int count = Integer.parseInt(args[1]);
        if (count < 10_000) throw new IllegalArgumentException("v37 requires at least 10000 generated rounds");
        GameRuleCore rules = new GameRuleCore();
        RandomRoundGenerator generator = new RandomRoundGenerator(rules, new SecureRandom(), config.jointStateModel());
        IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
        RoundRequest request = new RoundRequest(new BigDecimal("0.5"), 1);
        for (int index = 0; index < count; index++) {
            var result = generator.next(request);
            verifier.verify(request, result);
            System.out.println(String.join(",", result.symbols()) + "," + result.reelMultiplier());
        }
    }
}
