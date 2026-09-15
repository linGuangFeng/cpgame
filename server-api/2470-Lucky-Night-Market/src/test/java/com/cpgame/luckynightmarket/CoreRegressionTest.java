package com.cpgame.luckynightmarket;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Run with assertions unnecessary: every regression check throws on a mismatch. */
public final class CoreRegressionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        boundaries();
        randomizedCrossCheck();
        if (args.length != 1) throw new IllegalArgumentException("Pass independent original oracle-cases.tsv");
        originalCorpus(Path.of(args[0]));
        System.out.println("CoreRegressionTest passed; checks=" + checks);
    }

    private static void boundaries() {
        RoundFact.Step allWild = new RoundFact.Step(List.of(0,0,0,0,0,0,0,0,0), List.of(15,2,3), 0);
        equal(1000L, GameRuleCore.evaluate(allWild,false).units(), "All-wild pays 5*100*center");
        equal(5, GameRuleCore.evaluate(allWild,false).wins().size(), "Five all-wild lines");
        equal(10000L, GameRuleCore.evaluate(allWild,true).units(), "Feature applies the sum of all multipliers");
        RoundFact.Step sideTicket = new RoundFact.Step(List.of(0,0,0,0,0,0,0,0,0), List.of(0,2,0), 0);
        equal(1000L, GameRuleCore.evaluate(sideTicket,false).units(), "Side zero is cosmetic");
        RoundFact.Step zeroSum = new RoundFact.Step(List.of(0,0,0,0,0,0,0,0,0), List.of(0,0,0), 0);
        equal(500L, GameRuleCore.evaluate(zeroSum,true).units(), "Feature zero sum falls back to 1");
        fails(() -> GameRuleCore.evaluate(zeroSum,false), "Center ticket needs a wheel outcome");
        RoundFact.Step wheelWithLines = new RoundFact.Step(List.of(0,0,0,0,0,0,0,0,0), List.of(2,0,3), 200);
        equal(1500L, GameRuleCore.evaluate(wheelWithLines,false).units(), "Wheel also pays matrix wins");
        equal(new BigDecimal("120.00"), ResultUtil.stepCash(wheelWithLines,false,new BigDecimal("0.08"),1), "Exact decimal units");
        fails(() -> GameRuleCore.evaluate(wheelWithLines,true), "Feature cannot start a wheel");
        fails(() -> new RoundFact.Step(allWild.ps(),List.of(1,4,1),0), "Unconfigured multiplier rejected");
        fails(() -> new RoundFact.Step(allWild.ps(),List.of(1,0,1),1000), "Unobserved wheel prize rejected");
        List<Integer> input = new ArrayList<>(allWild.ps());
        RoundFact.Step frozen = new RoundFact.Step(input,List.of(1,1,1),0);
        input.set(0,6);
        equal(0, frozen.ps().get(0), "Step copies the input list");
        fails(() -> frozen.ps().set(0,1), "Step cannot be mutated");
        RoundFact.Step loss = new RoundFact.Step(List.of(1,2,3,4,5,6,1,2,3),List.of(1,1,1),0);
        RoundFact lossRound = new RoundFact(RoundFact.Mode.ORDINARY_LOSS,List.of(loss));
        String encoded = RoundCodec.encodeFull(lossRound);
        equal(22, encoded.length(), "Single-step minimum length");
        equal(lossRound, RoundCodec.decode(encoded), "Loss codec round trip");
        fails(() -> RoundCodec.decode(encoded+";"), "Trailing empty step rejected");
        fails(() -> RoundCodec.decode(encoded.replace(".0",".+0")), "Noncanonical wheel rejected");
        fails(() -> RoundCodec.decode(encoded.replace("LNM1", "LNM43")), "Old codec rejected");
        fails(() -> RoundCodec.decode(encoded.replace(".111.",".1１1.")), "Non-ASCII rejected");
        fails(() -> RoundCodec.encode(new RoundFact(RoundFact.Mode.ORDINARY_WIN,List.of(loss))), "Mode must agree with evaluated result");
        List<RoundFact.Step> feature = new ArrayList<>();
        feature.add(loss);
        for (int i=1;i<8;i++) feature.add(allWild);
        fails(() -> RoundCodec.encode(new RoundFact(RoundFact.Mode.LUCKY_FEATURE,feature)), "Over-cap round is rejected, never clipped");
        feature.replaceAll(ignored -> loss);
        feature.set(0, allWild);
        fails(() -> RoundCodec.encode(new RoundFact(RoundFact.Mode.LUCKY_FEATURE,feature)), "Feature start must lose");
    }

    private static void randomizedCrossCheck() {
        Random random = new Random(247020260914L);
        int[] values = {0,1,2,3,5,10,15};
        for (int i=0;i<100_000;i++) {
            List<Integer> ps = new ArrayList<>(9);
            List<Integer> muls = new ArrayList<>(3);
            for (int j=0;j<9;j++) ps.add(random.nextInt(7));
            for (int j=0;j<3;j++) muls.add(values[random.nextInt(values.length)]);
            boolean feature = random.nextBoolean();
            int wem = !feature && muls.get(1)==0 ? (random.nextBoolean()?100:200) : 0;
            RoundFact.Step step = new RoundFact.Step(ps,muls,wem);
            equal(GameRuleCore.evaluate(step,feature), ResultUtil.evaluate(step,feature), "Independent random settlement " + i);
        }
    }

    private static void originalCorpus(Path tsv) throws Exception {
        Map<String,List<RoundFact.Step>> rounds = new LinkedHashMap<>();
        Map<String,RoundFact.Mode> modes = new LinkedHashMap<>();
        int steps = 0, holdout = 0, featureBigWins = 0;
        List<String> oracleRows = Files.readAllLines(tsv);
        for (String line : oracleRows.subList(1, oracleRows.size())) {
            String[] row = line.split("\t", -1);
            if (row.length != 9) throw new AssertionError("Invalid oracle TSV row");
            RoundFact.Mode mode = RoundFact.Mode.valueOf(row[3]);
            RoundFact.Step step = new RoundFact.Step(csv(row[4]),csv(row[5]),Integer.parseInt(row[6]));
            boolean feature = mode == RoundFact.Mode.LUCKY_FEATURE;
            long expected = Long.parseLong(row[7]);
            String tag = row[0] + "/step-" + row[1];
            GameRuleCore.Evaluation generated = GameRuleCore.evaluate(step,feature);
            GameRuleCore.Evaluation settled = ResultUtil.evaluate(step,feature);
            equal(expected, generated.units(), tag + " generated units");
            equal(expected, settled.units(), tag + " settled units");
            List<Map<String,Object>> wins = generated.wins().stream()
                    .map(w -> Json.map("c",w.c(),"l",w.l(),"o",w.o(),"s",w.s())).toList();
            equal(Json.stringify(Json.parse(row[8])), Json.stringify(wins), tag + " provider win-line entries");
            equal(generated,settled,tag + " independent full evaluation");
            rounds.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(step);
            modes.put(row[0],mode);
            steps++;
            if (row[2].equals("holdout")) holdout++;
            if (feature && expected >= 50) featureBigWins++;
        }
        for (Map.Entry<String,List<RoundFact.Step>> entry : rounds.entrySet()) {
            RoundFact round = new RoundFact(modes.get(entry.getKey()),entry.getValue());
            GameRuleCore.validate(round);
            equal(round,RoundCodec.decode(RoundCodec.encodeFull(round)),entry.getKey() + " complete member round trip");
            equal(GameRuleCore.totalUnits(round),ResultUtil.totalUnits(round),entry.getKey() + " whole-round index units");
            RoundFact materialized=RoundCodec.decode(RoundCodec.encode(round));
            equal(round.mode(),materialized.mode(),"Marker mode preserved");
            equal(round.steps().size(),materialized.steps().size(),"Marker step count preserved");
            for(int i=0;i<round.steps().size();i++) {
                long award=ResultUtil.evaluate(round.steps().get(i),round.feature()).units();
                equal(award,ResultUtil.evaluate(materialized.steps().get(i),materialized.feature()).units(),"Marker original step payout");
                if(award>0 || round.feature()&&i==0)
                    equal(round.steps().get(i),materialized.steps().get(i),"Retain original wins and feature start");
            }
        }
        equal(3000,rounds.size(),"All original complete rounds");
        equal(3826,steps,"All original steps");
        if (holdout <= 100 || featureBigWins < 200) throw new AssertionError("Holdout and feature BigWin coverage missing");
        System.out.println("Original corpus: rounds="+rounds.size()+", steps="+steps+", holdoutSteps="+holdout+", featureBigWinSteps="+featureBigWins+", mismatches=0");
    }

    private static List<Integer> csv(String text) { return Arrays.stream(text.split(",")).map(Integer::parseInt).toList(); }
    private static void equal(Object expected,Object actual,String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label+": expected="+expected+", actual="+actual);
    }
    private static void fails(Runnable operation,String label) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError(label+": expected rejection");
    }
}
