package com.cpgame.sambasensation.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前游戏专用极简 ASCII：SS2 + entry + betType + coinTransition + scatterDelta + coinDelta + rewardCount ; steps。
 * 只存不能从规则重算的可见事实，不存响应壳、余额、时间戳、中奖线或倍率。
 */
public final class MinimalRoundFactCodec {
    private static final String PREFIX = "SS2";

    public String encode(GameRuleCore.CompleteRoundFact fact) {
        GameRuleCore.validateStructure(fact);
        StringBuilder out = new StringBuilder(PREFIX);
        out.append(fact.entryKind() == GameRuleCore.EntryKind.PAID_INITIAL ? 'P' : 'B');
        out.append(Character.forDigit(fact.betType(), 36));
        out.append(switch (fact.coinTransition()) {
            case UNCHANGED -> 'U'; case INCREMENT_NONDECREASING -> 'I'; case FULL_TRIGGER -> 'F';
        });
        out.append(to36(fact.scatterDelta())).append(':');
        int[] delta = fact.coinDelta();
        for (int i = 0; i < delta.length; i++) {
            if (i > 0) out.append('.');
            out.append(to36(delta[i]));
        }
        out.append(':').append(to36(fact.coinRewardCount())).append(';');
        for (int step = 0; step < fact.steps().size(); step++) {
            if (step > 0) out.append('/');
            List<int[]> boards = fact.steps().get(step).boards();
            for (int axis = 0; axis < boards.size(); axis++) {
                if (axis > 0) out.append(',');
                for (int symbol : boards.get(axis)) out.append(Character.toUpperCase(Character.forDigit(symbol, 36)));
            }
        }
        String encoded = out.toString();
        if (encoded.startsWith("{") || encoded.startsWith("[")) throw new IllegalStateException("JSON member forbidden");
        for (int i = 0; i < encoded.length(); i++) if (encoded.charAt(i) > 127) throw new IllegalStateException("member must be ASCII");
        return encoded;
    }

    public GameRuleCore.CompleteRoundFact decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) throw new IllegalArgumentException("unknown Samba member version");
        if (value.startsWith("{") || value.startsWith("[")) throw new IllegalArgumentException("full JSON member forbidden");
        int semicolon = value.indexOf(';');
        int firstColon = value.indexOf(':', 6);
        int secondColon = value.indexOf(':', firstColon + 1);
        if (semicolon < 0 || firstColon < 0 || secondColon < 0 || secondColon > semicolon) throw new IllegalArgumentException("invalid member header");
        GameRuleCore.EntryKind entry = switch (value.charAt(3)) {
            case 'P' -> GameRuleCore.EntryKind.PAID_INITIAL;
            case 'B' -> GameRuleCore.EntryKind.FEATURE_BUY_INITIAL;
            default -> throw new IllegalArgumentException("invalid entry kind");
        };
        int betType = digit(value.charAt(4));
        GameRuleCore.CoinTransition coinTransition = switch (value.charAt(5)) {
            case 'U' -> GameRuleCore.CoinTransition.UNCHANGED;
            case 'I' -> GameRuleCore.CoinTransition.INCREMENT_NONDECREASING;
            case 'F' -> GameRuleCore.CoinTransition.FULL_TRIGGER;
            default -> throw new IllegalArgumentException("invalid coin transition");
        };
        int scatterDelta = from36(value.substring(6, firstColon));
        String[] encodedDelta = value.substring(firstColon + 1, secondColon).split("\\.", -1);
        if (encodedDelta.length != 5) throw new IllegalArgumentException("coin delta length mismatch");
        int[] coinDelta = new int[5];
        for (int i = 0; i < coinDelta.length; i++) coinDelta[i] = from36(encodedDelta[i]);
        int coinRewardCount = from36(value.substring(secondColon + 1, semicolon));
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String encodedStep : value.substring(semicolon + 1).split("/", -1)) {
            List<int[]> boards = new ArrayList<>();
            for (String encodedBoard : encodedStep.split(",", -1)) {
                if (encodedBoard.length() != GameRuleCore.CELLS) throw new IllegalArgumentException("board length mismatch");
                int[] board = new int[GameRuleCore.CELLS];
                for (int i = 0; i < board.length; i++) board[i] = digit(encodedBoard.charAt(i));
                boards.add(board);
            }
            steps.add(new GameRuleCore.Step(boards));
        }
        GameRuleCore.CompleteRoundFact fact = new GameRuleCore.CompleteRoundFact(entry, betType, scatterDelta,
                coinDelta, coinTransition, coinRewardCount, steps);
        GameRuleCore.validateStructure(fact);
        return fact;
    }

    public ResultUtil.Evaluation verify(String member) { return ResultUtil.evaluate(decode(member)); }
    public boolean supports(String member) { return member != null && member.startsWith(PREFIX); }

    private static String to36(int value) { return Integer.toString(value, 36).toUpperCase(); }
    private static int from36(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("empty base36 number");
        return Integer.parseInt(value, 36);
    }
    private static int digit(char value) {
        int digit = Character.digit(value, 36);
        if (digit < 0 || digit > 10) throw new IllegalArgumentException("invalid symbol digit");
        return digit;
    }
}
