package com.cpgame.fishinggo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** FG1 ASCII: boards only. Win/state rebuilt by ResultUtil. */
public final class RoundCodec {
    public String encode(CompleteRound round) {
        StringJoiner joiner = new StringJoiner(";", "FG1|", "");
        for (CompleteRound.Step step : round.steps()) joiner.add(String.join(",", step.board()));
        return joiner.toString();
    }

    public CompleteRound decode(String member, RoundGenerator generator) {
        if (member == null || !member.startsWith("FG1|")) throw new IllegalArgumentException("FG1 member");
        List<List<String>> boards = new ArrayList<>();
        for (String page : member.substring(4).split(";")) {
            List<String> board = List.of(page.split(",", -1));
            if (board.size() != 15) throw new IllegalArgumentException("board width");
            boards.add(board);
        }
        return generator.rebuild(boards);
    }
}
