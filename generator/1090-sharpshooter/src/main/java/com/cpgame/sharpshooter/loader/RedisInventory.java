package com.cpgame.sharpshooter.loader;

import com.cpgame.sharpshooter.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Read-only acceptance inventory for the delivered Redis pool. */
public final class RedisInventory {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: RedisInventory generator.properties report.json");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) { properties.load(reader); }
        int database = Integer.parseInt(properties.getProperty("redis.database", "15"));
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder().database(database).connectionTimeoutMillis(5000).socketTimeoutMillis(10000);
        String username = properties.getProperty("redis.username", "").trim();
        String password = properties.getProperty("redis.password", "").trim();
        if (!username.isEmpty()) config.user(username);
        if (!password.isEmpty()) config.password(password);
        String game = properties.getProperty("redis.game-id", "1090");
        RoundCodec codec = new RoundCodec();
        GameRuleCore rules = new GameRuleCore();
        Map<String, Integer> outcomes = new TreeMap<>();
        List<Map<String, Object>> buckets = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        int total = 0;
        boolean allAscii = true;
        boolean allDecodeAndValidate = true;
        try (Jedis jedis = new Jedis(properties.getProperty("redis.host"), Integer.parseInt(properties.getProperty("redis.port", "6379")), config.build())) {
            jedis.connect();
            for (boolean special : new boolean[]{false, true}) {
                String index = special ? RedisKeys.maryIndex(game) : RedisKeys.normalIndex(game);
                for (String entry : jedis.zrange(index, 0, -1)) {
                    int multiplier = Integer.parseInt(entry);
                    String key = special ? RedisKeys.maryList(game, multiplier) : RedisKeys.normalList(game, multiplier);
                    List<String> members = jedis.lrange(key, 0, -1);
                    String outcomeName = special ? "SPECIAL" : (multiplier == 0 ? "LOSS" : "WIN");
                    for (String member : members) {
                        total++;
                        outcomes.merge(outcomeName, 1, Integer::sum);
                        allAscii &= member.chars().allMatch(character -> character >= 32 && character <= 126);
                        hashes.add(hex(MessageDigest.getInstance("SHA-256").digest(member.getBytes(StandardCharsets.US_ASCII))));
                        try { rules.validateRound(codec.decode(member)); } catch (RuntimeException failure) { allDecodeAndValidate = false; }
                    }
                    buckets.add(Map.of("key", key, "outcome", outcomeName, "integerMultiplier", multiplier, "members", members.size()));
                }
            }
        }
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("databaseIs15", database == 15);
        checks.put("poolNonEmpty", total > 0);
        checks.put("allMembersAscii", allAscii);
        checks.put("allMembersDecodeAndValidate", allDecodeAndValidate);
        checks.put("allMembersUnique", hashes.size() == total);
        boolean pass = checks.values().stream().allMatch(Boolean.TRUE::equals);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("redis", Map.of("host", properties.getProperty("redis.host"), "port", Integer.parseInt(properties.getProperty("redis.port", "6379")), "database", database, "prefix", RedisKeys.prefix(game)));
        report.put("totalMembers", total);
        report.put("uniqueMemberHashes", hashes.size());
        report.put("outcomes", outcomes);
        report.put("buckets", buckets);
        report.put("checks", checks);
        report.put("status", pass ? "PASS" : "FAIL");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), report);
        if (!pass) throw new IllegalStateException("Redis inventory failed");
    }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
}
