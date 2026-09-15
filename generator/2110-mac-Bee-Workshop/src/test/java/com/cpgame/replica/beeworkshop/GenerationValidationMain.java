package com.cpgame.replica.beeworkshop;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/** 10000 generated complete rounds plus independent-loss smoke. */
public final class GenerationValidationMain {
    public static void main(String[] args) {
        GameRuleCore rules = new GameRuleCore();
        ResultUtil util = new ResultUtil(rules);
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Random random = new SecureRandom();
        Map<GameRuleCore.RoundKind, Integer> counts = new EnumMap<>(GameRuleCore.RoundKind.class);
        for (var kind : GameRuleCore.RoundKind.values()) counts.put(kind, 0);
        int generated = 0, specialEntry = 0;
        while (generated < 10000) {
            boolean special = generated % CompleteRoundFactory.ENTRY_SWITCH_EVERY < 1 && generated > 0
                    ? (specialEntry++ >= 0 && generated / CompleteRoundFactory.ENTRY_SWITCH_EVERY % 2 == 1)
                    : generated % (2 * CompleteRoundFactory.ENTRY_SWITCH_EVERY) >= CompleteRoundFactory.ENTRY_SWITCH_EVERY;
            var round = factory.generate(random, special);
            rules.validate(round);
            String encoded = codec.encode(round);
            if (encoded.charAt(0) == '{' || encoded.charAt(0) == '[') throw new IllegalStateException("JSON member");
            var decoded = codec.decode(encoded);
            if (util.integerMultiplier(round) != util.integerMultiplier(decoded)) throw new IllegalStateException("codec multiplier");
            counts.merge(round.kind(), 1, Integer::sum);
            generated++;
            if (generated % 1000 == 0) System.out.println("generated " + generated);
        }
        System.out.println("{\"generatedCompleteRounds\":" + generated + ",\"kinds\":" + counts
                + ",\"rulesHash\":\"" + GameRuleCore.RULES_HASH + "\",\"status\":\"PASS\"}");
    }
}
