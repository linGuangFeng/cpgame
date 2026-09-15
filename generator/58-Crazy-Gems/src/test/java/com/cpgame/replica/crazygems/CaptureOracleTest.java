package com.cpgame.replica.crazygems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaptureOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void resultUtilMatchesEveryCapturedHistoryDetail() throws Exception {
        Path file = Path.of("../../captures/58-Crazy-Gems/history-details.jsonl").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(file), "missing capture " + file);
        int checked = 0;
        try (var lines = Files.lines(file)) {
            for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                JsonNode detail = JSON.readTree(it.next()).path("detail");
                String[] rskl = new String[9];
                JsonNode symbols = detail.path("rskl");
                for (int i = 0; i < 9; i++) rskl[i] = symbols.get(i).asText();
                CrazyGemsBoard board = new CrazyGemsBoard(rskl, detail.path("rpx").asInt());
                CrazyGemsEvaluation evaluation = CrazyGemsResultUtil.evaluate(board);
                assertEquals(objectSize(detail.path("wmkl")), evaluation.wmkl().size(), "wmkl size " + detail.path("bid"));
                for (Map.Entry<String, String> win : evaluation.wmkl().entrySet()) {
                    assertEquals(win.getValue(), detail.path("wmkl").path(win.getKey()).asText(),
                            "wmkl " + win.getKey() + " " + detail.path("bid"));
                }
                BigDecimal expected = new BigDecimal(detail.path("wa").asText());
                BigDecimal actual = CrazyGemsResultUtil.winAmount(evaluation,
                        new BigDecimal(detail.path("bs").asText()), detail.path("bl").asInt());
                assertEquals(0, expected.compareTo(actual), "wa " + detail.path("bid") + " expected " + expected + " actual " + actual);
                CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION, rskl, board.rpx());
                String member = new CompleteRoundCodec().encode(fact);
                assertEquals(evaluation.multiplierDeci(), new CompleteRoundCodec().verify(member).multiplierDeci());
                checked++;
            }
        }
        assertEquals(1160, checked);
    }

    private static int objectSize(JsonNode node) {
        int count = 0;
        var it = node.fieldNames();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }
}
