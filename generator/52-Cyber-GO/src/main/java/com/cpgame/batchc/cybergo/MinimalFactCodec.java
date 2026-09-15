package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.VISIBLE_CELLS;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Redis member只保存每个Delivery的15格盘面；所有派生结果必须重新反推。 */
public final class MinimalFactCodec {
    private static final Map<String, String> ENCODE = Map.of(
            "S1", "0", "S2", "1", "S3", "2", "S4", "3", "A", "4",
            "K", "5", "Q", "6", "J", "7", "WILD", "8", "SC", "9");
    private static final Map<String, String> DECODE = reverse(ENCODE);

    public CyberGoModels.MinimalRoundFacts extract(CyberGoModels.CompleteRound round) {
        ResultUtil.validateCompleteRound(round);
        return new CyberGoModels.MinimalRoundFacts(round.deliveries().stream().map(CyberGoModels.Step::rskl).toList());
    }

    public boolean redisEncodingEnabled() { return true; }

    public byte[] encodeForRedis(CyberGoModels.MinimalRoundFacts facts) {
        StringBuilder result = new StringBuilder(facts.boards().size() * (VISIBLE_CELLS + 1));
        for (int boardIndex = 0; boardIndex < facts.boards().size(); boardIndex++) {
            List<String> board = facts.boards().get(boardIndex);
            if (board.size() != VISIBLE_CELLS) throw new IllegalArgumentException("Redis盘面必须为15格");
            if (boardIndex > 0) result.append('|');
            for (String symbol : board) {
                String code = ENCODE.get(symbol);
                if (code == null) throw new IllegalArgumentException("未确认符号: " + symbol);
                result.append(code);
            }
        }
        return result.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public CyberGoModels.MinimalRoundFacts decodeFromRedis(byte[] member) {
        if (member == null || member.length == 0) throw new IllegalArgumentException("Redis member不能为空");
        String text = new String(member, StandardCharsets.US_ASCII);
        String[] encodedBoards = text.split("\\|", -1);
        List<List<String>> boards = new ArrayList<>(encodedBoards.length);
        for (String encoded : encodedBoards) {
            if (encoded.length() != VISIBLE_CELLS) throw new IllegalArgumentException("Redis盘面编码长度必须为15");
            List<String> board = new ArrayList<>(VISIBLE_CELLS);
            for (int offset = 0; offset < encoded.length(); offset++) {
                String symbol = DECODE.get(encoded.substring(offset, offset + 1));
                if (symbol == null) throw new IllegalArgumentException("Redis member包含未知符号码");
                board.add(symbol);
            }
            boards.add(List.copyOf(board));
        }
        return new CyberGoModels.MinimalRoundFacts(boards);
    }

    private static Map<String, String> reverse(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((symbol, code) -> result.put(code, symbol));
        return Map.copyOf(result);
    }
}
