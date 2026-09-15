package com.cpgame.g1910.core;

import java.util.ArrayList;
import java.util.List;

/** Compact ASCII Redis member; it contains only facts needed to reconstruct one complete Round. */
public final class MinimalRoundFactCodec {
    private static final String PREFIX = "CH40";
    private MinimalRoundFactCodec() { }

    public static String encode(GameRuleCore.CompleteRound round) {
        char mode = switch (round.mode()) { case ORDINARY -> 'N'; case SMALL_GAME_1 -> 'S'; case FREE_REWARD -> 'F'; };
        StringBuilder out = new StringBuilder(PREFIX).append(mode).append(':')
            .append(round.freeTimes()).append(',').append(round.freeMultiplier()).append(':');
        appendBoard(out, round.paid().symbols());
        for (GameRuleCore.Step step : round.freeSteps()) { out.append('/'); appendBoard(out, step.symbols()); }
        return out.toString();
    }

    public static GameRuleCore.CompleteRound decode(String text) {
        if (text == null || !text.startsWith(PREFIX) || text.length() < 24 || text.charAt(5) != ':')
            throw new IllegalArgumentException("invalid Churrasco fact");
        GameRuleCore.Mode mode = switch (text.charAt(4)) {
            case 'N' -> GameRuleCore.Mode.ORDINARY; case 'S' -> GameRuleCore.Mode.SMALL_GAME_1;
            case 'F' -> GameRuleCore.Mode.FREE_REWARD; default -> throw new IllegalArgumentException("invalid mode");
        };
        int next = text.indexOf(':', 6);
        if (next < 0) throw new IllegalArgumentException("missing fact header");
        String[] header = text.substring(6, next).split(",");
        if (header.length != 2) throw new IllegalArgumentException("invalid fact header");
        int times = Integer.parseInt(header[0]), multiplier = Integer.parseInt(header[1]);
        String[] boards = text.substring(next + 1).split("/");
        List<GameRuleCore.Step> free = new ArrayList<>();
        for (int index = 1; index < boards.length; index++) free.add(new GameRuleCore.Step(parseBoard(boards[index])));
        return new GameRuleCore.CompleteRound(mode, new GameRuleCore.Step(parseBoard(boards[0])), free, times, multiplier);
    }

    private static void appendBoard(StringBuilder out, int[] board) { for (int symbol : board) out.append(Character.forDigit(symbol, 16)); }
    private static int[] parseBoard(String raw) {
        if (raw.length() != 15) throw new IllegalArgumentException("invalid board fact");
        int[] board = new int[15];
        for (int i=0;i<15;i++) { board[i]=Character.digit(raw.charAt(i),16); if(board[i]<1||board[i]>13)throw new IllegalArgumentException("invalid symbol fact"); }
        return board;
    }
}
