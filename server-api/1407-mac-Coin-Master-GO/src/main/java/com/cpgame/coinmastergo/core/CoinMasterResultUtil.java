package com.cpgame.coinmastergo.core;

import com.cpgame.coinmastergo.model.WinMatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Independent rule projection used to verify every board produced by GameRuleCore. */
public final class CoinMasterResultUtil {
    private CoinMasterResultUtil() { }

    public static Evaluation evaluate(List<String> transportBoard, int betLevel, BigDecimal betSize, int rpx) {
        validateTransportBoard(transportBoard);
        List<WinMatch> matches = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String payingSymbol : GameRules.PAYING_SYMBOLS) {
            List<List<Integer>> coordinates = new ArrayList<>();
            int ways = 1;
            int matchedReels = 0;
            for (int reel = 0; reel < GameRules.REELS; reel++) {
                List<Integer> reelCoordinates = new ArrayList<>();
                for (int row = 0; row < GameRules.VISIBLE_ROWS; row++) {
                    String actual = transportBoard.get(reel * GameRules.TRANSPORT_ROWS + row + 1);
                    if (actual.equals(payingSymbol) || actual.equals("WILD")) {
                        reelCoordinates.add(reel * 10 + row);
                    }
                }
                if (reelCoordinates.isEmpty()) break;
                coordinates.add(reelCoordinates);
                matchedReels++;
                ways *= reelCoordinates.size();
            }
            if (matchedReels >= 3) {
                BigDecimal multiplier = GameRules.PAYTABLE.get(payingSymbol).get(matchedReels);
                BigDecimal win = multiplier.multiply(betSize).multiply(BigDecimal.valueOf(betLevel))
                        .multiply(BigDecimal.valueOf(ways)).multiply(BigDecimal.valueOf(rpx));
                win = money(win);
                matches.add(new WinMatch(payingSymbol, win, coordinates));
                total = total.add(win);
            }
        }
        long scatterCount = visibleSymbols(transportBoard).stream().filter("SC"::equals).count();
        return new Evaluation(money(total), matches, (int) scatterCount);
    }

    public static void validateTransportBoard(List<String> board) {
        if (board == null || board.size() != GameRules.TRANSPORT_CELLS) {
            throw new IllegalArgumentException("rskl must contain exactly 25 reel-major symbols");
        }
        for (String symbol : board) {
            if (!GameRules.SYMBOLS.contains(symbol)) throw new IllegalArgumentException("unsupported symbol: " + symbol);
        }
    }

    private static List<String> visibleSymbols(List<String> board) {
        List<String> visible = new ArrayList<>(20);
        for (int reel = 0; reel < 5; reel++) {
            for (int row = 1; row < 5; row++) visible.add(board.get(reel * 5 + row));
        }
        return visible;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public record Evaluation(BigDecimal totalWin, List<WinMatch> matches, int scatterCount) { }
}
