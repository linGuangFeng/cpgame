package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent 4-connected cluster reconstruction. Does not call GameRuleCore.evaluateBoard.
 */
public final class ResultUtil {
    private static final String[] PAYING = {"S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9"};

    public BoardResult evaluate(List<String> board, BigDecimal betSize, int betLevel) {
        if (board == null || board.size() != 25) throw new IllegalArgumentException("board must contain 25 symbols");
        GameRuleCore.validateBet(betSize, betLevel);
        List<WinMatch> matches = new ArrayList<>();
        for (String symbol : PAYING) {
            boolean[] visited = new boolean[25];
            for (int cell = 0; cell < 25; cell++) {
                if (visited[cell]) continue;
                if (!symbol.equals(board.get(cell)) && !"S1".equals(board.get(cell))) continue;
                List<Integer> cluster = new ArrayList<>();
                walk(board, symbol, cell, visited, cluster);
                boolean real = false;
                for (int index : cluster) if (symbol.equals(board.get(index))) { real = true; break; }
                if (!real || cluster.size() < 4) continue;
                List<Integer> coords = new ArrayList<>();
                for (int index : cluster) coords.add((index / 5) * 10 + (index % 5));
                coords.sort(Integer::compareTo);
                matches.add(new WinMatch(symbol, coords, GameRuleCore.pay(symbol, coords.size(), betSize, betLevel)));
            }
        }
        BigDecimal total = matches.stream().map(WinMatch::winAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        return new BoardResult(matches, total);
    }

    public RoundMode mode(CompleteRound round) { return GameRuleCore.classify(round.steps(), round.payout()); }

    public BigDecimal payout(CompleteRound round) {
        return round.steps().getLast().winAmountSum();
    }

    public boolean sameMatches(List<WinMatch> left, List<WinMatch> right) {
        return fingerprint(left).equals(fingerprint(right));
    }

    private static Set<String> fingerprint(List<WinMatch> matches) {
        Set<String> keys = new HashSet<>();
        for (WinMatch match : matches) {
            List<Integer> coords = new ArrayList<>(match.indices());
            coords.sort(Integer::compareTo);
            keys.add(match.symbolKey() + ":" + coords + ":" + match.winAmount().stripTrailingZeros().toPlainString());
        }
        return keys;
    }

    private void walk(List<String> board, String symbol, int cell, boolean[] visited, List<Integer> cluster) {
        if (cell < 0 || cell >= 25 || visited[cell]) return;
        String value = board.get(cell);
        if (!symbol.equals(value) && !"S1".equals(value)) return;
        visited[cell] = true;
        cluster.add(cell);
        int row = cell / 5, col = cell % 5;
        if (col > 0) walk(board, symbol, cell - 1, visited, cluster);
        if (col < 4) walk(board, symbol, cell + 1, visited, cluster);
        if (row > 0) walk(board, symbol, cell - 5, visited, cluster);
        if (row < 4) walk(board, symbol, cell + 5, visited, cluster);
    }

    public record BoardResult(List<WinMatch> matches, BigDecimal winAmount) {
        public BoardResult {
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
        }
    }
}
