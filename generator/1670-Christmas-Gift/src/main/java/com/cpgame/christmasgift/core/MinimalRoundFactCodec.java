package com.cpgame.christmasgift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Compact ASCII facts only. Amounts, wins, odds and envelopes are derived after Redis read. */
public final class MinimalRoundFactCodec {
    private static final String NORMAL = "CG38N:";
    private static final String FEATURE = "CG38F";

    public String encode(GameRuleCore.CompleteRound round) {
        StringBuilder encoded = new StringBuilder();
        if (round.mode() == GameRuleCore.Mode.ORDINARY) {
            encoded.append(NORMAL);
            for (int position = 1; position <= 9; position++) {
                Integer symbol = round.steps().get(0).newlyDealt().get(position);
                if (symbol == null) throw new IllegalArgumentException("ordinary board incomplete");
                encoded.append(symbol);
            }
            return encoded.toString();
        }
        encoded.append(FEATURE).append(round.targetSymbol()).append(':');
        for (int step = 0; step < round.steps().size(); step++) {
            if (step > 0) encoded.append('/');
            boolean first = true;
            for (var entry : round.steps().get(step).newlyDealt().entrySet()) {
                if (!first) encoded.append(',');
                encoded.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
        }
        return encoded.toString();
    }

    public GameRuleCore.CompleteRound decode(String encoded) {
        if (encoded.startsWith(NORMAL)) {
            String symbols = encoded.substring(NORMAL.length());
            if (symbols.length() != 9) throw new IllegalArgumentException("invalid ordinary member");
            LinkedHashMap<Integer, Integer> board = new LinkedHashMap<>();
            for (int position = 1; position <= 9; position++) {
                board.put(position, digit(symbols.charAt(position - 1)));
            }
            return new GameRuleCore.CompleteRound(GameRuleCore.Mode.ORDINARY, 0,
                List.of(new GameRuleCore.Deal(board)));
        }
        if (!encoded.startsWith(FEATURE) || encoded.length() < 7 || encoded.charAt(6) != ':') {
            throw new IllegalArgumentException("unknown member format");
        }
        int target = digit(encoded.charAt(5));
        String[] rawSteps = encoded.substring(7).split("/", -1);
        List<GameRuleCore.Deal> steps = new ArrayList<>();
        for (String rawStep : rawSteps) {
            LinkedHashMap<Integer, Integer> deal = new LinkedHashMap<>();
            if (rawStep.isEmpty()) throw new IllegalArgumentException("empty step");
            for (String pair : rawStep.split(",")) {
                String[] values = pair.split("=", -1);
                if (values.length != 2) throw new IllegalArgumentException("invalid step pair");
                deal.put(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
            }
            steps.add(new GameRuleCore.Deal(deal));
        }
        return new GameRuleCore.CompleteRound(GameRuleCore.Mode.CHRISTMAS_GIFT_FEATURE, target, steps);
    }

    private int digit(char value) {
        if (value < '0' || value > '7') throw new IllegalArgumentException("invalid symbol digit");
        return value - '0';
    }
}
