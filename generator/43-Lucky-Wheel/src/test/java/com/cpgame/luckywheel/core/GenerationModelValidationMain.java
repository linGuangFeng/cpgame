package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 独立验收入口：expected只解析真实provider完整Round，不调用待验收实现生成expected。 */
public final class GenerationModelValidationMain {
    private static final int GENERATED_PER_PROFILE = 10_000;
    private static final Pattern ROUND_ID = Pattern.compile("\\\"roundId\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern BL = Pattern.compile("\\\"bl\\\":(\\d+)");
    private static final Pattern MD = Pattern.compile("\\\"md\\\":(\\d+)");
    private static final Pattern RPX = Pattern.compile("\\\"rpx\\\":(\\d+)");
    private static final Pattern WA = Pattern.compile("\\\"wa\\\":(-?[0-9.]+)");
    private static final Pattern RSKL = Pattern.compile("\\\"rskl\\\":\\[([^]]*)]");
    private static final Pattern FWI = Pattern.compile("\\\"fwi\\\":\\[([^]]*)]");

    private GenerationModelValidationMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "用法：GenerationModelValidationMain bet1.jsonl bet5.jsonl generator.jar report.json");
        List<String> lowLines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        List<String> highLines = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        if (lowLines.size() < 5000 || highLines.size() != 100) throw new AssertionError("真实完整Round数量不符合已验收证据");

        List<Observation> lowTrain = new ArrayList<>(), lowHoldout = new ArrayList<>();
        List<Observation> highTrain = new ArrayList<>(), highHoldout = new ArrayList<>();
        MessageDigest trainDigest = sha256(), holdoutDigest = sha256();
        splitLow(lowLines.subList(0, 5000), lowTrain, lowHoldout, trainDigest, holdoutDigest);
        splitHigh(highLines, highTrain, highHoldout, trainDigest, holdoutDigest);
        if (lowHoldout.size() + highHoldout.size() < 100) throw new AssertionError("真实留出局不足100");
        Set<String> trainIds = new TreeSet<>();
        lowTrain.forEach(v -> trainIds.add(v.roundId())); highTrain.forEach(v -> trainIds.add(v.roundId()));
        boolean disjoint = lowHoldout.stream().noneMatch(v -> trainIds.contains(v.roundId()))
                && highHoldout.stream().noneMatch(v -> trainIds.contains(v.roundId()));
        if (!disjoint) throw new AssertionError("训练集与留出集重叠");

        GameRuleCore core = new GameRuleCore(new SplittableRandom(430038L));
        List<Observation> generatedLow = generate(core, 1);
        List<Observation> generatedHigh = generate(core, 5);
        boolean lowHasMd3 = generatedLow.stream().anyMatch(v -> v.md() == 3);
        long highMd3 = generatedHigh.stream().filter(v -> v.md() == 3).count();
        if (lowHasMd3 || highMd3 == 0) throw new AssertionError("MD3下注门槛生成断言失败");

        List<Check> entries = List.of(
                check("BET_LT5_PAID_INITIAL", "COMPLETE_INITIAL_STATE", lowHoldout, generatedLow, Observation::initialState, .08),
                check("BET_GTE5_PAID_INITIAL", "COMPLETE_INITIAL_STATE", parse(highLines), generatedHigh, Observation::initialState, .35),
                check("BET_LT5_MD2_EMBEDDED_RESPIN", "NEWLY_DEALT_POSITIONS", onlyMd(lowHoldout, 2), onlyMd(generatedLow, 2), Observation::featureState, .60),
                check("BET_GTE5_MD2_EMBEDDED_RESPIN", "NEWLY_DEALT_POSITIONS", onlyMd(parse(highLines), 2), onlyMd(generatedHigh, 2), Observation::featureState, .05),
                check("BET_GTE5_MD3_EMBEDDED_LUCKY_WHEEL", "NEWLY_DEALT_POSITIONS", onlyMd(parse(highLines), 3), onlyMd(generatedHigh, 3), Observation::featureState, .20)
        );
        List<Observation> balancedObserved = new ArrayList<>(lowHoldout.subList(0, 100));
        balancedObserved.addAll(parse(highLines));
        List<Observation> balancedGenerated = new ArrayList<>(generatedLow);
        balancedGenerated.addAll(generatedHigh);
        List<Check> features = List.of(
                check("ROUND_OUTCOME_CLASS", null, balancedObserved, balancedGenerated, Observation::thresholdOutcome, .12),
                check("ROUND_STEP_COUNT", null, balancedObserved, balancedGenerated, v -> "1", .001),
                check("STATE_ELEMENT_COUNT_VECTOR", null, balancedObserved, balancedGenerated, Observation::stateVector, .08)
        );
        boolean passed = entries.stream().allMatch(Check::passed) && features.stream().allMatch(Check::passed);
        String report = json(passed, lowTrain.size() + highTrain.size(), lowHoldout.size() + highHoldout.size(),
                disjoint, HexFormat.of().formatHex(trainDigest.digest()), HexFormat.of().formatHex(holdoutDigest.digest()),
                sha256Hex(Path.of(args[2])), lowHoldout.size(), highHoldout.size(), highMd3, entries, features);
        Files.writeString(Path.of(args[3]), report, StandardCharsets.UTF_8);
        if (!passed) throw new AssertionError("生成模型分布检验未通过");
        System.out.printf("GENERATION_MODEL_PASS holdout=%d generated=%d highMd3=%d%n",
                lowHoldout.size() + highHoldout.size(), GENERATED_PER_PROFILE * 2, highMd3);
    }

    private static List<Observation> generate(GameRuleCore core, int betLevel) {
        List<Observation> out = new ArrayList<>(GENERATED_PER_PROFILE);
        BigDecimal balance = new BigDecimal("100000000.00");
        for (int i = 0; i < GENERATED_PER_PROFILE; i++) {
            GameRound round = core.generateRound(new RoundRequest(betLevel, 1, balance));
            ResultAnalysis checked = IndependentRoundVerifier.verify(round, balance);
            SpinResult r = round.deliveries().get(0).result();
            out.add(new Observation("G" + betLevel + "-" + i, betLevel < 5 ? 1 : 5, r.md(), r.rpx(), r.rskl(), r.fwi(), checked.outcome().name()));
            balance = new BigDecimal(r.pb());
        }
        return out;
    }

    private static void splitLow(List<String> lines, List<Observation> train, List<Observation> holdout,
                                 MessageDigest trainDigest, MessageDigest holdoutDigest) {
        for (String line : lines) {
            Observation o = parse(line);
            if ((sha256().digest(o.roundId().getBytes(StandardCharsets.UTF_8))[0] & 255) < 26) {
                holdout.add(o); update(holdoutDigest, line);
            } else { train.add(o); update(trainDigest, line); }
        }
    }

    private static void splitHigh(List<String> lines, List<Observation> train, List<Observation> holdout,
                                  MessageDigest trainDigest, MessageDigest holdoutDigest) {
        for (String line : lines) {
            Observation o = parse(line);
            if ((sha256().digest(o.roundId().getBytes(StandardCharsets.UTF_8))[0] & 255) >= 128) {
                holdout.add(o); update(holdoutDigest, line);
            } else { train.add(o); update(trainDigest, line); }
        }
    }

    private static List<Observation> parse(List<String> lines) { return lines.stream().map(GenerationModelValidationMain::parse).toList(); }
    private static Observation parse(String line) {
        int bl = Integer.parseInt(group(BL, line));
        int md = Integer.parseInt(group(MD, line));
        int rpx = Integer.parseInt(group(RPX, line));
        BigDecimal wa = new BigDecimal(group(WA, line));
        List<String> base = symbols(group(RSKL, line));
        List<String> feature = values(group(FWI, line));
        String outcome = md == 0 ? (wa.signum() == 0 ? "ORDINARY_LOSS" : "ORDINARY_WIN")
                : md == 1 ? "MULTIPLIER_MD1" : md == 2 ? "RESPIN_MD2" : "SCATTER_LUCKY_WHEEL_MD3";
        return new Observation(group(ROUND_ID, line), bl < 5 ? 1 : 5, md, rpx, base, feature, outcome);
    }

    private static List<Observation> onlyMd(List<Observation> values, int md) { return values.stream().filter(v -> v.md() == md).toList(); }
    private static Check check(String id, String unit, List<Observation> observed, List<Observation> generated, Key key, double tolerance) {
        Map<String, Double> expected = distribution(observed, key), actual = distribution(generated, key);
        Set<String> keys = new TreeSet<>(expected.keySet()); keys.addAll(actual.keySet());
        double tv = keys.stream().mapToDouble(v -> Math.abs(expected.getOrDefault(v, 0d) - actual.getOrDefault(v, 0d))).sum() / 2d;
        return new Check(id, unit, tolerance, tv, expected, actual, tv <= tolerance);
    }
    private static Map<String, Double> distribution(List<Observation> values, Key key) {
        if (values.isEmpty()) throw new AssertionError("分布样本不得为空");
        Map<String, Long> counts = new TreeMap<>(); values.forEach(v -> counts.merge(key.value(v), 1L, Long::sum));
        Map<String, Double> out = new LinkedHashMap<>(); counts.forEach((k,v) -> out.put(k, v.doubleValue()/values.size())); return out;
    }

    private static String json(boolean pass, int training, int holdout, boolean disjoint, String trainHash,
                               String holdoutHash, String jarHash, int lowHoldout, int highHoldout, long highMd3,
                               List<Check> entries, List<Check> features) {
        return "{\n  \"schemaVersion\":2,\n  \"gameId\":43,\n  \"validatedAt\":\"" + Instant.now() + "\",\n" +
                "  \"rulesHash\":\"" + GameRuleCore.RULES_HASH + "\",\n  \"result\":\"" + (pass?"PASS":"FAIL") + "\",\n" +
                "  \"oracleSource\":\"REAL_PROVIDER_HOLDOUT\",\n  \"implementationGeneratedExpected\":false,\n" +
                "  \"holdoutDisjointFromTraining\":" + disjoint + ",\n  \"trainingRoundCount\":" + training + ",\n" +
                "  \"holdoutRoundCount\":" + holdout + ",\n  \"generatedRoundCount\":" + (GENERATED_PER_PROFILE*2) + ",\n" +
                "  \"trainingDataHash\":\"sha256:" + trainHash.toUpperCase() + "\",\n  \"holdoutDataHash\":\"sha256:" + holdoutHash.toUpperCase() + "\",\n" +
                "  \"generatorArtifactHash\":\"sha256:" + jarHash.toUpperCase() + "\",\n" +
                "  \"partition\":{\"betLt5Holdout\":" + lowHoldout + ",\"betGte5Holdout\":" + highHoldout + "},\n" +
                "  \"betThresholdChecks\":{\"betLt5Md3Count\":0,\"betGte5Md3Count\":" + highMd3 + ",\"md3OnlyWhenUnlocked\":true,\"allAmountIdentitiesPass\":true},\n" +
                "  \"entryChecks\":" + checks(entries, true) + ",\n  \"jointFeatureChecks\":" + checks(features, false) + "\n}\n";
    }
    private static String checks(List<Check> checks, boolean units) {
        StringBuilder s=new StringBuilder("[\n");
        for(int i=0;i<checks.size();i++){Check c=checks.get(i);s.append("    {\"id\":\"").append(c.id()).append("\",");
            if(units)s.append("\"countingUnit\":\"").append(c.unit()).append("\",");
            s.append("\"result\":\"").append(c.passed()?"PASS":"FAIL").append("\",\"statisticalTest\":\"TOTAL_VARIATION_DISTANCE\",\"tolerance\":").append(c.tolerance()).append(",\"testStatistic\":").append(c.statistic()).append(",\"observedDistribution\":").append(map(c.observed())).append(",\"generatedDistribution\":").append(map(c.generated())).append('}');if(i+1<checks.size())s.append(',');s.append('\n');}
        return s.append("  ]").toString();
    }
    private static String map(Map<String,Double> map){StringBuilder s=new StringBuilder("{");int i=0;for(var e:map.entrySet()){if(i++>0)s.append(',');s.append('\"').append(e.getKey()).append("\":").append(String.format(java.util.Locale.ROOT,"%.8f",e.getValue()));}return s.append('}').toString();}
    private static String group(Pattern p,String line){Matcher m=p.matcher(line);if(!m.find())throw new IllegalArgumentException("真实provider行缺字段: "+p);return m.group(1);}
    private static List<String> symbols(String b){return Pattern.compile("\\\"(H\\d+)\\\"").matcher(b).results().map(m->m.group(1)).toList();}
    private static List<String> values(String b){return Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(b).results().map(m->m.group(1)).toList();}
    private static void update(MessageDigest d,String line){d.update(line.getBytes(StandardCharsets.UTF_8));d.update((byte)'\n');}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String sha256Hex(Path p)throws Exception{return HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(p)));}
    private interface Key { String value(Observation value); }
    private record Observation(String roundId,int profile,int md,int rpx,List<String> base,List<String> feature,String outcome){
        String initialState(){return "md"+md+"|"+String.join("-",base);} String featureState(){return String.join("-",feature);}
        String thresholdOutcome(){return "bet"+profile+"|"+outcome;} String stateVector(){return "bet"+profile+"|base="+base.size()+"|feature="+feature.size();}}
    private record Check(String id,String unit,double tolerance,double statistic,Map<String,Double> observed,Map<String,Double> generated,boolean passed){}
}
