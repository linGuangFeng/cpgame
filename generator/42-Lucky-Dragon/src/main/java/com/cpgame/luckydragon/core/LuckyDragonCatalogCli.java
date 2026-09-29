package com.cpgame.luckydragon.core;

/** Enumerates the realtime odd list and combo map. No seed. Does not require Redis. */
public final class LuckyDragonCatalogCli {
    public static void main(String[] args) {
        System.out.printf("rulesHash=%s%n", GameRuleCore.RULES_HASH);
        System.out.printf("generation.mode=realtime generation.cache=false%n");
        System.out.printf("oddsList size=%d %s%n",
                LuckyDragonMultiplierCatalog.oddsList().size(),
                LuckyDragonMultiplierCatalog.oddsList());
        System.out.printf("comboMap keys=%d combos=%d%n",
                LuckyDragonMultiplierCatalog.comboMap().size(),
                LuckyDragonMultiplierCatalog.comboCount());
        LuckyDragonMultiplierCatalog.comboMap().forEach((odd, combos) ->
                System.out.printf("odd=%d special=%s combos=%d%n",
                        odd, LuckyDragonMultiplierCatalog.special(odd), combos.size()));
        System.out.printf("payTable=%s%n", GameRuleCore.SYMBOL_PAY_MULTIPLIERS);
        System.out.printf("floorOdd(1000)=%d%n", LuckyDragonMultiplierCatalog.floorOdd(1000));
        System.out.println("OK realtime multiplier catalog");
    }

    private LuckyDragonCatalogCli() { }
}
