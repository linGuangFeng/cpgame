package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent ways reconstruction; does not call GameRuleCore.evaluateBoard. */
public final class ResultUtil {
    public BoardResult evaluate(List<String> rskl, BigDecimal betSize, int betLevel, int roundPayX) {
        GameRuleCore.validateBet(betSize, betLevel);
        List<Token> tokens = GameRuleCore.parse(rskl);
        Map<Integer, List<Token>> byReel = new LinkedHashMap<>();
        for (int c = 0; c < 6; c++) byReel.put(c, new ArrayList<>());
        for (Token token : tokens) byReel.get(token.reel()).add(token);
        List<WinMatch> matches = new ArrayList<>();
        BigDecimal unmultiplied = BigDecimal.ZERO;
        Map<String, Map<Integer, Integer>> table = GameRuleCore.symbolPayTable();
        for (String symbol : GameRuleCore.PAYING_SYMBOLS) {
            List<List<Integer>> groups = new ArrayList<>();
            int length = 0;
            int ways = 1;
            for (int c = 0; c < 6; c++) {
                List<Integer> mainHits = new ArrayList<>();
                List<Integer> extraHits = new ArrayList<>();
                for (Token token : byReel.get(c)) {
                    if (token.symbol().equals(symbol) || "Wild".equals(token.symbol())) {
                        if (token.extra()) extraHits.add(token.coord());
                        else mainHits.add(token.coord());
                    }
                }
                if (mainHits.isEmpty() && extraHits.isEmpty()) break;
                List<Integer> hits = new ArrayList<>(mainHits);
                hits.addAll(extraHits);
                groups.add(List.copyOf(hits));
                ways *= hits.size();
                length++;
            }
            if (length < 3) continue;
            Integer units = table.get(symbol).get(length);
            if (units == null || units <= 0) continue;
            BigDecimal raw = betSize.multiply(BigDecimal.valueOf((long) betLevel * units * ways));
            unmultiplied = unmultiplied.add(raw);
            matches.add(new WinMatch(symbol, groups, ways, raw.stripTrailingZeros()));
        }
        BigDecimal win = unmultiplied.multiply(BigDecimal.valueOf(roundPayX)).stripTrailingZeros();
        return new BoardResult(matches, win);
    }

    public RoundMode mode(CompleteRound round) {
        return GameRuleCore.classify(round.steps(), payout(round));
    }

    public BigDecimal payout(CompleteRound round) {
        return round.steps().getLast().roundWinAmount();
    }

    public record BoardResult(List<WinMatch> matches, BigDecimal winAmount) {
        public BoardResult {
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
        }
    }
}
