package com.cpgame.fishinggo.loader;

import com.cpgame.fishinggo.core.ResultUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OriginHoldout {
    private OriginHoldout() {}

    public static void check(ResultUtil util) throws Exception {
        List<String> lines = load();
        if (lines.size() < 100) throw new IllegalStateException("holdout too small " + lines.size());
        int checked = 0;
        for (String line : lines) {
            String[] p = line.split("\\|", -1);
            if (p.length < 5) throw new IllegalStateException(line);
            List<String> board = List.of(p[2].split(",", -1));
            BigDecimal expected = new BigDecimal(p[3]).stripTrailingZeros();
            int rpx = Integer.parseInt(p[4]);
            BigDecimal got = util.evaluate(board, rpx).payout().stripTrailingZeros();
            if (got.compareTo(expected) != 0)
                throw new IllegalStateException("holdout " + p[0] + " step " + p[1] + " " + got + " != " + expected);
            checked++;
        }
        System.out.printf("origin-holdout PASS pages=%d%n", checked);
    }

    private static List<String> load() throws Exception {
        InputStream in = OriginHoldout.class.getResourceAsStream("/holdout-pages.txt");
        if (in != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                for (String s; (s = r.readLine()) != null; ) if (!s.isBlank()) lines.add(s.trim());
                return lines;
            }
        }
        Path p = Path.of("src/test/resources/holdout-pages.txt");
        if (!Files.isRegularFile(p)) p = Path.of("reports/54-Fishing-GO/holdout-pages.txt");
        if (!Files.isRegularFile(p)) p = Path.of("../../../reports/54-Fishing-GO/holdout-pages.txt");
        return Files.readAllLines(p, StandardCharsets.UTF_8).stream().filter(s -> !s.isBlank()).toList();
    }
}
