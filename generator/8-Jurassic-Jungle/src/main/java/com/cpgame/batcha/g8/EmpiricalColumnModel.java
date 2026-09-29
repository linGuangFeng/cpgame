package com.cpgame.batcha.g8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregated column-block distributions from 1,514 training complete paid Rounds. Holdout excluded. */
public final class EmpiricalColumnModel {
    public static final String SHA256 = "cdff9dbb5a305d39cca79259e13eaa1cbfb013fbf81f3ea2a1313f9d44038d84";
    private static final EmpiricalColumnModel INSTANCE = new EmpiricalColumnModel();
    private final Map<String, List<Block>> slots = new HashMap<>();
    private final Map<String, Integer> totals = new HashMap<>();
    private final Map<String, int[]> cellWeights = new HashMap<>();
    private final Map<String, Integer> cellTotals = new HashMap<>();
    private final Map<String, Long> observedNormal = new HashMap<>();
    private final Map<String, Long> observedSpecial = new HashMap<>();

    private EmpiricalColumnModel() {
        try (InputStream in = getClass().getResourceAsStream("/jj8-column-model.tsv")) {
            if (in == null) throw new IllegalStateException("Missing empirical column model");
            byte[] bytes = in.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!SHA256.equals(hash)) throw new IllegalStateException("Empirical model hash mismatch");
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                String key = f[0] + "|" + f[1] + "|" + f[2];
                List<String> symbols = List.of(f[3].split(","));
                int count = Integer.parseInt(f[4]);
                if (symbols.size() != Integer.parseInt(f[2]) || count <= 0) {
                    throw new IllegalArgumentException("Invalid model block");
                }
                int cumulative = totals.merge(key, count, Integer::sum);
                slots.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Block(symbols, cumulative));
                String cellKey = f[0] + "|" + f[1];
                int[] weights = cellWeights.computeIfAbsent(cellKey, ignored -> new int[9]);
                boolean special = isSpecialEntry(f[0]);
                for (String symbol : symbols) {
                    weights[symbolId(symbol)] += count;
                    (special ? observedSpecial : observedNormal).merge(symbol, (long) count, Long::sum);
                }
                cellTotals.merge(cellKey, count * symbols.size(), Integer::sum);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot load empirical column model", e);
        }
    }

    public static EmpiricalColumnModel configured(java.util.Map<String, Double> weights) {
        if (weights.isEmpty()) return INSTANCE;
        EmpiricalColumnModel model = new EmpiricalColumnModel();
        model.overrides = adjustmentRatios(model, weights);
        for (var entry : model.slots.entrySet()) {
            boolean special = !entry.getKey().startsWith("PAID_INITIAL|") && !entry.getKey().startsWith("BASE_REFILL|");
            double sum = 0;
            int previous = 0;
            java.util.List<WeightedBlock> blocks = new java.util.ArrayList<>();
            for (Block block : entry.getValue()) {
                double weight = block.cumulative() - previous;
                previous = block.cumulative();
                for (String symbol : block.symbols())
                    weight *= model.overrides.getOrDefault("generation.symbol." + symbol
                        + (special ? ".special-weight" : ".normal-weight"), 1.0);
                sum += weight;
                blocks.add(new WeightedBlock(block.symbols(), sum));
            }
            if (!Double.isFinite(sum) || sum <= 0) throw new IllegalArgumentException("Invalid model weights: " + entry.getKey());
            model.weighted.put(entry.getKey(), java.util.List.copyOf(blocks));
        }
        return model;
    }

    public static long observedWeight(String symbol, boolean special) {
        Long value = (special ? INSTANCE.observedSpecial : INSTANCE.observedNormal).get(symbol);
        if (value == null) throw new IllegalArgumentException("Unobserved symbol " + symbol);
        return value;
    }

    private static Map<String, Double> adjustmentRatios(EmpiricalColumnModel model, Map<String, Double> configured) {
        Map<String, Double> result = new HashMap<>();
        for (String symbol : List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9")) {
            for (boolean special : new boolean[]{false, true}) {
                String key = "generation.symbol." + symbol + (special ? ".special-weight" : ".normal-weight");
                double observed = observedWeight(symbol, special);
                double requested = configured.getOrDefault(key, observed);
                result.put(key, requested / observed);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isSpecialEntry(String entry) {
        return !entry.equals("PAID_INITIAL") && !entry.equals("BASE_REFILL");
    }
    private java.util.Map<String, Double> overrides = java.util.Map.of();
    private final java.util.Map<String, java.util.List<WeightedBlock>> weighted = new java.util.HashMap<>();
    private record WeightedBlock(List<String> symbols, double cumulative) { }
    private List<String> drawWeighted(String key, SecureRandom random) {
        var blocks = weighted.get(key);
        if (blocks == null) return null;
        double target = random.nextDouble() * blocks.getLast().cumulative();
        int low = 0, high = blocks.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (target < blocks.get(middle).cumulative()) high = middle; else low = middle + 1;
        }
        return blocks.get(low).symbols();
    }

    public static EmpiricalColumnModel instance() { return INSTANCE; }

    public List<String> draw(String entry, int column, int length, SecureRandom random) {
        if (length == 0) return List.of();
        String key = entry + "|" + column + "|" + length;
        List<String> adjusted = drawWeighted(key, random);
        if (adjusted != null) return adjusted;
        List<Block> blocks = slots.get(key);
        if (blocks != null) {
            int target = random.nextInt(totals.get(key));
            int lo = 0, hi = blocks.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (target < blocks.get(mid).cumulative()) hi = mid;
                else lo = mid + 1;
            }
            return blocks.get(lo).symbols();
        }
        List<String> drawn = new ArrayList<>(length);
        for (int i = 0; i < length; i++) drawn.add(drawCell(entry, column, random));
        return drawn;
    }

    public String drawCell(String entry, int column, SecureRandom random) {
        String key = entry + "|" + column;
        int[] weights = cellWeights.get(key);
        Integer total = cellTotals.get(key);
        if (weights == null || total == null || total <= 0) {
            key = "PAID_INITIAL|" + column;
            weights = cellWeights.get(key);
            total = cellTotals.get(key);
        }
        if (weights == null || total == null || total <= 0) {
            throw new IllegalArgumentException("Unobserved dealing entry " + entry + "|" + column);
        }
        boolean special = !entry.equals("PAID_INITIAL") && !entry.equals("BASE_REFILL");
        double[] adjusted = new double[weights.length];
        double weightedTotal = 0;
        for (int i = 0; i < weights.length; i++) {
            adjusted[i] = weights[i] * overrides.getOrDefault("generation.symbol.S" + (i + 1)
                + (special ? ".special-weight" : ".normal-weight"), 1.0);
            weightedTotal += adjusted[i];
        }
        double target = random.nextDouble() * weightedTotal;
        for (int i = 0; i < weights.length; i++) {
            target -= adjusted[i];
            if (target < 0) return "S" + (i + 1);
        }
        return "S9";
    }

    /** Evidenced Giant destinations: lows become S2-S5 only; S1 never observed. */
    public String transformLow(String low, SecureRandom random) {
        if (!GameRuleCore.isLow(low)) throw new IllegalArgumentException("only low symbols transform");
        int[] dest = GIANT.get(low);
        int total = 0;
        for (int value : dest) total += value;
        int target = random.nextInt(total);
        String[] highs = {"S2", "S3", "S4", "S5"};
        for (int i = 0; i < highs.length; i++) {
            target -= dest[i];
            if (target < 0) return highs[i];
        }
        return "S5";
    }

    /** Evidenced Fire overlay symbols from 151 fire-apply steps in the abc222 1000-round capture. S9 never chosen. */
    public String fireSymbol(SecureRandom random) {
        int target = random.nextInt(FIRE_TOTAL);
        for (Map.Entry<String, Integer> entry : FIRE.entrySet()) {
            target -= entry.getValue();
            if (target < 0) return entry.getKey();
        }
        return "S2";
    }

    private static int symbolId(String symbol) {
        if (symbol.length() != 2 || symbol.charAt(0) != 'S') throw new IllegalArgumentException(symbol);
        int id = symbol.charAt(1) - '1';
        if (id < 0 || id > 8) throw new IllegalArgumentException(symbol);
        return id;
    }

    private static final Map<String, int[]> GIANT = giant();
    private static final Map<String, Integer> FIRE = fire();
    private static final int FIRE_TOTAL = FIRE.values().stream().mapToInt(Integer::intValue).sum();

    private static Map<String, int[]> giant() {
        Map<String, int[]> map = new LinkedHashMap<>();
        // counts S2,S3,S4,S5 from training extra→board
        map.put("S6", new int[]{759, 735, 666, 757});
        map.put("S7", new int[]{732, 791, 712, 711});
        map.put("S8", new int[]{744, 739, 628, 775});
        map.put("S9", new int[]{752, 786, 691, 717});
        return Map.copyOf(map);
    }

    private static Map<String, Integer> fire() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("S1", 97);
        map.put("S4", 11);
        map.put("S7", 9);
        map.put("S3", 8);
        map.put("S2", 8);
        map.put("S6", 8);
        map.put("S5", 5);
        map.put("S8", 5);
        return Map.copyOf(map);
    }

    private record Block(List<String> symbols, int cumulative) { }
}
