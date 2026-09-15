package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent count-based result reconstruction; does not call GameRuleCore.evaluateBoard. */
public final class ResultUtil {
    public BoardResult evaluate(List<String> board, BigDecimal betSize, int betLevel) {
        if (board == null || board.size() != 36) throw new IllegalArgumentException("board must contain 36 symbols");
        GameRuleCore.validateBet(betSize, betLevel);
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (String symbol : GameRuleCore.PAYING_SYMBOLS) positions.put(symbol, new ArrayList<>());
        for (int index = 0; index < 36; index++) {
            if (!GameRuleCore.ALL_SYMBOLS.contains(board.get(index))) throw new IllegalArgumentException("unknown symbol");
            List<Integer> found = positions.get(board.get(index));
            if (found != null) found.add((index / 6) * 10 + index % 6);
        }
        List<WinMatch> matches = new ArrayList<>();
        positions.forEach((symbol, cells) -> {
            if (cells.size() >= 8) matches.add(new WinMatch(symbol, cells,
                GameRuleCore.pay(symbol, cells.size(), betSize, betLevel)));
        });
        BigDecimal total = matches.stream().map(WinMatch::winAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        return new BoardResult(matches, total);
    }

    public BoardResult evaluate(List<String> board, BigDecimal betSize) { return evaluate(board, betSize, 1); }
    public RoundMode mode(CompleteRound round) { return GameRuleCore.classify(round.steps(), payout(round)); }
    public BigDecimal payout(CompleteRound round) {
        return round.steps().stream().filter(step -> step.spinStatus() == 1)
            .map(Step::roundWinAmount).reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
    }

    public record BoardResult(List<WinMatch> matches, BigDecimal winAmount) {
        public BoardResult {
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
        }
    }
}
