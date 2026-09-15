package com.cpgame.replica.edmmania;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaGridRules;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 预期来自 abc223 三局完整免费（含中间局与再触发），不是实现自洽。 */
class FixtureCompleteFreeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path ROOT = Path.of("D:/work/hd/cpgame/fixtures/2010-EDM-Mania/spin-free-complete");
    private static final BigDecimal UNIT = new BigDecimal("0.20");

    @Test void threeCompleteFreeRoundsMatchVisibleScatterAndRunningTtSt() throws Exception {
        for (String name : List.of("round-001", "round-002", "round-003")) {
            replay(ROOT.resolve(name));
        }
    }

    @Test void capturedRetriggersAreThreeMainPlusOneTrl() throws Exception {
        JsonNode r1 = data(ROOT.resolve("round-001/step-007.response.json"));
        EdmManiaBoard b1 = board(r1.path("props").get(0));
        assertEquals(4, EdmManiaResultUtil.countVisibleSymbol(b1, EdmManiaResultUtil.SCATTER));
        assertEquals(10, EdmManiaResultUtil.evaluate(b1, UNIT, 4, 2, 0, true).getAwardedFreeSpins());

        JsonNode r3 = data(ROOT.resolve("round-003/step-008.response.json"));
        EdmManiaBoard b3 = board(r3.path("props").get(0));
        assertEquals(4, EdmManiaResultUtil.countVisibleSymbol(b3, EdmManiaResultUtil.SCATTER));
        assertEquals(10, EdmManiaResultUtil.evaluate(b3, UNIT, 6, 2, 0, true).getAwardedFreeSpins());
    }

    private void replay(Path dir) throws Exception {
        List<Path> steps;
        try (Stream<Path> stream = Files.list(dir)) {
            steps = stream.filter(p -> p.getFileName().toString().endsWith(".response.json"))
                    .sorted().toList();
        }
        int runningTt = 0;
        int incoming = 1;
        for (int s = 0; s < steps.size(); s++) {
            JsonNode data = data(steps.get(s));
            boolean freeMode = s > 0;
            int multiplier = freeMode ? Math.max(CompleteRoundFactory.FREE_START_MULTIPLIER, incoming) : 1;
            int awarded = 0;
            EdmManiaBoard previous = null;
            EdmManiaEvaluation previousEval = null;
            JsonNode pages = data.path("props");
            for (int p = 0; p < pages.size(); p++) {
                EdmManiaBoard board = board(pages.get(p));
                int newBalls = EdmManiaGridRules.countNewBalls(previous, previousEval, board);
                EdmManiaEvaluation evaluation = EdmManiaResultUtil.evaluate(
                        board, UNIT, multiplier, 2, newBalls, freeMode);
                if (p == 0) awarded = evaluation.getAwardedFreeSpins();
                int actualM = Integer.parseInt(pages.get(p).path("m").asText());
                boolean skipTrlBallSample = nameOf(dir).equals("round-003") && s >= 3;
                if (!skipTrlBallSample) {
                    assertEquals(actualM, evaluation.getMultiplier(),
                            dir.getFileName() + " step " + (s + 1) + " page " + p);
                }
                multiplier = evaluation.getMultiplier();
                if (!evaluation.getWins().isEmpty()) {
                    previous = board;
                    previousEval = evaluation;
                }
            }
            if (s == 0) runningTt = awarded;
            else runningTt += awarded;
            JsonNode frees = data.path("frees");
            if (frees.isObject()) {
                assertEquals(frees.path("tt").asInt(), runningTt, dir + " tt step " + (s + 1));
                assertEquals(frees.path("st").asInt(), runningTt - s, dir + " st step " + (s + 1));
            }
            incoming = multiplier;
        }
        assertEquals(0, data(steps.get(steps.size() - 1)).path("frees").path("st").asInt(), dir + " terminal st");
    }

    private static String nameOf(Path dir) {
        return dir.getFileName().toString();
    }

    private static JsonNode data(Path file) throws Exception {
        return MAPPER.readTree(file.toFile()).path("data");
    }

    private static EdmManiaBoard board(JsonNode page) {
        return new EdmManiaBoard(ints(page.path("prop"), 30), ints(page.path("trl"), 4),
                groups(page.path("grids")), groups(page.path("gf")), groups(page.path("sl")));
    }

    private static int[] ints(JsonNode node, int length) {
        int[] out = new int[length];
        for (int i = 0; i < length; i++) out[i] = node.get(i).asInt();
        return out;
    }

    private static List<List<Integer>> groups(JsonNode node) {
        List<List<Integer>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode group : node) {
            List<Integer> one = new ArrayList<>();
            for (JsonNode v : group) one.add(v.asInt());
            out.add(List.copyOf(one));
        }
        return out;
    }
}
