package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent settlement. Reconstructs wins from chessboard windows and the captured
 * paytable; does not call GameRuleCore.materialize.
 */
public final class ResultUtil {
    private ResultUtil() { }

    public static Evaluation evaluate(List<String> chessboards, List<List<String>> boards,
                                      BigDecimal betSize, int betLevel) {
        GameRuleCore.validateBet(betSize, betLevel);
        if (chessboards == null || boards == null || chessboards.size() != boards.size()
                || chessboards.isEmpty()) {
            throw new IllegalArgumentException("chessboards and boards must align");
        }
        BigDecimal lineStake = GameRuleCore.lineStake(betSize, betLevel);
        BigDecimal betAmount = GameRuleCore.money(lineStake.multiply(BigDecimal.valueOf(chessboards.size())));
        List<String> paylines = new ArrayList<>();
        List<String> winSymbols = new ArrayList<>();
        BigDecimal rawWin = BigDecimal.ZERO;
        for (int i = 0; i < chessboards.size(); i++) {
            LineWin line = evaluateLine(chessboards.get(i), boards.get(i), lineStake);
            if (line.winAmount().signum() > 0) {
                paylines.add(line.paylineKey());
                winSymbols.add(line.symbol());
                rawWin = rawWin.add(line.winAmount());
            }
        }
        boolean bothWin = chessboards.size() == 2 && paylines.size() == 2;
        BigDecimal winAmount = bothWin
                ? GameRuleCore.money(rawWin.multiply(BigDecimal.valueOf(GameRuleCore.X2_WHEN_BOTH_WIN)))
                : GameRuleCore.money(rawWin);
        BigDecimal ratio = winAmount.divide(betAmount, 8, RoundingMode.HALF_UP);
        if (ratio.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("round multiplier is not an integer: " + ratio);
        }
        int multiplier = ratio.intValueExact();
        RoundMode mode = winAmount.signum() == 0 ? RoundMode.LOSS : RoundMode.WIN;
        return new Evaluation(mode, betAmount, winAmount, multiplier, paylines, winSymbols);
    }

    public static Evaluation evaluateSingleBoard(List<String> board, BigDecimal betAmount) {
        if (betAmount == null || betAmount.signum() <= 0) {
            throw new IllegalArgumentException("bet amount must be positive");
        }
        List<String> reels = GameRuleCore.logicalReels(board);
        GameRuleCore.requireConfirmedWinBoundary(reels);
        String first = reels.get(0);
        boolean triple = first.equals(reels.get(1)) && first.equals(reels.get(2));
        if (!triple || GameRuleCore.payMultiplier(first) <= 0) {
            return new Evaluation(RoundMode.LOSS, GameRuleCore.money(betAmount), BigDecimal.ZERO, 0,
                    List.of(), List.of());
        }
        int units = GameRuleCore.payMultiplier(first);
        BigDecimal win = GameRuleCore.money(betAmount.multiply(BigDecimal.valueOf(units)));
        return new Evaluation(RoundMode.WIN, GameRuleCore.money(betAmount), win, units,
                List.of(GameRuleCore.PL_V1_BOTTOM), List.of(first));
    }

    public static LineWin evaluateLine(String chessboard, List<String> board, BigDecimal lineStake) {
        List<String> reels = GameRuleCore.logicalReels(board);
        GameRuleCore.requireConfirmedWinBoundary(reels);
        String first = reels.get(0);
        boolean triple = first.equals(reels.get(1)) && first.equals(reels.get(2));
        if (!triple || GameRuleCore.payMultiplier(first) <= 0) {
            return new LineWin(chessboard, GameRuleCore.paylineKey(chessboard), null, BigDecimal.ZERO);
        }
        BigDecimal win = GameRuleCore.money(lineStake.multiply(BigDecimal.valueOf(GameRuleCore.payMultiplier(first))));
        return new LineWin(chessboard, GameRuleCore.paylineKey(chessboard), first, win);
    }

    public record Evaluation(RoundMode mode, BigDecimal betAmount, BigDecimal winAmount, int multiplier,
                             List<String> winPaylineKeys, List<String> winSymbolKeys) {
        public Evaluation {
            winPaylineKeys = List.copyOf(winPaylineKeys);
            winSymbolKeys = List.copyOf(winSymbolKeys);
            betAmount = betAmount.stripTrailingZeros();
            winAmount = winAmount.stripTrailingZeros();
        }
    }

    public record LineWin(String chessboard, String paylineKey, String symbol, BigDecimal winAmount) { }
}
