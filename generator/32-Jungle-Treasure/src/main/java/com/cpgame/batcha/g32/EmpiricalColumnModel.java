package com.cpgame.batcha.g32;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class EmpiricalColumnModel {
    public static final String COLUMN_SHA256 = "d5b3350b52d208f4409bf99be394687011ddf3952fe9318cf282d9ca3618dddc";
    public static final String FILL_SHA256 = "70c9f3b8ac87ae9daa41375d4aef898a612b4818aea2087c762927c71163a68a";
    private static final EmpiricalColumnModel INSTANCE = new EmpiricalColumnModel();
    private final Map<String, List<Block>> columns = new HashMap<>();
    private final Map<String, Integer> columnTotals = new HashMap<>();
    private final Map<String, List<Block>> fills = new HashMap<>();
    private final Map<String, Integer> fillTotals = new HashMap<>();

    private EmpiricalColumnModel() {
        load("/jt32-column-model.tsv", COLUMN_SHA256, columns, columnTotals, true);
        load("/jt32-fill-tokens.tsv", FILL_SHA256, fills, fillTotals, false);
    }

    public static EmpiricalColumnModel instance() { return INSTANCE; }

    public List<String> drawReel(String entry, int column, SecureRandom random) {
        return draw(columns, columnTotals, entry + "|" + column, random).symbols();
    }

    public List<String> drawFill(String entry, int column, int remain, SecureRandom random) {
        List<Block> exact = fills.get(entry + "|" + column);
        if (exact == null || fillTotals.get(entry + "|" + column) == null) {
            return List.of(fallback(remain, column, random));
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            List<String> tokens = draw(fills, fillTotals, entry + "|" + column, random).symbols();
            String token = tokens.getFirst();
            int height = token.charAt(0) - '0';
            if (height >= 1 && height <= remain) return tokens;
        }
        return List.of(fallback(remain, column, random));
    }

    private static String fallback(int remain, int column, SecureRandom random) {
        List<String> pool = column == 0 || column == 5
            ? List.of("1T", "1J", "1Q", "1K", "1A", "1H6", "1H5", "1H4", "1H3", "1H2")
            : List.of("1T", "1J", "1Q", "1K", "1A", "1H6", "1H5", "1H4", "1H3", "1H2", "2T", "2Q", "1Scat");
        for (int i = 0; i < 12; i++) {
            String token = pool.get(random.nextInt(pool.size()));
            if (token.charAt(0) - '0' <= remain) return token;
        }
        return "1T";
    }

    private static Block draw(Map<String, List<Block>> slots, Map<String, Integer> totals, String key, SecureRandom random) {
        List<Block> blocks = slots.get(key);
        Integer total = totals.get(key);
        if (blocks == null || total == null || total <= 0) throw new IllegalArgumentException("Unobserved dealing entry " + key);
        int target = random.nextInt(total);
        int lo = 0, hi = blocks.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (target < blocks.get(mid).cumulative()) hi = mid; else lo = mid + 1;
        }
        return blocks.get(lo);
    }

    private static void load(String resource, String expected, Map<String, List<Block>> slots,
                             Map<String, Integer> totals, boolean column) {
        try (InputStream in = EmpiricalColumnModel.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            byte[] bytes = in.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!expected.equals(hash)) throw new IllegalStateException("model hash mismatch for " + resource);
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length < 4) continue;
                String key = fields[0] + "|" + fields[1];
                List<String> symbols = column ? List.of(fields[2].split(",")) : List.of(fields[2]);
                int count = Integer.parseInt(fields[3].trim());
                if (count <= 0) continue;
                int cumulative = totals.merge(key, count, Integer::sum);
                slots.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Block(symbols, cumulative));
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot load empirical model " + resource, error);
        }
    }

    private record Block(List<String> symbols, int cumulative) { }
}
