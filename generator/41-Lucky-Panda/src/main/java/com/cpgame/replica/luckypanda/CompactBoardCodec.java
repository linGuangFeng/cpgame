package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/** Fixed wire-symbol mapping shared by the loader and consumer. Never configurable. */
final class CompactBoardCodec {
    private static final Map<String, Character> SYMBOL_CODES = Map.ofEntries(
            Map.entry("Pan", 'P'), Map.entry("H1", 'F'), Map.entry("H2", 'G'),
            Map.entry("H3", 'H'), Map.entry("H4", 'I'), Map.entry("H5", 'L'),
            Map.entry("A", 'A'), Map.entry("K", 'K'), Map.entry("Q", 'Q'),
            Map.entry("J", 'J'), Map.entry("T", 'T'),
            Map.entry("Wild", 'W'), Map.entry("Scat", 'S'));
    private static final Map<Character, String> WIRE_SYMBOLS = SYMBOL_CODES.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private CompactBoardCodec() { }

    static String encode(LuckyPandaBoard board) {
        StringBuilder out = new StringBuilder();
        for (var reel : board.reels()) {
            for (var token : reel) {
                if (token.height() > 1) out.append(token.height());
                out.append(SYMBOL_CODES.get(token.symbol().wireName()));
            }
        }
        return out.toString();
    }

    static LuckyPandaBoard decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw new IllegalArgumentException("empty board");
        var tokens = new ArrayList<String>();
        for (int i = 0; i < encoded.length();) {
            int height = 1;
            char code = encoded.charAt(i++);
            if (code >= '2' && code <= '4') {
                height = code - '0';
                if (i == encoded.length()) throw new IllegalArgumentException("height missing symbol");
                code = encoded.charAt(i++);
            }
            String symbol = WIRE_SYMBOLS.get(code);
            if (symbol == null) throw new IllegalArgumentException("unknown compact symbol: " + code);
            tokens.add(height + symbol);
        }
        return LuckyPandaBoard.fromRskl(tokens);
    }
}
