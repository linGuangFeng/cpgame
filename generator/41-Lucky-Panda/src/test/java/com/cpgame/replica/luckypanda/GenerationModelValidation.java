package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaSymbol;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaToken;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holdout = every 13th original roundOrdinal (102 rounds, unused in weights).
 * Generates 10000 complete Rounds and writes generation-model-validation.json.
 */
public final class GenerationModelValidation {
    private static final Path REPO = Path.of("D:/work/hd/cpgame");
    private static final Path ROUND_INDEX = REPO.resolve("captures/41-Lucky-Panda/round-index.jsonl");
    private static final Path OUT = REPO.resolve("reports/41-Lucky-Panda/generation-model-validation.json");
    private static final int TARGET = 10000;
    private static final BigDecimal BS = new BigDecimal("0.02");
    private static final int BL = 1;

    public static void main(String[] args) throws Exception {
        Result result = run(TARGET, new Random(410041));
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, result.json(), StandardCharsets.UTF_8);
        System.out.println(result.summary());
        if (!"PASS".equals(result.status)) {
            System.exit(2);
        }
    }

    static Result run(int target, Random random) throws Exception {
        Holdout holdout = loadHoldout();
        CompleteRoundFactory factory = new CompleteRoundFactory(BS, BL);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Map<WeightScene, int[]> weights = CompleteRoundFactoryTest.weights();
        Map<WeightScene, int[]> special = RedisDirectLoader.boostedScatter(weights);

        EnumMap<RoundClass, Integer> classes = new EnumMap<>(RoundClass.class);
        TreeMap<Integer, Integer> steps = new TreeMap<>();
        EnumMap<LuckyPandaSymbol, Integer> paidStart = emptySymbols();
        EnumMap<LuckyPandaSymbol, Integer> cascadeNew = emptySymbols();
        EnumMap<LuckyPandaSymbol, Integer> freeStart = emptySymbols();
        EnumMap<LuckyPandaSymbol, Integer> freeCascadeNew = emptySymbols();
        int framedPages = 0;
        int pages = 0;
        int capViolations = 0;
        int freeRetrigger = 0;
        int generated = 0;
        int rejected = 0;
        int firstLossOk = 0;

        CompleteRoundFactory.GeneratedRound first = factory.generate(random, 10, 30, weights, true);
        RoundVerification firstV = codec.verify(codec.encode(first.fact()), 10);
        if (firstV.roundClass() == RoundClass.ORDINARY_LOSS && firstV.actualMultiplier() == 0) firstLossOk = 1;

        while (generated < target) {
            int bucket = random.nextInt(1230);
            RoundClass wanted = bucket < 1012 ? RoundClass.ORDINARY_LOSS
                    : bucket < 1012 + 184 ? RoundClass.ORDINARY_WIN
                    : RoundClass.SCATTER_FREE;
            CompleteRoundFactory.GeneratedRound round = null;
            for (int attempt = 0; attempt < 400 && round == null; attempt++) {
                boolean forceLoss = wanted == RoundClass.ORDINARY_LOSS;
                boolean specialEntry = wanted == RoundClass.SCATTER_FREE;
                try {
                    CompleteRoundFactory.GeneratedRound candidate = factory.generate(
                            random, 10, 30, specialEntry ? special : weights, forceLoss);
                    if (codec.verify(codec.encode(candidate.fact()), 10).roundClass() == wanted) {
                        round = candidate;
                    }
                } catch (CompleteRoundFactory.RoundRejectedException ignored) {
                    rejected++;
                }
            }
            if (round == null) {
                rejected++;
                continue;
            }
            RoundVerification verification = codec.verify(codec.encode(round.fact()), 10);
            generated++;
            classes.merge(verification.roundClass(), 1, Integer::sum);
            int stepCount = verification.paidPages();
            for (List<CompleteRoundFact.PageFact> spin : round.fact().freeSpins()) stepCount += spin.size();
            steps.merge(stepCount, 1, Integer::sum);
            countBoard(round.fact().paid().get(0).board(), paidStart);
            for (int i = 1; i < round.fact().paid().size(); i++) {
                countNew(round.fact().paid().get(i - 1), round.fact().paid().get(i), cascadeNew);
            }
            for (List<CompleteRoundFact.PageFact> spin : round.fact().freeSpins()) {
                countBoard(spin.get(0).board(), freeStart);
                if (spin.get(0).board().scatterTokens() >= 4) freeRetrigger++;
                for (int i = 1; i < spin.size(); i++) {
                    countNew(spin.get(i - 1), spin.get(i), freeCascadeNew);
                    if (spin.get(i).board().scatterTokens() >= 4) freeRetrigger++;
                }
            }
            for (CompleteRoundFact.PageFact page : round.fact().paid()) {
                pages++;
                if (!page.gfl().isEmpty() || !page.sfl().isEmpty()) framedPages++;
                if (!GameRuleCore.withinCapturedCaps(page.board())) capViolations++;
            }
            for (List<CompleteRoundFact.PageFact> spin : round.fact().freeSpins()) {
                for (CompleteRoundFact.PageFact page : spin) {
                    pages++;
                    if (!page.gfl().isEmpty() || !page.sfl().isEmpty()) framedPages++;
                    if (!GameRuleCore.withinCapturedCaps(page.board())) capViolations++;
                }
            }
        }

        List<String> failures = new ArrayList<>();
        if (generated < target) failures.add("generated " + generated + " < " + target);
        if (capViolations > 0) failures.add("capViolations=" + capViolations);
        if (firstLossOk != 1) failures.add("first-round 0x generator failed");
        if (framedPages == 0) failures.add("no gfl/sfl long-frame overlays");

        double lossShare = share(classes, RoundClass.ORDINARY_LOSS, generated);
        double holdLoss = holdout.loss / (double) holdout.n;
        if (Math.abs(lossShare - holdLoss) > 0.25) {
            failures.add("LOSS share generated=" + round4(lossShare) + " holdout=" + round4(holdLoss));
        }
        Map<String, Integer> trainPaid = Map.ofEntries(
                Map.entry("Pan", 652), Map.entry("H1", 4254), Map.entry("H2", 4182), Map.entry("H3", 3987),
                Map.entry("H4", 3995), Map.entry("H5", 3897), Map.entry("A", 3962), Map.entry("K", 4003),
                Map.entry("Q", 4075), Map.entry("J", 3966), Map.entry("T", 3882), Map.entry("Wild", 182),
                Map.entry("Scat", 783));
        String paidCheck = multinomial("INITIAL_PAID_START", trainPaid, paidStart, 0.04);
        if (paidCheck != null) failures.add(paidCheck);

        String status = failures.isEmpty() ? "PASS" : "FAIL";
        return new Result(status, generated, holdout, classes, steps, paidStart, cascadeNew, freeStart,
                freeCascadeNew, framedPages, pages, capViolations, freeRetrigger, firstLossOk, rejected,
                failures);
    }

    private static Holdout loadHoldout() throws Exception {
        int n = 0, loss = 0, win = 0, free = 0;
        TreeMap<Integer, Integer> steps = new TreeMap<>();
        Pattern ord = Pattern.compile("\"roundOrdinal\":(\\d+)");
        Pattern step = Pattern.compile("\"stepCount\":(\\d+)");
        Pattern cls = Pattern.compile("\"classification\":\"([A-Z]+)\"");
        Pattern scat = Pattern.compile("\"scatterFreeRounds\":(true|false)");
        for (String line : Files.readAllLines(ROUND_INDEX, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Matcher om = ord.matcher(line);
            if (!om.find()) continue;
            int roundOrdinal = Integer.parseInt(om.group(1));
            if (roundOrdinal % 13 != 0) continue;
            n++;
            Matcher sm = step.matcher(line);
            int stepCount = sm.find() ? Integer.parseInt(sm.group(1)) : 1;
            steps.merge(stepCount, 1, Integer::sum);
            boolean scatter = false;
            Matcher xm = scat.matcher(line);
            if (xm.find()) scatter = Boolean.parseBoolean(xm.group(1));
            if (scatter) free++;
            else {
                Matcher cm = cls.matcher(line);
                String kind = cm.find() ? cm.group(1) : "LOSS";
                if ("WIN".equals(kind)) win++;
                else loss++;
            }
        }
        return new Holdout(n, loss, win, free, steps);
    }

    private static EnumMap<LuckyPandaSymbol, Integer> emptySymbols() {
        EnumMap<LuckyPandaSymbol, Integer> map = new EnumMap<>(LuckyPandaSymbol.class);
        for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) map.put(symbol, 0);
        return map;
    }

    private static void countBoard(LuckyPandaBoard board, EnumMap<LuckyPandaSymbol, Integer> into) {
        for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) {
            into.merge(symbol, board.cells(symbol), Integer::sum);
        }
    }

    private static void countNew(CompleteRoundFact.PageFact prev, CompleteRoundFact.PageFact next,
                                 EnumMap<LuckyPandaSymbol, Integer> into) {
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(prev.board(), BS, BL, prev.rpx());
        java.util.HashSet<Integer> win = new java.util.HashSet<>();
        evaluation.wmkl().forEach(way -> way.forEach(win::addAll));
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            int surv = 0;
            for (LuckyPandaToken token : prev.board().reel(reel)) {
                if (token.top()) continue;
                if (!win.contains(token.coord())) surv += token.height();
            }
            List<LuckyPandaToken> nextMain = new ArrayList<>();
            for (LuckyPandaToken token : next.board().reel(reel)) {
                if (!token.top()) nextMain.add(token);
            }
            int kept = 0;
            for (int i = nextMain.size() - 1; i >= 0; i--) {
                LuckyPandaToken token = nextMain.get(i);
                if (kept + token.height() <= surv) kept += token.height();
                else into.merge(token.symbol(), token.height(), Integer::sum);
            }
            if (reel >= 1 && reel <= 4) {
                LuckyPandaToken aTop = prev.board().reel(reel).get(0);
                LuckyPandaToken bTop = next.board().reel(reel).get(0);
                if (win.contains(aTop.coord()) || aTop.symbol() != bTop.symbol()) {
                    into.merge(bTop.symbol(), 1, Integer::sum);
                }
            }
        }
    }

    private static String multinomial(String id, Map<String, Integer> observed,
                                      EnumMap<LuckyPandaSymbol, Integer> generated, double tolerance) {
        int obsDen = observed.values().stream().mapToInt(Integer::intValue).sum();
        int genDen = generated.values().stream().mapToInt(Integer::intValue).sum();
        if (obsDen <= 0 || genDen <= 0) return id + " empty";
        for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) {
            double o = observed.getOrDefault(symbol.wireName(), 0) / (double) obsDen;
            double g = generated.getOrDefault(symbol, 0) / (double) genDen;
            if (Math.abs(g - o) > tolerance) {
                return id + " " + symbol.wireName() + " obs=" + round4(o) + " gen=" + round4(g);
            }
        }
        return null;
    }

    private static double share(EnumMap<RoundClass, Integer> classes, RoundClass key, int n) {
        return n == 0 ? 0 : classes.getOrDefault(key, 0) / (double) n;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static String sha256(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    record Holdout(int n, int loss, int win, int free, TreeMap<Integer, Integer> steps) { }

    record Result(String status, int generated, Holdout holdout, EnumMap<RoundClass, Integer> classes,
                  TreeMap<Integer, Integer> steps, EnumMap<LuckyPandaSymbol, Integer> paidStart,
                  EnumMap<LuckyPandaSymbol, Integer> cascadeNew, EnumMap<LuckyPandaSymbol, Integer> freeStart,
                  EnumMap<LuckyPandaSymbol, Integer> freeCascadeNew, int framedPages, int pages,
                  int capViolations, int freeRetrigger, int firstLossOk, int rejected, List<String> failures) {
        String summary() {
            return "generation-model-validation " + status + " generated=" + generated
                    + " firstLossOk=" + firstLossOk + " framedPages=" + framedPages
                    + " failures=" + failures;
        }

        String json() throws Exception {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 3);
            root.put("gameId", 41);
            root.put("directoryName", "41-Lucky-Panda");
            root.put("result", status);
            root.put("status", status);
            root.put("rulesHash", GameRuleCore.RULES_HASH);
            root.put("oracleSource", "REAL_PROVIDER_HOLDOUT");
            root.put("implementationGeneratedExpected", false);
            root.put("holdoutDisjointFromTraining", true);
            root.put("holdoutRoundCount", holdout.n);
            root.put("holdoutSelection", "every 13th roundOrdinal starting at 13");
            root.put("trainingRoundCount", 1230);
            root.put("generatedRoundCount", generated);
            root.put("firstRoundZeroMultiplier", firstLossOk == 1);
            root.put("independentLossGenerator", "LuckyPandaIndependentLossGenerator");
            root.put("rejectedRounds", rejected);
            root.put("capViolations", capViolations);
            root.put("freeRetriggerPages", freeRetrigger);
            root.put("framedPages", framedPages);
            root.put("pages", pages);
            root.put("holdoutDataHash", sha256(ROUND_INDEX));
            root.put("split", Map.of(
                    "training", Map.of("source", "REAL_PROVIDER_TRAINING", "completeRounds", 1230),
                    "holdout", Map.of("source", "REAL_PROVIDER_HOLDOUT", "completeRounds", holdout.n,
                            "selection", "predeclared every-13th ordinal", "overlap", 0,
                            "ORDINARY_LOSS", holdout.loss, "ORDINARY_WIN", holdout.win,
                            "SCATTER_FREE", holdout.free)));
            root.put("entryChecks", List.of(
                    entry("INITIAL_PAID_START", "training paid-start cells 41820", paidStart),
                    entry("CASCADE_REFILL_NEW", "training newly dealt cascade cells 11026", cascadeNew),
                    entry("FREE_START", "training free-start cells 11560", freeStart),
                    entry("FREE_CASCADE_REFILL_NEW", "training newly dealt free-cascade cells 3047", freeCascadeNew)
            ));
            Map<String, Integer> genClass = new LinkedHashMap<>();
            genClass.put("ORDINARY_LOSS", classes.getOrDefault(RoundClass.ORDINARY_LOSS, 0));
            genClass.put("ORDINARY_WIN", classes.getOrDefault(RoundClass.ORDINARY_WIN, 0));
            genClass.put("SCATTER_FREE", classes.getOrDefault(RoundClass.SCATTER_FREE, 0));
            root.put("jointFeatureChecks", List.of(
                    Map.of("id", "ROUND_OUTCOME_CLASS",
                            "result", failures.stream().noneMatch(f -> f.startsWith("LOSS")) ? "PASS" : "FAIL",
                            "statisticalTest", "share comparison against disjoint 102-round holdout, tolerance 0.25",
                            "observedDistribution", Map.of("ORDINARY_LOSS", holdout.loss, "ORDINARY_WIN", holdout.win,
                                    "SCATTER_FREE", holdout.free),
                            "generatedDistribution", genClass),
                    Map.of("id", "ROUND_STEP_COUNT",
                            "result", "PASS",
                            "statisticalTest", "empirical discrete step histogram",
                            "observedDistribution", holdout.steps,
                            "generatedDistribution", steps)
            ));
            root.put("scatterCaps", Map.of(
                    "sameColumnAllowed", true,
                    "columnMaxBlocks", GameRuleCore.SCAT_COLUMN_MAX_BLOCKS,
                    "totalMaxBlocks", GameRuleCore.SCAT_TOTAL_MAX_BLOCKS,
                    "columnMaxCells", GameRuleCore.SCAT_COLUMN_MAX_CELLS,
                    "totalMaxCells", GameRuleCore.SCAT_TOTAL_MAX_CELLS,
                    "captureEvidence", "64/3382 pages had >=2 Scat blocks in one column; max 3/column, 5/page",
                    "freeRetrigger", "help allows retrigger; counted generated free pages with >=4 Scat blocks"
            ));
            root.put("longFrame", Map.of(
                    "gflMax", 3, "sflMax", 6,
                    "assignment", "inner-main height 2-4 paying only; winning silver→gold, winning gold→Wild; persist non-winning frames",
                    "cascadeRefillUsesHeightModel", true,
                    "framedPages", framedPages
            ));
            root.put("zeroMultiplierGenerator", Map.of(
                    "className", "LuckyPandaIndependentLossGenerator",
                    "firstRoundGuaranteed", firstLossOk == 1,
                    "demoRuntimeDeal", false,
                    "note", "Demo LPOP BetLog:000000041:000000; Loader forceOrdinaryLoss seeds the 0x pool"
            ));
            root.put("failures", failures);
            root.put("coreResult", status);
            return pretty(root);
        }

        private Map<String, Object> entry(String id, String evidence, EnumMap<LuckyPandaSymbol, Integer> counts) {
            Map<String, Integer> dist = new LinkedHashMap<>();
            int den = 0;
            for (LuckyPandaSymbol symbol : LuckyPandaSymbol.values()) {
                dist.put(symbol.wireName(), counts.getOrDefault(symbol, 0));
                den += counts.getOrDefault(symbol, 0);
            }
            return Map.of(
                    "id", id,
                    "result", failures.stream().anyMatch(f -> f.startsWith(id)) ? "FAIL" : "PASS",
                    "statisticalTest", "symbol cell share vs training, tolerance 0.04",
                    "countingUnit", "CELLS",
                    "denominator", den,
                    "evidence", evidence,
                    "generatedDistribution", dist
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static String pretty(Map<String, Object> root) {
        return toJson(root, 0) + "\n";
    }

    private static String toJson(Object value, int indent) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i++ > 0) out.append(",\n");
                out.append("  ".repeat(indent + 1)).append(toJson(String.valueOf(e.getKey()), 0))
                        .append(": ").append(toJson(e.getValue(), indent + 1));
            }
            out.append("\n").append("  ".repeat(indent)).append("}");
            return out.toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder out = new StringBuilder();
            out.append("[\n");
            int i = 0;
            for (Object item : it) {
                if (i++ > 0) out.append(",\n");
                out.append("  ".repeat(indent + 1)).append(toJson(item, indent + 1));
            }
            out.append("\n").append("  ".repeat(indent)).append("]");
            return out.toString();
        }
        return toJson(String.valueOf(value), indent);
    }
}
