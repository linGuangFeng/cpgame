package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One generate entry: ckl picks the odd list, requested odd floors into that list,
 * the top/bottom combo and boards come from the catalog. No Redis, no retry.
 */
public final class CompleteRoundFactory {
    public CompleteRound generate(RoundMode requested, SecureRandom random, BigDecimal betSize, int betLevel) {
        return generate(requested, GameRuleCore.CHESSBOARDS, random, betSize, betLevel);
    }

    public CompleteRound generate(RoundMode requested, List<String> chessboards,
                                  SecureRandom random, BigDecimal betSize, int betLevel) {
        int odd = requested == RoundMode.LOSS
                ? 0
                : JungleKingsMultiplierCatalog.samplePositiveOdd(random, chessboards);
        return generate(random, chessboards, betSize, betLevel, odd);
    }

    public static CompleteRound generate(SecureRandom random, List<String> chessboards,
                                         BigDecimal betSize, int betLevel, int requestedOdd) {
        Objects.requireNonNull(random);
        GameRuleCore.validateBet(betSize, betLevel);
        List<String> keys = GameRuleCore.parseChessboards(String.join(",", chessboards));
        int floored = JungleKingsMultiplierCatalog.floorOdd(keys, requestedOdd);
        if(floored==0){
            String key=String.join(",",keys);
            ZeroLossSupport<List<List<String>>> pool=LOSS_POOLS.computeIfAbsent(key,k->{
                SecureRandom setup=new SecureRandom();
                return new ZeroLossSupport<>(()->lossCandidate(setup,keys),CompleteRoundFactory::validLoss,values->List.copyOf(values));
            });
            List<List<String>> pages=pool.generate(()->lossCandidate(random,keys),random::nextInt);
            return GameRuleCore.materialize(keys,pages,betSize,betLevel);
        }

        int[] combo = JungleKingsMultiplierCatalog.pickCombo(keys, floored, random);
        if (combo.length != keys.size()) {
            throw new IllegalStateException("combo width " + combo.length + " != ckl " + keys.size());
        }
        List<List<String>> boards = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            boards.add(JungleKingsBoardGenerator.generate(
                    random, GameRuleCore.chessboardIndex(keys.get(i)), combo[i]));
        }
        CompleteRound round = GameRuleCore.materialize(keys, boards, betSize, betLevel);
        ResultUtil.Evaluation check = ResultUtil.evaluate(keys, boards, betSize, betLevel);
        if (check.multiplier() != floored) {
            throw new IllegalStateException("generated multiplier " + check.multiplier() + " != " + floored);
        }
        return round;
    }

    private static final java.util.concurrent.ConcurrentMap<String,ZeroLossSupport<List<List<String>>>> LOSS_POOLS=new java.util.concurrent.ConcurrentHashMap<>();
    public static List<List<String>> lossCandidate(SecureRandom random,List<String> keys){
        List<List<String>> pages=new ArrayList<>();for(String key:keys)
            pages.add(JungleKingsBoardGenerator.generate(random,GameRuleCore.chessboardIndex(key),0));
        return List.copyOf(pages);
    }
    private static boolean validLoss(List<List<String>> pages){
        for(List<String> b:pages)if(JungleKingsMultiplierCatalog.pageOdd(b)!=0)return false;
        return true;
    }
}
