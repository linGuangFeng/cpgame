package com.cpgame.monsterslayer.generator;

import com.cpgame.monsterslayer.core.GameRuleCore;
import com.cpgame.monsterslayer.core.MinimalRoundFactCodec;
import com.cpgame.monsterslayer.core.ResultUtil;
import java.security.SecureRandom;
import java.util.List;

/** Independent per-cell ordinary boards plus captured buy templates. */
public final class CompleteRoundFactory {
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    private final int[] normalWeights;
    private final int[] maryWeights;
    private final ZeroLossSupport<GameRuleCore.CompleteRound> losses;

    public CompleteRoundFactory() {
        this(MonsterSlayerBoardGenerator.defaultNormalWeights(), MonsterSlayerBoardGenerator.defaultMaryWeights());
    }

    public CompleteRoundFactory(int[] normalWeights) {
        this(normalWeights, MonsterSlayerBoardGenerator.defaultMaryWeights());
    }

    public CompleteRoundFactory(int[] normalWeights, int[] maryWeights) {
        this.normalWeights = MonsterSlayerBoardGenerator.validatedWeights(normalWeights, "normal");
        this.maryWeights = MonsterSlayerBoardGenerator.validatedWeights(maryWeights, "Mary");
        var defaultsRandom=new SecureRandom();
        losses=new ZeroLossSupport<>(()->lossCandidate(defaultsRandom),r->ResultUtil.evaluate(r).multiplierCenti()==0,r->r);
    }

    public GameRuleCore.CompleteRound generateLoss(SecureRandom random) { return losses.generate(()->lossCandidate(random),random::nextInt); }
    public GameRuleCore.CompleteRound generateWin(SecureRandom random) { return ordinary(random, true); }

    private GameRuleCore.CompleteRound ordinary(SecureRandom random, boolean winning) {
        MonsterSlayerBoardGenerator boards = new MonsterSlayerBoardGenerator(random, normalWeights, maryWeights);
        for (int attempt = 0; attempt < 100000; attempt++) {
            int[] board = boards.generateOrdinary();
            if (!MonsterSlayerBoardGenerator.legalOrdinary(board)) continue;
            GameRuleCore.CompleteRound round = new GameRuleCore.CompleteRound(false,
                    List.of(new GameRuleCore.Step(board, 0, 0)));
            if ((ResultUtil.evaluate(round).multiplierCenti() > 0) == winning) return round;
        }
        throw new IllegalStateException("ordinary per-cell model exhausted rejection limit");
    }

    public GameRuleCore.CompleteRound generateSpecial(SecureRandom random) {
        throw new IllegalStateException("Natural special generation blocked: scatter-feature holdout remains SAMPLE_INSUFFICIENT; use generateBuy");
    }

    public GameRuleCore.CompleteRound generateBuy(int buyType, SecureRandom random) {
        String[] corpus = switch (buyType) {
            case 3 -> BUY3;
            case 4 -> BUY4;
            case 5 -> BUY5;
            default -> throw new IllegalArgumentException("buy type must be 3/4/5");
        };
        if (corpus.length == 0) throw new IllegalStateException("buy corpus empty for type " + buyType);
        String template = corpus[random.nextInt(corpus.length)];
        return codec.decodeBuyTemplate(buyType, template);
    }

    private static final String[] BUY3 = loadCorpus("monster-slayer-buy-3.txt");
    private static final String[] BUY4 = loadCorpus("monster-slayer-buy-4.txt");
    private static final String[] BUY5 = loadCorpus("monster-slayer-buy-5.txt");

    private static String[] loadCorpus(String name) {
        try (var in = CompleteRoundFactory.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalStateException("missing buy corpus " + name);
            String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) throw new IllegalStateException("empty buy corpus " + name);
            return raw.split("\\R");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read buy corpus " + name, e);
        }
    }

    public GameRuleCore.CompleteRound lossCandidate(SecureRandom random){
        int[] b=new MonsterSlayerBoardGenerator(random,normalWeights,maryWeights).generateLossCandidate();
        return new GameRuleCore.CompleteRound(false,List.of(new GameRuleCore.Step(b,0,0)));
    }
}
