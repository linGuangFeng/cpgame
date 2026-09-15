package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ASCII member. Stores activated chessboards and the three stop symbols per board.
 * Bet, pay, multiplier and HTTP envelope are recomputed on decode.
 */
public final class MemberCodec {
    public static final String VERSION = "JK2V1";

    public byte[] encode(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) {
            throw new IllegalArgumentException("only raw gid 2 is supported");
        }
        StringBuilder ascii = new StringBuilder(VERSION);
        ascii.append('|').append(String.join(",", round.chessboards()));
        for (List<String> board : round.boards()) {
            List<String> reels = GameRuleCore.logicalReels(board);
            ascii.append('|').append(String.join(",", reels));
        }
        return ascii.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public CompleteRound decode(byte[] member) {
        if (member == null || member.length == 0) throw new IllegalArgumentException("member is empty");
        String ascii = new String(member, StandardCharsets.US_ASCII);
        String[] fields = ascii.split("\\|", -1);
        if (fields.length < 3 || !VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("unsupported Jungle Kings member");
        }
        List<String> chessboards = GameRuleCore.parseChessboards(fields[1]);
        if (fields.length != chessboards.size() + 2) {
            throw new IllegalArgumentException("member chessboard/board count mismatch");
        }
        List<List<String>> boards = new ArrayList<>(chessboards.size());
        for (int i = 0; i < chessboards.size(); i++) {
            String[] reels = fields[i + 2].split(",", -1);
            if (reels.length != 3) throw new IllegalArgumentException("member must contain 3 reel stops");
            boards.add(GameRuleCore.expandBoard(List.of(reels[0], reels[1], reels[2])));
        }
        return GameRuleCore.materialize(chessboards, boards, new BigDecimal("0.5"), 1);
    }

    public CompleteRound project(CompleteRound stored, List<String> requested, BigDecimal betSize, int betLevel) {
        List<List<String>> boards = new ArrayList<>(requested.size());
        for (String key : requested) {
            int index = stored.chessboards().indexOf(key);
            if (index < 0) throw new IllegalArgumentException("stored member does not contain " + key);
            boards.add(stored.boards().get(index));
        }
        return GameRuleCore.materialize(requested, boards, betSize, betLevel);
    }
}
