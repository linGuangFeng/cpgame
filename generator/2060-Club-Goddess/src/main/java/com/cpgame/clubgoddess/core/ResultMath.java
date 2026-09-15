package com.cpgame.clubgoddess.core;

import com.cpgame.clubgoddess.core.GameModels.WinItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Generation-side projection math. ResultUtil does not call this class. */
final class ResultMath {
    private ResultMath() {}

    static List<WinItem> evaluate(List<Integer> board, BigDecimal bet, int level) {
        if (board.size() != GameRuleDefinition.CELL_COUNT) throw new IllegalArgumentException("Invalid board size");
        List<WinItem> wins = new ArrayList<>();
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        for (int row = 0; row < GameRuleDefinition.ROWS; row++) {
            if (GameRuleDefinition.REGULAR_SYMBOLS.contains(board.get(row))) candidates.add(board.get(row));
        }
        for (int symbol : candidates) {
            List<List<Integer>> positionsByReel = new ArrayList<>();
            for (int reel = 0; reel < GameRuleDefinition.COLUMNS; reel++) {
                List<Integer> positions = new ArrayList<>();
                for (int row = 0; row < GameRuleDefinition.ROWS; row++) {
                    int pos = reel * GameRuleDefinition.ROWS + row;
                    int cell = board.get(pos);
                    if (cell == symbol || (GameRuleDefinition.WILD_REELS_ZERO_BASED.contains(reel)
                            && cell == GameRuleDefinition.WILD)) positions.add(pos);
                }
                if (positions.isEmpty()) break;
                positionsByReel.add(positions);
            }
            int reels = positionsByReel.size();
            if (reels < GameRuleDefinition.MINIMUM_WIN_REELS) continue;
            int ways = 1;
            List<Integer> positions = new ArrayList<>();
            for (List<Integer> reelPositions : positionsByReel) {
                ways = Math.multiplyExact(ways, reelPositions.size());
                positions.addAll(reelPositions);
            }
            int odd = GameRuleDefinition.odd(symbol, reels);
            BigDecimal win = money(bet.multiply(BigDecimal.valueOf(level))
                    .multiply(BigDecimal.valueOf(odd)).multiply(BigDecimal.valueOf(ways)));
            wins.add(new WinItem(odd, List.copyOf(positions), win, ways, symbol));
        }
        return List.copyOf(wins);
    }

    static BigDecimal total(List<WinItem> wins) {
        return money(wins.stream().map(WinItem::tw).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    static int scatterCount(List<Integer> board) {
        return (int) board.stream().filter(v -> v == GameRuleDefinition.SCATTER).count();
    }

    static void validateBoardSymbols(List<Integer> board) {
        if (board.stream().filter(v -> v == GameRuleDefinition.WILD).count() > 2)
            throw new IllegalArgumentException("Wild exceeds observed board maximum 2");
        for (int reel = 0; reel < 5 && board.size() == 15; reel++) {
            if (scatterCount(board.subList(reel * 3, reel * 3 + 3)) > 1)
                throw new IllegalArgumentException("Scatter exceeds observed reel maximum 1");
        }
        if (board.size() != GameRuleDefinition.CELL_COUNT) throw new IllegalArgumentException("Invalid board size");
        for (int pos = 0; pos < board.size(); pos++) {
            int symbol = board.get(pos);
            if (!GameRuleDefinition.REGULAR_SYMBOLS.contains(symbol)
                    && symbol != GameRuleDefinition.SCATTER && symbol != GameRuleDefinition.WILD) {
                throw new IllegalArgumentException("Unsupported symbol " + symbol);
            }
            int reel = pos / GameRuleDefinition.ROWS;
            if (symbol == GameRuleDefinition.WILD
                    && !GameRuleDefinition.WILD_REELS_ZERO_BASED.contains(reel)) {
                throw new IllegalArgumentException("Wild on forbidden reel");
            }
        }
    }
}
