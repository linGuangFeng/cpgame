package com.cpgame.replica.blessing;

import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingMultiplierCatalog;
import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingRoundFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Enumerates odd lists and fire/ice combo maps. No seed. Does not require Redis. */
public final class BlessingCatalogCli {
    public static void main(String[] args) {
        System.out.printf("rulesVersion=%s rulesHash=%s%n", BlessingRulesMetadata.VERSION, BlessingRulesMetadata.HASH);
        System.out.printf("singleList size=%d %s%n",
                BlessingMultiplierCatalog.oddsList(BlessingRoundFactory.TYPE_FIRE).size(),
                BlessingMultiplierCatalog.oddsList(BlessingRoundFactory.TYPE_FIRE));
        System.out.printf("bothList size=%d %s%n",
                BlessingMultiplierCatalog.oddsList(BlessingRoundFactory.TYPE_BOTH).size(),
                BlessingMultiplierCatalog.oddsList(BlessingRoundFactory.TYPE_BOTH));
        System.out.printf("singleFireMap keys=%d combos=%d%n",
                BlessingMultiplierCatalog.comboMap(BlessingRoundFactory.TYPE_FIRE).size(),
                BlessingMultiplierCatalog.comboCount(BlessingRoundFactory.TYPE_FIRE));
        System.out.printf("singleIceMap keys=%d combos=%d%n",
                BlessingMultiplierCatalog.comboMap(BlessingRoundFactory.TYPE_ICE).size(),
                BlessingMultiplierCatalog.comboCount(BlessingRoundFactory.TYPE_ICE));
        System.out.printf("bothMap keys=%d combos=%d%n",
                BlessingMultiplierCatalog.comboMap(BlessingRoundFactory.TYPE_BOTH).size(),
                BlessingMultiplierCatalog.comboCount(BlessingRoundFactory.TYPE_BOTH));
        Map<Integer, List<int[]>> map = BlessingMultiplierCatalog.singleReelOdds();
        map.forEach((odd, list) -> {
            System.out.printf("pageOdd=%d middleCases=%d%n", odd, list.size());
            if (odd > 0 && list.size() <= 8) {
                for (int[] mid : list) System.out.println("  " + Arrays.toString(mid));
            }
        });
        System.out.printf("payTable=%s%n", BlessingResultUtil.payTable());
        System.out.println("OK realtime multiplier catalog");
    }
}
