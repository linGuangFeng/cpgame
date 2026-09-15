package com.cpgame.hiddenrealm.loader;

import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.ResultUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 100 origin holdout pages that did not participate in dealing-model counts. */
public final class OriginHoldout {
    private OriginHoldout() {}

    public static void check(GameRuleCore rules, ResultUtil util) throws Exception {
        List<String> lines = load();
        if (lines.size() < 100) throw new IllegalStateException("holdout too small " + lines.size());
        int checked = 0, skipped = 0;
        for (String line : lines) {
            String[] p = line.split("\\|", -1);
            if (p.length != 7) throw new IllegalStateException("holdout row " + line);
            int ts = Integer.parseInt(p[3]);
            String board25 = p[4];
            int expectedOdds = Integer.parseInt(p[5]);
            boolean skip = "1".equals(p[6]);
            int[][] board = parse(board25);
            rules.validateBoard(board);
            int got = util.pageOdds(board, ts);
            int core = rules.evaluatePage(board, ts).oddsSum();
            if (got != core) throw new IllegalStateException("oracle/core diverge " + line);
            if (skip) { skipped++; continue; }
            if (got != expectedOdds) throw new IllegalStateException("holdout odds " + got + " != " + expectedOdds + " " + line);
            checked++;
        }
        if (checked < 80) throw new IllegalStateException("holdout checked " + checked);
        System.out.printf("origin-holdout PASS pages=%d checked=%d skippedLordQuirk=%d%n", lines.size(), checked, skipped);
    }

    private static List<String> splitLines(String raw) {
        List<String> lines = new ArrayList<>();
        for (String s : raw.replace("\\n", "\n").split("\n")) if (!s.isBlank()) lines.add(s.trim());
        return lines;
    }

    private static int[][] parse(String raw) {
        if (raw.length() != 25) throw new IllegalArgumentException("board");
        int[][] b = new int[5][5];
        int i = 0;
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++) b[c][r] = raw.charAt(i++) - '0';
        return b;
    }

    private static List<String> load() throws Exception {
        InputStream in = OriginHoldout.class.getResourceAsStream("/holdout-pages.txt");
        if (in == null) {
            Path p = Path.of("src/test/resources/holdout-pages.txt");
            if (!Files.isRegularFile(p)) p = Path.of("../../../reports/1380-Hidden-Realm/holdout-pages.txt");
            if (!Files.isRegularFile(p)) p = Path.of("reports/1380-Hidden-Realm/holdout-pages.txt");
            if (!Files.isRegularFile(p)) throw new IllegalStateException("holdout-pages.txt missing");
            return splitLines(Files.readString(p, StandardCharsets.UTF_8));
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            for (String s; (s = r.readLine()) != null; ) sb.append(s).append('\n');
            return splitLines(sb.toString());
        }
    }
}
