package com.cpgame.g2110.generator;

import com.cpgame.g2110.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class RedisAuditMain {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]), db = Integer.parseInt(args[2]);
        Path output = Path.of(args[3]);
        GameRuleCore rules = new GameRuleCore();
        ResultUtil util = new ResultUtil(rules);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("gameId", 2110);
        report.put("host", host);
        report.put("database", db);
        Map<String, Object> keys = new TreeMap<>();
        int members = 0, checked = 0;
        try (Jedis j = new Jedis(host, port)) {
            j.connect();
            j.select(db);
            for (boolean special : new boolean[]{false, true}) {
                String index = RedisKeys.index(special);
                String type = j.type(index);
                long size = "zset".equals(type) ? j.zcard(index) : 0;
                keys.put(index, Map.of("type", type, "size", size));
                for (String ratio : j.zrange(index, 0, -1)) {
                    String list = RedisKeys.list(special, Integer.parseInt(ratio));
                    String listType = j.type(list);
                    long listSize = "list".equals(listType) ? j.llen(list) : 0;
                    keys.put(list, Map.of("type", listType, "size", listSize));
                    members += listSize;
                    for (String value : j.lrange(list, 0, Math.min(2, listSize - 1))) {
                        if (!value.chars().allMatch(c -> c >= 32 && c <= 126)) throw new IllegalStateException("non ASCII member");
                        var round = codec.decode(value);
                        rules.validate(round);
                        util.integerMultiplier(round);
                        checked++;
                    }
                }
            }
        }
        report.put("keys", keys);
        report.put("totalListMembers", members);
        report.put("sampleMembersDecodedAndRecalculated", checked);
        report.put("runtimeFixtureDependency", false);
        report.put("status", "PASS");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(output.toAbsolutePath());
    }
}
