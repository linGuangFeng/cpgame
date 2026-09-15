package com.cpgame.batcha.g16;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Aggregated column-block distributions, never complete-Round fixtures. */
public final class EmpiricalColumnModel {
    public static final String SHA256 = "0d46ea41b87e22e433f21acdcf5f536b1fbcd1120200b25923a71ae3688757e2";
    private static final EmpiricalColumnModel INSTANCE = new EmpiricalColumnModel();
    private final Map<String, List<Block>> slots = new HashMap<>();
    private final Map<String, Integer> totals = new HashMap<>();

    private EmpiricalColumnModel() {
        try (InputStream in = getClass().getResourceAsStream("/jf16-column-model.tsv")) {
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
                if (symbols.size() != Integer.parseInt(f[2]) || count <= 0)
                    throw new IllegalArgumentException("Invalid model block");
                int cumulative = totals.merge(key, count, Integer::sum);
                slots.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Block(symbols, cumulative));
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot load empirical column model", e);
        }
    }

    public static EmpiricalColumnModel instance() { return INSTANCE; }

    public List<String> draw(String entry, int column, int length, SecureRandom random) {
        if (length == 0) return List.of();
        String key = entry + "|" + column + "|" + length;
        List<Block> blocks = slots.get(key);
        if (blocks == null) throw new IllegalArgumentException("Unobserved dealing entry " + key);
        int target = random.nextInt(totals.get(key));
        int lo = 0, hi = blocks.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (target < blocks.get(mid).cumulative()) hi = mid; else lo = mid + 1;
        }
        return blocks.get(lo).symbols();
    }

    /** Conservative observed bounds from 1,026 training Rounds, holdout excluded. */
    public static boolean legalSpecials(List<String> board) {
        int scatters = 0, multipliers = 0;
        for (int c = 0; c < 6; c++) {
            int columnScatters = 0, columnMultipliers = 0;
            for (int r = 0; r < 6; r++) {
                String s = board.get(c * 6 + r);
                if ("Scat".equals(s)) { scatters++; columnScatters++; }
                if (GameRuleCore.isMultiplier(s)) { multipliers++; columnMultipliers++; }
            }
            if (columnScatters > 1 || columnMultipliers > 2) return false;
        }
        return scatters <= 5 && multipliers <= 4;
    }
    private record Block(List<String> symbols, int cumulative) { }
}
