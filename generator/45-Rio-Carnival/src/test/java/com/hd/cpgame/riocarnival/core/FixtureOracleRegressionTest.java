package com.hd.cpgame.riocarnival.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** fixtures 只在测试阶段作为独立 oracle；正式源码和 JAR 不依赖该目录。 */
class FixtureOracleRegressionTest {
    @Test void capturedThirtyTwoFreeStepRoundsRemainLegalWithoutInventedLoaderLimit() throws Exception {
        Path root = Paths.get("..", "..", "fixtures", "45-Rio-Carnival", "complete-rounds").normalize();
        ObjectMapper mapper = new ObjectMapper();
        for (String name : new String[] {"round-000152.json", "round-000668.json"}) {
            JsonNode round = mapper.readTree(root.resolve(name).toFile());
            JsonNode steps = round.path("steps");
            assertEquals(33, steps.size(), name + " 应包含付费 Step 加 32 个免费 Step");
            assertEquals(32, steps.get(steps.size() - 1).path("response").path("nfsc").asInt(), name);
            assertEquals(32, steps.get(steps.size() - 1).path("response").path("fsn").asInt(), name);
            assertEquals(1, steps.get(steps.size() - 1).path("response").path("ss").asInt(), name);
        }
    }

    @Test void allCapturedBoardsBoundariesAndFullyVisibleAwardsMatchIndependentOracle() throws Exception {
        Path root = Paths.get("..", "..", "fixtures", "45-Rio-Carnival", "complete-rounds").normalize();
        ObjectMapper mapper = new ObjectMapper();
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }
        int stepCount = 0;
        int evaluatedAwards = 0;
        int wildWinningLines = 0;
        for (Path file : files) {
            JsonNode round = mapper.readTree(file.toFile());
            BigDecimal bs = round.path("request").path("bs").decimalValue();
            int bl = round.path("request").path("bl").asInt();
            JsonNode steps = round.path("steps");
            assertTrue(steps.size() >= 1, file.toString());
            int scheduled = 0;
            for (int i = 0; i < steps.size(); i++) {
                JsonNode response = steps.get(i).path("response");
                List<String> board = new ArrayList<String>();
                for (JsonNode symbol : response.path("rskl")) board.add(symbol.asText());
                assertEquals(GameRules.REELS * GameRules.ROWS, board.size(), file + " step " + i);
                int scatters = ResultUtil.scatterCount(board);
                if (i == 0) {
                    assertTrue(response.path("ba").decimalValue().signum() > 0);
                    assertEquals(1, response.path("ss").asInt());
                    scheduled = response.path("fsn").asInt();
                    assertEquals(scatters >= 3, scheduled > 0);
                } else {
                    assertEquals(0, response.path("ba").decimalValue().signum());
                    assertEquals(i, response.path("nfsc").asInt());
                    if (scatters >= 3) scheduled += GameRules.scatterAward(scatters);
                    assertEquals(scheduled, response.path("fsn").asInt());
                    assertEquals(i == scheduled ? 1 : 0, response.path("ss").asInt());
                }
                JsonNode wmkl = response.path("wmkl");
                if (allMatchCountsVisible(wmkl)) {
                    WinEvaluation actual = ResultUtil.evaluate(board, bs, bl, response.path("rpx").asInt());
                    assertEquals(expectedMatches(wmkl), actual.matches, file + " step " + i);
                    assertEquals(0, actual.award.compareTo(response.path("wa").decimalValue()), file + " step " + i);
                    evaluatedAwards++;
                    for (Map<String,Integer> one : actual.matches.values())
                        if (one.containsKey(GameRules.WILD)) wildWinningLines++;
                }
                stepCount++;
            }
            JsonNode terminal = steps.get(steps.size() - 1).path("response");
            assertEquals(1, terminal.path("ss").asInt());
            assertEquals(terminal.path("fsn").asInt(), terminal.path("nfsc").asInt());
        }
        assertEquals(1406, files.size());
        assertEquals(2306, stepCount);
        assertTrue(evaluatedAwards >= 2000);
        assertTrue(wildWinningLines > 0);
    }

    private static boolean allMatchCountsVisible(JsonNode wmkl) {
        if (wmkl.isArray()) return wmkl.size() == 0;
        if (!wmkl.isObject()) return false;
        Iterator<JsonNode> lines = wmkl.elements();
        while (lines.hasNext()) {
            Iterator<JsonNode> values = lines.next().elements();
            while (values.hasNext()) if (!values.next().isNumber()) return false;
        }
        return true;
    }

    private static Map<String,Map<String,Integer>> expectedMatches(JsonNode wmkl) {
        Map<String,Map<String,Integer>> expected = new LinkedHashMap<String,Map<String,Integer>>();
        if (!wmkl.isObject()) return expected;
        Iterator<Map.Entry<String,JsonNode>> lines = wmkl.fields();
        while (lines.hasNext()) {
            Map.Entry<String,JsonNode> line = lines.next();
            Map<String,Integer> one = new LinkedHashMap<String,Integer>();
            Iterator<Map.Entry<String,JsonNode>> symbols = line.getValue().fields();
            while (symbols.hasNext()) {
                Map.Entry<String,JsonNode> symbol = symbols.next();
                one.put(symbol.getKey(), symbol.getValue().asInt());
            }
            expected.put(line.getKey(), one);
        }
        return expected;
    }
}
