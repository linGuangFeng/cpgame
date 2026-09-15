package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckyPandaResultUtilOracleTest {
    private static final BigDecimal BS = new BigDecimal("0.02");
    private static final int BL = 1;

    @Test
    void fixtureLossRound001HasZeroAward() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1H5", "1H4", "1J", "1H3", "1Q",
                "1T", "2H5", "2H3", "1H1",
                "1H2", "4H2", "1H2",
                "1H1", "1A", "1H4", "1H3", "1J", "1H2",
                "1H2", "2K", "3T",
                "1Q", "1H3", "1Scat", "1H1", "1J"));
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(board, BS, BL, 0);
        assertFalse(evaluation.hasWaysWin());
        assertEquals(0, evaluation.wa().compareTo(BigDecimal.ZERO.setScale(2)));
        assertEquals(1, evaluation.ss());
        assertTrue(evaluation.scatterTokens() < 4);
    }

    @Test
    void capturedRound11Step1H5TwoWays() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1H5", "1H5", "1Q", "1H2", "1H3",
                "1H3", "2H5", "1H3", "1H2", "1H3",
                "1Scat", "3H5", "1T", "1A",
                "1H4", "3H4", "1H4", "1H5",
                "1H2", "3H3", "1K", "1H5",
                "1H3", "1H4", "1H1", "1H5", "1H2"));
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(board, BS, BL, 0);
        assertEquals(List.of("H5"), evaluation.wskl());
        assertEquals(List.of(List.of(List.of(0, 1), List.of(11), List.of(21), List.of(33), List.of(43), List.of(53))),
                evaluation.wmkl());
        assertEquals(0, evaluation.wa().compareTo(new BigDecimal("0.60")));
        assertEquals(0, evaluation.ss());
    }

    @Test
    void capturedRound11Step5H2TwelveWaysWithRpx2() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1Q", "1Scat", "1H2", "1H2", "1H2",
                "1Scat", "1J", "1Pan", "1Q", "1H2", "1H2",
                "1H4", "1H2", "1H1", "1H5", "1H1", "1T",
                "1H2", "1K", "3H4", "1H4",
                "1H2", "1Q", "1H2", "3H3",
                "1H3", "1H3", "1H4", "1H1", "1H2"));
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(board, BS, BL, 2);
        assertEquals(List.of("H2"), evaluation.wskl());
        assertEquals(List.of(List.of(List.of(2, 3, 4), List.of(14, 15), List.of(21), List.of(30), List.of(40, 42), List.of(54))),
                evaluation.wmkl());
        assertEquals(0, evaluation.wa().compareTo(new BigDecimal("19.20")));
    }

    @Test
    void originalHttpDeliveriesRecomputeWaWhenPresent() throws Exception {
        Path capture = Path.of("D:/work/hd/cpgame/captures/41-Lucky-Panda/original-http-spin-index.jsonl");
        if (!Files.isRegularFile(capture)) return;
        Pattern rskl = Pattern.compile("\"rskl\":\\[(.*?)]");
        Pattern wa = Pattern.compile("\"wa\":(-?\\d+(?:\\.\\d+)?)");
        Pattern rpx = Pattern.compile("\"rpx\":(-?\\d+)");
        int checked = 0;
        int mismatch = 0;
        for (String line : Files.readAllLines(capture)) {
            Matcher rm = rskl.matcher(line);
            Matcher wm = wa.matcher(line);
            Matcher pm = rpx.matcher(line);
            if (!rm.find() || !wm.find() || !pm.find()) continue;
            List<String> tokens = new ArrayList<>();
            Matcher tm = Pattern.compile("\"([^\"]+)\"").matcher(rm.group(1));
            while (tm.find()) tokens.add(tm.group(1));
            LuckyPandaBoard board = LuckyPandaBoard.fromRskl(tokens);
            LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(
                    board, BS, BL, Integer.parseInt(pm.group(1)));
            BigDecimal expected = new BigDecimal(wm.group(1)).setScale(2);
            if (evaluation.wa().compareTo(expected) != 0) mismatch++;
            checked++;
        }
        assertEquals(3382, checked);
        assertEquals(0, mismatch);
    }
}
