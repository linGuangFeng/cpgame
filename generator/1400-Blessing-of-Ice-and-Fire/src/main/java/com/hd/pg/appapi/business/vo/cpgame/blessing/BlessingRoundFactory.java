package com.hd.pg.appapi.business.vo.cpgame.blessing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * One generate entry: bet_type picks the odd list, requested odd floors into that list,
 * fire/ice combo comes from the map, fillers stay realtime.
 */
public final class BlessingRoundFactory {
    public static final int TYPE_FIRE = 1;
    public static final int TYPE_ICE = 2;
    public static final int TYPE_BOTH = 3;

    private BlessingRoundFactory() { }

    public static BlessingRound generate(SecureRandom random, int betType, BigDecimal betSize, int level,
                                        int requestedOdd) {
        if (betType < TYPE_FIRE || betType > TYPE_BOTH) throw new IllegalArgumentException("bet_type 1|2|3");
        if (level < 1 || level > 10) throw new IllegalArgumentException("level 1..10");
        int floored = BlessingMultiplierCatalog.floorOdd(betType, requestedOdd);
        if(floored==0){
            int[][] pages=LOSS_POOL.generate(()->lossCandidate(random),random::nextInt);
            BigDecimal unit=betSize.multiply(BigDecimal.valueOf(level)).setScale(4,RoundingMode.HALF_UP);
            BigDecimal charged=betType==TYPE_BOTH?unit.multiply(BigDecimal.valueOf(2)):unit;
            return settle(pages[0],pages[1],betType,betSize,level,unit,charged);
        }

        List<int[]> combos = BlessingMultiplierCatalog.combos(betType, floored);
        int[] pair = combos.get(random.nextInt(combos.size()));
        BigDecimal unit = betSize.multiply(BigDecimal.valueOf(level)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal charged = betType == TYPE_BOTH ? unit.multiply(BigDecimal.valueOf(2)) : unit;
        int[] top = BlessingBoardGenerator.generate(random, pair[0]);
        int[] bottom = BlessingBoardGenerator.generate(random, pair[1]);
        return settle(top, bottom, betType, betSize, level, unit, charged);
    }

    /** Demo sampling when the caller does not pass an odd: 28% a positive list value, else 0. */
    public static int sampleRequestedOdd(SecureRandom random, int betType) {
        if (random.nextInt(100) >= 28) return 0;
        List<Integer> wins = new ArrayList<>();
        for (int odd : BlessingMultiplierCatalog.oddsList(betType)) {
            if (odd > 0) wins.add(odd);
        }
        return wins.get(random.nextInt(wins.size()));
    }

    static BlessingRound settle(int[] top, int[] bottom, int betType, BigDecimal betSize, int level,
                                BigDecimal unit, BigDecimal charged) {
        BlessingResultUtil.BlessingPage topPage = BlessingResultUtil.evaluatePage(top);
        BlessingResultUtil.BlessingPage bottomPage = BlessingResultUtil.evaluatePage(bottom);
        if (betType == TYPE_FIRE && bottomPage.odd() != 0) {
            bottomPage = new BlessingResultUtil.BlessingPage(bottomPage.p(), bottomPage.w(), 0);
        }
        if (betType == TYPE_ICE && topPage.odd() != 0) {
            topPage = new BlessingResultUtil.BlessingPage(topPage.p(), topPage.w(), 0);
        }
        boolean bothWin = topPage.odd() > 0 && bottomPage.odd() > 0 && betType == TYPE_BOTH;
        int m = bothWin ? 2 : 1;
        BigDecimal topTw = money(unit.multiply(BigDecimal.valueOf(topPage.odd())));
        BigDecimal bottomTw = money(unit.multiply(BigDecimal.valueOf(bottomPage.odd())));
        BigDecimal total = money(topTw.add(bottomTw).multiply(BigDecimal.valueOf(m)));
        BigDecimal odds = charged.signum() == 0 ? BigDecimal.ZERO
                : total.divide(charged, 4, RoundingMode.HALF_UP);
        return new BlessingRound(betType, betSize, level, unit, charged, topPage, bottomPage, m, topTw, bottomTw,
                total, odds);
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record BlessingRound(
            int betType,
            BigDecimal betSize,
            int level,
            BigDecimal unit,
            BigDecimal charged,
            BlessingResultUtil.BlessingPage top,
            BlessingResultUtil.BlessingPage bottom,
            int bothMultiplier,
            BigDecimal topTw,
            BigDecimal bottomTw,
            BigDecimal totalWin,
            BigDecimal odds
    ) { }

    private static final SecureRandom DEFAULT_RANDOM=new SecureRandom();
    private static final ZeroLossSupport<int[][]> LOSS_POOL=new ZeroLossSupport<>(
        ()->lossCandidate(DEFAULT_RANDOM),b->BlessingResultUtil.evaluateOdd(b[0])==0&&BlessingResultUtil.evaluateOdd(b[1])==0,
        b->new int[][]{b[0].clone(),b[1].clone()});
    public static int[][] lossCandidate(SecureRandom random){return new int[][]{
        BlessingBoardGenerator.generate(random,0),BlessingBoardGenerator.generate(random,0)};}
}
