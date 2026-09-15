package com.cpgame.replica.beeworkshop;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis member is printable ASCII structural facts, never JSON.
 * BW1|kindOrdinal|board15.hexMask~...  Awards are recalculated by ResultUtil.
 */
public final class CompleteRoundCodec {
    private final GameRuleCore rules = new GameRuleCore();

    public String encode(GameRuleCore.CompleteRound round) {
        rules.validate(round);
        StringBuilder out = new StringBuilder("BW1|").append(round.kind().ordinal()).append('|');
        for (int i = 0; i < round.steps().size(); i++) {
            if (i > 0) out.append('~');
            var step = round.steps().get(i);
            for (int value : step.board()) out.append((char) ('0' + value));
            int mask = 0;
            for (int p : step.specialPositions()) mask |= 1 << p;
            out.append('.').append(Integer.toHexString(mask));
        }
        String value = out.toString();
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) throw new IllegalStateException("non ASCII");
        if (value.charAt(0) == '{' || value.charAt(0) == '[') throw new IllegalStateException("member must not be JSON");
        return value;
    }

    public GameRuleCore.CompleteRound decode(String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("empty member");
            if (value.charAt(0) == '{' || value.charAt(0) == '[') throw new IllegalArgumentException("JSON members are rejected");
            String[] h = value.split("\\|", 3);
            if (h.length != 3 || !"BW1".equals(h[0])) throw new IllegalArgumentException("header");
            var kind = GameRuleCore.RoundKind.values()[Integer.parseInt(h[1])];
            List<GameRuleCore.Step> steps = new ArrayList<>();
            for (String raw : h[2].split("~")) {
                String[] p = raw.split("\\.", 2);
                if (p.length != 2 || p[0].length() != GameRuleCore.CELLS) throw new IllegalArgumentException("step");
                int[] board = new int[GameRuleCore.CELLS];
                for (int i = 0; i < board.length; i++) board[i] = p[0].charAt(i) - '0';
                int mask = Integer.parseInt(p[1], 16);
                List<Integer> special = new ArrayList<>();
                for (int i = 0; i < GameRuleCore.CELLS; i++) if ((mask & (1 << i)) != 0) special.add(i);
                steps.add(new GameRuleCore.Step(board, special));
            }
            var round = new GameRuleCore.CompleteRound(kind, steps);
            rules.validate(round);
            return round;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid BW1 member", e);
        }
    }
}
