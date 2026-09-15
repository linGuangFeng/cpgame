package com.cpgame.junglekings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent oracle: 1152 origin History details vs ResultUtil, never vs our own generator. */
class EvidenceOracleTest {
    private static final Pattern BOARD = Pattern.compile("\"rand_symbol_key_list\":\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern WIN = Pattern.compile("\"win_amount\":\"([0-9.]+)\"");
    private static final Pattern BET = Pattern.compile("\"bet_amount\":([0-9.]+)");

    @Test
    void allHistoryDetailsMatchIndependentSingleBoardSettlement() throws Exception {
        Path file = repoRoot().resolve("captures/2-Jungle-Kings/history-1to1.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertEquals(1152, lines.size());
        int wins = 0;
        int losses = 0;
        for (String line : lines) {
            Matcher boardMatch = BOARD.matcher(line);
            Matcher winMatch = WIN.matcher(line);
            Matcher betMatch = BET.matcher(line);
            assertTrue(boardMatch.find() && winMatch.find() && betMatch.find());
            List<String> board = parseBoard(boardMatch.group(1));
            BigDecimal bet = new BigDecimal(betMatch.group(1));
            BigDecimal expectedWin = new BigDecimal(winMatch.group(1));
            ResultUtil.Evaluation evaluation = ResultUtil.evaluateSingleBoard(board, bet);
            assertEquals(0, expectedWin.compareTo(evaluation.winAmount().setScale(2)),
                    "win_amount mismatch for " + board);
            if (expectedWin.signum() == 0) {
                assertEquals(RoundMode.LOSS, evaluation.mode());
                losses++;
            } else {
                assertEquals(RoundMode.WIN, evaluation.mode());
                assertEquals(List.of("PL0014"), evaluation.winPaylineKeys());
                wins++;
            }
        }
        assertEquals(952, losses);
        assertEquals(200, wins);
    }

    @Test
    void holdoutLast100AgreeWithTrainingEvaluator() throws Exception {
        Path file = repoRoot().resolve("captures/2-Jungle-Kings/history-1to1.jsonl");
        List<String> lines = Files.readAllLines(file);
        List<String> holdout = lines.subList(lines.size() - 100, lines.size());
        int mismatches = 0;
        for (String line : holdout) {
            Matcher boardMatch = BOARD.matcher(line);
            Matcher winMatch = WIN.matcher(line);
            Matcher betMatch = BET.matcher(line);
            boardMatch.find();
            winMatch.find();
            betMatch.find();
            ResultUtil.Evaluation evaluation = ResultUtil.evaluateSingleBoard(
                    parseBoard(boardMatch.group(1)), new BigDecimal(betMatch.group(1)));
            if (new BigDecimal(winMatch.group(1)).compareTo(evaluation.winAmount().setScale(2)) != 0) {
                mismatches++;
            }
        }
        assertEquals(0, mismatches);
    }

    private static List<String> parseBoard(String inner) {
        List<String> board = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"(S\\d+)\"").matcher(inner);
        while (matcher.find()) board.add(matcher.group(1));
        return board;
    }

    static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path path = cwd; path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("captures/2-Jungle-Kings/history-1to1.jsonl"))) return path;
        }
        throw new IllegalStateException("cannot locate repo root from " + cwd);
    }
}
