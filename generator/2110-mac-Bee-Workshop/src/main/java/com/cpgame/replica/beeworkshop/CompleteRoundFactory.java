package com.cpgame.replica.beeworkshop;

import java.util.Random;

/**
 * One paid start through every cascade/free delivery. Demo never calls this;
 * RedisDirectLoader uses it to pre-write complete members.
 */
public final class CompleteRoundFactory {
    public static final int ENTRY_SWITCH_EVERY = 1000;
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil util = new ResultUtil(rules);
    private final RoundFactory factory;
    private final IndependentLossGenerator loss;

    public CompleteRoundFactory() { this(new Random(), null); }
    public CompleteRoundFactory(Random random) { this(random, null); }
    public CompleteRoundFactory(int[] ordinaryWeights) { this(new Random(), ordinaryWeights); }
    public CompleteRoundFactory(Random random, int[] ordinaryWeights) {
        this.factory = new RoundFactory(rules, random);
        this.loss = new IndependentLossGenerator(ordinaryWeights);
    }

    public GameRuleCore.CompleteRound generate(Random random, boolean specialEntry) {
        GameRuleCore.CompleteRound round;
        if (specialEntry) {
            round = factory.generate(GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS);
        } else {
            int draw = random.nextInt(1929);
            if (draw < 1485) round = loss.generate(random);
            else if (draw < 1485 + 120) round = factory.generate(GameRuleCore.RoundKind.ORDINARY_WIN);
            else if (draw < 1485 + 120 + 192) round = factory.generate(GameRuleCore.RoundKind.MYSTERY_BOX);
            else round = factory.generate(GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS);
        }
        rules.validate(round);
        long core = round.steps().stream().mapToLong(s -> rules.payoutUnits(s.board())).sum();
        if (core != util.totalUnits(round)) throw new IllegalStateException("ResultUtil disagrees with GameRuleCore");
        return round;
    }

    public record GeneratedRound(GameRuleCore.CompleteRound round, int multiplier, boolean special) {}

    public GeneratedRound generateVerified(Random random, boolean specialEntry) {
        var round = generate(random, specialEntry);
        return new GeneratedRound(round, util.integerMultiplier(round), GameRuleCore.special(round.kind()));
    }
}
