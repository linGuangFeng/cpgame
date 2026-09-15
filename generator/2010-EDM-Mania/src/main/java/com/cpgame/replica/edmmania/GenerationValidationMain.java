package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

/** 10000 个新生成完整局 vs 付费开局样本分布。 */
public final class GenerationValidationMain {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        SecureRandom random = new SecureRandom();
        int[] opening = new int[13];
        int[] scatter = new int[8];
        int loss = 0, win = 0, special = 0, pages = 0, spins = 0, rejected = 0;
        int maxSteps = 0;
        while (loss + win + special < n) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, false, 12, 30);
            } catch (CompleteRoundFactory.RoundRejectedException ex) {
                rejected++;
                continue;
            }
            String encoded = codec.encode(generated.fact());
            RoundVerification v = codec.verify(encoded, 12, false, 30);
            if (v.multiplier().compareTo(generated.multiplier()) != 0) {
                throw new IllegalStateException("verify mismatch");
            }
            var first = generated.fact().spins().get(0).get(0);
            EdmManiaBoard board = new EdmManiaBoard(toArray(first.prop()), toArray(first.trl()),
                    first.grids(), first.gf(), first.sl());
            for (int s : board.getProp()) opening[s - 1]++;
            for (int s : board.getTrl()) opening[s - 1]++;
            int sc = EdmManiaResultUtil.evaluate(board, BigDecimal.ONE, 1, 2).getScatterCount();
            scatter[Math.min(sc, 7)]++;
            boolean isSpecial = generated.fact().spins().size() > 1;
            if (isSpecial) special++;
            else if (generated.multiplier().signum() == 0) loss++;
            else win++;
            spins += v.spins();
            pages += v.pages();
            maxSteps = Math.max(maxSteps, v.pages());
        }
        int cells = Arrays.stream(opening).sum();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("generated", n).put("rejectedOverlong", rejected)
                .put("loss", loss).put("ordinaryWin", win).put("special", special)
                .put("openingCells", cells).put("totalSpins", spins).put("totalPages", pages)
                .put("maxPages", maxSteps).put("rulesHash", EdmManiaRulesMetadata.HASH);
        ObjectNode dist = root.putObject("openingSymbolPct");
        int[] sample = EdmManiaBoardGenerator.DEFAULT_NORMAL_WEIGHTS;
        int sampleTotal = Arrays.stream(sample).sum();
        boolean pass = true;
        for (int i = 0; i < 13; i++) {
            double got = 100.0 * opening[i] / cells;
            double exp = 100.0 * sample[i] / sampleTotal;
            dist.put(Integer.toString(i + 1), String.format("%.4f (sample %.4f)", got, exp));
            if (Math.abs(got - exp) > 8.0 && sample[i] > 50) pass = false;
        }
        ObjectNode scNode = root.putObject("openingScatterCounts");
        for (int i = 0; i < scatter.length; i++) scNode.put(Integer.toString(i), scatter[i]);
        root.put("corePass", pass);
        Path out = Path.of("reports/2010-EDM-Mania/generation-model-validation.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        if (!pass) System.exit(2);
    }

    private static int[] toArray(java.util.List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }
}
