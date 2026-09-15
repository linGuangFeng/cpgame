package com.cpgame.fishinggo.loader;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.ProtocolConstants;
import com.cpgame.fishinggo.core.ResultUtil;
import com.cpgame.fishinggo.core.RoundCodec;
import com.cpgame.fishinggo.core.RoundGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GenerationModelValidation {
    private static final int TRAIN_LOSS = 1015;
    private static final int TRAIN_WIN = 185;
    private static final int TRAIN_SPECIAL = 41;
    private static final int TRAIN_TOTAL = 1241;

    public static void main(String[] args) throws Exception {
        ResultUtil util = new ResultUtil();
        OriginHoldout.check(util);
        RoundGenerator generator = new RoundGenerator(new SecureRandom());
        RoundCodec codec = new RoundCodec();
        int loss = 0, win = 0, special = 0, pages = 0, unique = 0;
        int[] sc = new int[8];
        java.util.Set<String> facts = new java.util.HashSet<>();
        for (int i = 0; i < 10000; i++) {
            CompleteRound round = generator.natural();
            ResultUtil.Analysis a = util.analyze(round);
            util.requireCaps(round.steps().get(0).board(), false);
            for (int p = 1; p < round.steps().size(); p++) util.requireCaps(round.steps().get(p).board(), true);
            String member = codec.encode(round);
            CompleteRound back = codec.decode(member, generator);
            if (!member.equals(codec.encode(back))) throw new IllegalStateException("codec");
            if (!member.startsWith("FG1|") || member.startsWith("FG1|{")) throw new IllegalStateException("member");
            facts.add(member);
            switch (a.outcome()) {
                case LOSS -> {
                    if (round.steps().size() != 1 || a.odds() != 0) throw new IllegalStateException("loss shape");
                    loss++;
                }
                case WIN -> {
                    if (round.steps().size() != 1 || a.odds() <= 0) throw new IllegalStateException("win shape");
                    win++;
                }
                case FREE_SPINS -> {
                    if (round.steps().size() != 13) throw new IllegalStateException("free steps");
                    int n = util.scatter(round.steps().get(0).board());
                    if (n < 5 || n > 7) throw new IllegalStateException("entry scatter");
                    special++;
                    sc[n]++;
                }
            }
            pages += a.deliveries();
        }
        unique = facts.size();
        double dLoss = Math.abs(loss / 10000.0 - TRAIN_LOSS / (double) TRAIN_TOTAL);
        double dWin = Math.abs(win / 10000.0 - TRAIN_WIN / (double) TRAIN_TOTAL);
        double dSpecial = Math.abs(special / 10000.0 - TRAIN_SPECIAL / (double) TRAIN_TOTAL);
        double maxDiff = Math.max(dLoss, Math.max(dWin, dSpecial));
        if (maxDiff > 0.03) throw new IllegalStateException("distribution drift " + maxDiff);
        Path out = resolveReport();
        String json = """
                {
                  "gameId": 54,
                  "gameName": "Fishing GO",
                  "rulesHash": "%s",
                  "status": "PASS",
                  "oracle": "Origin holdout pages vs independent ResultUtil 243-ways; generated rounds rebuilt by ResultUtil; FG1 codec roundtrip",
                  "usesImplementationGeneratedExpected": false,
                  "trainingComplete": %d,
                  "holdoutRounds": 100,
                  "holdoutRef": "reports/54-Fishing-GO/holdout-100.json",
                  "holdoutPagesRef": "reports/54-Fishing-GO/holdout-pages.txt",
                  "newGeneratedRounds": 10000,
                  "generatedCounts": {
                    "ORDINARY_LOSS": %d,
                    "ORDINARY_WIN": %d,
                    "FREE_SPINS": %d
                  },
                  "generatedSteps": %d,
                  "uniqueGeneratedFacts": %d,
                  "freeEntryScatter": { "5": %d, "6": %d, "7": %d },
                  "trainingCounts": {
                    "ORDINARY_LOSS": %d,
                    "ORDINARY_WIN": %d,
                    "FREE_SPINS": %d
                  },
                  "distributionComparison": {
                    "ORDINARY_LOSS": { "sourcePercent": %.4f, "generatedPercent": %.4f, "absoluteDifference": %.6f },
                    "ORDINARY_WIN": { "sourcePercent": %.4f, "generatedPercent": %.4f, "absoluteDifference": %.6f },
                    "FREE_SPINS": { "sourcePercent": %.4f, "generatedPercent": %.4f, "absoluteDifference": %.6f }
                  },
                  "distributionMaxAbsoluteDifference": %.6f,
                  "distributionTolerance": 0.03,
                  "caps": {
                    "wildPaidPageMax": 5,
                    "wildFreePageMax": 3,
                    "scatterPaidPageMax": 7,
                    "scatterFreePageMax": 4,
                    "wildColumnMax": 2,
                    "scatterColumnMaxPaid": 2,
                    "scatterColumnMaxFree": 1,
                    "wildReels": [2, 3, 4],
                    "retriggerGenerated": false
                  },
                  "dealingModel": "reports/54-Fishing-GO/dealing-model.json",
                  "limitations": [
                    "FREE_SPINS_RETRIGGER is SAMPLE_INSUFFICIENT; generation keeps free scatter below 5",
                    "Original reel strips and RTP are not exposed; weights are empirical from 1241 training origin rounds"
                  ]
                }
                """.formatted(
                ProtocolConstants.RULES_HASH,
                TRAIN_TOTAL, loss, win, special, pages, unique, sc[5], sc[6], sc[7],
                TRAIN_LOSS, TRAIN_WIN, TRAIN_SPECIAL,
                100.0 * TRAIN_LOSS / TRAIN_TOTAL, 100.0 * loss / 10000.0, dLoss,
                100.0 * TRAIN_WIN / TRAIN_TOTAL, 100.0 * win / 10000.0, dWin,
                100.0 * TRAIN_SPECIAL / TRAIN_TOTAL, 100.0 * special / 10000.0, dSpecial,
                maxDiff);
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "generation-model-validation PASS generated=10000 loss=%d win=%d special=%d pages=%d unique=%d sc5/6/7=%d/%d/%d maxDiff=%.4f rulesHash=%s file=%s%n",
                loss, win, special, pages, unique, sc[5], sc[6], sc[7], maxDiff, ProtocolConstants.RULES_HASH, out);
    }

    private static Path resolveReport() {
        List<Path> candidates = List.of(
                Path.of("reports/54-Fishing-GO/generation-model-validation.json"),
                Path.of("../../reports/54-Fishing-GO/generation-model-validation.json"),
                Path.of("D:/work/hd/cpgame/reports/54-Fishing-GO/generation-model-validation.json"));
        for (Path p : candidates) {
            Path parent = p.toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isDirectory(parent)) return p.toAbsolutePath().normalize();
        }
        return candidates.get(candidates.size() - 1);
    }
}
