package com.cpgame.junglekings;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Enumerates odd lists and top/bottom combo maps. No seed. Does not require Redis. */
public final class JungleKingsCatalogCli {
    public static void main(String[] args) {
        List<String> single = List.of(GameRuleCore.CB_TOP);
        List<String> both = GameRuleCore.CHESSBOARDS;
        System.out.printf("rulesVersion=%s rulesHash=%s%n", GameRuleCore.RULES_VERSION, GameRuleCore.RULES_HASH);
        System.out.printf("generation.mode=realtime generation.cache=false%n");
        System.out.printf("singleList size=%d %s%n",
                JungleKingsMultiplierCatalog.oddsList(single).size(),
                JungleKingsMultiplierCatalog.oddsList(single));
        System.out.printf("bothList size=%d %s%n",
                JungleKingsMultiplierCatalog.oddsList(both).size(),
                JungleKingsMultiplierCatalog.oddsList(both));
        System.out.printf("singleMap keys=%d combos=%d%n",
                JungleKingsMultiplierCatalog.comboMap(single).size(),
                JungleKingsMultiplierCatalog.comboCount(single));
        System.out.printf("bothMap keys=%d combos=%d%n",
                JungleKingsMultiplierCatalog.comboMap(both).size(),
                JungleKingsMultiplierCatalog.comboCount(both));
        printCombos("single", single);
        printCombos("both", both);
        for (int chessboard = 0; chessboard < 2; chessboard++) {
            int boardIndex = chessboard;
            Map<Integer, List<List<String>>> map = JungleKingsMultiplierCatalog.reelOdds(boardIndex);
            map.forEach((odd, list) -> System.out.printf("chessboard=%d pageOdd=%d boardCases=%d%n",
                    boardIndex, odd, list.size()));
        }
        System.out.printf("payTable=%s%n", GameRuleCore.payMultipliers());
        System.out.println("OK realtime multiplier catalog");
    }

    private static void printCombos(String label, List<String> chessboards) {
        JungleKingsMultiplierCatalog.comboMap(chessboards).forEach((odd, combos) -> {
            List<String> pairs = combos.stream().map(Arrays::toString).toList();
            System.out.printf("%s odd=%d combos=%s%n", label, odd, pairs);
        });
    }
}
