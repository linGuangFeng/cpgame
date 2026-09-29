package com.cpgame.fishinggo.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** FG2 ASCII: one character per symbol; independent ordinary loss is '#'. */
public final class RoundCodec {
    private static final String PREFIX = "FG2|";
    private static final Map<String, Character> SYMBOL_TO_CODE = Map.of(
            "A", '0', "J", '1', "K", '2', "Q", '3', "S1", '4',
            "S2", '5', "S3", '6', "S4", '7', "SC", '8', "WILD", '9');
    private static final List<String> CODE_TO_SYMBOL =
            List.of("A", "J", "K", "Q", "S1", "S2", "S3", "S4", "SC", "WILD");

    public String encode(CompleteRound round) {
        if (isIndependentLoss(round)) return "#";
        StringJoiner pages = new StringJoiner(".", PREFIX, "");
        for (CompleteRound.Step step : round.steps()) {
            if (step.board().size() != ProtocolConstants.CELLS) throw new IllegalArgumentException("board width");
            StringBuilder board = new StringBuilder(ProtocolConstants.CELLS);
            for (String symbol : step.board()) {
                Character code = SYMBOL_TO_CODE.get(symbol);
                if (code == null) throw new IllegalArgumentException("symbol " + symbol);
                board.append(code);
            }
            pages.add(board);
        }
        return pages.toString();
    }

    public CompleteRound decode(String member, RoundGenerator generator) {
        if (member == null) throw new IllegalArgumentException("member");
        if (member.equals("#")) return generator.loss();
        if (member.startsWith(PREFIX)) return generator.rebuild(decodeCompact(member.substring(PREFIX.length())));
        if (member.startsWith("FG1|")) return generator.rebuild(decodeLegacy(member.substring(4)));
        throw new IllegalArgumentException("Fishing GO member");
    }

    private static boolean isIndependentLoss(CompleteRound round) {
        if (round.steps().size() != 1) return false;
        CompleteRound.Step step = round.steps().get(0);
        return step.rpx() == 1 && step.apx() == 1 && step.fsn() == 0 && step.nfsc() == 0
                && step.gt() == 1 && step.smallGameType() == 0 && step.ss() == 1
                && step.ba().compareTo(ProtocolConstants.MIN_TOTAL_BET) == 0
                && step.wa().compareTo(BigDecimal.ZERO) == 0
                && step.rwa().compareTo(BigDecimal.ZERO) == 0;
    }

    private static List<List<String>> decodeCompact(String payload) {
        List<List<String>> boards = new ArrayList<>();
        for (String page : payload.split("\\.", -1)) {
            if (page.length() != ProtocolConstants.CELLS) throw new IllegalArgumentException("board width");
            List<String> board = new ArrayList<>(ProtocolConstants.CELLS);
            for (int i = 0; i < page.length(); i++) {
                int code = page.charAt(i) - '0';
                if (code < 0 || code >= CODE_TO_SYMBOL.size()) throw new IllegalArgumentException("symbol code");
                board.add(CODE_TO_SYMBOL.get(code));
            }
            boards.add(List.copyOf(board));
        }
        return List.copyOf(boards);
    }

    private static List<List<String>> decodeLegacy(String payload) {
        List<List<String>> boards = new ArrayList<>();
        for (String page : payload.split(";", -1)) {
            List<String> board = List.of(page.split(",", -1));
            if (board.size() != ProtocolConstants.CELLS) throw new IllegalArgumentException("board width");
            boards.add(board);
        }
        return List.copyOf(boards);
    }
}
