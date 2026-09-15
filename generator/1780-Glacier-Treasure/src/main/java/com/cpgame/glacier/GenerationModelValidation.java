package com.cpgame.glacier;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;


/** Offline generation-model validation. Original fixtures are not a runtime source. */
public final class GenerationModelValidation {
    public static void main(String[] args) throws Exception {
        int n = args.length>0 ? Integer.parseInt(args[0]) : 10000;
        Path out = Path.of(args.length>1 ? args[1] : "generation-model-validation.json");
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        GameRuleCore core = new GameRuleCore();
        ResultUtil oracle = new ResultUtil();
        var table = ResultUtil.paytable();
        int loss=0, win=0, special=0, rejected=0, maxPages=0, maxScatter=0, maxWild=0;
        long t0=System.currentTimeMillis();
        for (int i=0;i<n;i++) {
            CompleteRoundFactory.GeneratedRound g;
            while (true) {
                try { g = factory.generate(random, false); break; }
                catch (CompleteRoundFactory.RoundRejectedException ex) { rejected++; }
            }
            String ascii = codec.encode(g.fact);
            if (ascii.charAt(0)=='{' || ascii.charAt(0)=='[') throw new IllegalStateException("JSON member");
            CompleteRoundFact back = codec.decode(ascii, g.fact.featureBuy());
            int ratio = CompleteRoundFactory.verifyRatio(back);
            if (ratio!=g.ratio) throw new IllegalStateException("ratio");
            if (g.special()) special++; else if (g.ratio==0) loss++; else win++;
            maxPages=Math.max(maxPages, g.maxPages);
            for (var spin: back.spins()) for (var b: spin) {
                maxScatter=Math.max(maxScatter, b.scatterUnits());
                maxWild=Math.max(maxWild, b.wildCount());
                if (b.scatterUnits()>GenerationModel.MAX_SCATTER_UNITS_BOARD)
                    throw new IllegalStateException("scatter cap");
                if (b.wildCount()>GenerationModel.MAX_WILD_BOARD) throw new IllegalStateException("wild cap");
                for (int c=0;c<6;c++) {
                    if (b.scatterUnitsOnReel(c)>GenerationModel.MAX_SCATTER_UNITS_REEL)
                        throw new IllegalStateException("scatter reel cap");
                    if (b.wildOnReel(c)>GenerationModel.MAX_WILD_REEL) throw new IllegalStateException("wild reel cap");
                }
                var eval=core.evaluate(b, BigDecimal.ONE, 1);
                oracle.verify(eval, oracle.evaluate(b, BigDecimal.ONE, 1, table));
            }
            if ((i+1)%500==0) System.out.printf("VALIDATED %d/%d loss=%d win=%d special=%d rejected=%d%n",
                i+1,n,loss,win,special,rejected);
        }
        if (maxScatter>GenerationModel.MAX_SCATTER_UNITS_BOARD) throw new IllegalStateException("scatter");
        var report=new LinkedHashMap<String,Object>();
        report.put("gameId",1780);
        report.put("generatedAt", Instant.now().toString());
        report.put("rulesHash", GameRuleCore.RULES_HASH);
        report.put("behaviorIds", GameRuleCore.BEHAVIOR_IDS);
        report.put("generatedCompleteRounds", n);
        report.put("elapsedMs", System.currentTimeMillis()-t0);
        report.put("counts", Map.of("loss",loss,"win",win,"special",special,"rejected",rejected));
        report.put("maxPages", maxPages);
        report.put("maxScatterUnits", maxScatter);
        report.put("maxWild", maxWild);
        report.put("caps", Map.of(
            "scatterUnitsBoard", GenerationModel.MAX_SCATTER_UNITS_BOARD,
            "scatterUnitsReel", GenerationModel.MAX_SCATTER_UNITS_REEL,
            "wildBoard", GenerationModel.MAX_WILD_BOARD,
            "wildReel", GenerationModel.MAX_WILD_REEL,
            "cascadePages", GenerationModel.MAX_CASCADE_PAGES));
        report.put("status", "PASS");
        report.put("coreChecks", "ResultUtil path oracle + cascade survivor order + codec round-trip + Redis-illegal JSON prefix");
        Files.writeString(out, Json.stringify(report)+"\n", StandardCharsets.UTF_8);
        System.out.println(Json.stringify(Map.of("status","PASS","n",n,"loss",loss,"win",win,"special",special,"report",out.toString())));
    }
}
