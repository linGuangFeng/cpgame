package com.cpgame.g2110.core;

import java.nio.file.*;
import java.util.*;
import redis.clients.jedis.Jedis;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class RedisPoolEvidenceCheck {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(Path.of(args[0]))) { p.load(r); }
        try (Jedis j = new Jedis("192.168.10.3", 6379, 3000)) {
            j.connect();
            String password = p.getProperty("redis.password", "").trim();
            if (!password.isEmpty()) j.auth(password);
            j.select(15);
            var rules = new GameRuleCore();
            var util = new ResultUtil(rules);
            var codec = new MinimalRoundFactCodec();
            Map<String, Object> counts = new LinkedHashMap<>();
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (var kind : GameRuleCore.RoundKind.values()) byKind.put(kind.name(), 0);
            for (boolean special : new boolean[]{false, true}) {
                var choices = j.zrange(RedisKeys.index(special), 0, -1);
                int members = 0;
                for (String choice : choices) {
                    int unit = Integer.parseInt(choice);
                    for (String member : j.lrange(RedisKeys.list(special, unit), 0, -1)) {
                        if (!member.chars().allMatch(c -> c >= 32 && c < 127)) throw new IllegalStateException("non ASCII");
                        var round = codec.decode(member);
                        rules.validate(round);
                        if (util.integerMultiplier(round) != unit) throw new IllegalStateException("bucket mismatch");
                        if (RedisKeys.special(round.kind()) != special) throw new IllegalStateException("index mismatch");
                        if (util.totalUnits(round) != round.steps().stream().mapToLong(s -> rules.payoutUnits(s.board())).sum()) {
                            throw new IllegalStateException("oracle mismatch");
                        }
                        byKind.merge(round.kind().name(), 1, Integer::sum);
                        members++;
                    }
                }
                counts.put(special ? "MaryKeyList" : "PerKeyList", Map.of("membersVerified", members, "integerBuckets", choices.size()));
            }
            System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                "status", "PASS", "host", "192.168.10.3", "port", 6379, "database", 15,
                "normalIndex", RedisKeys.normalIndex(), "maryIndex", RedisKeys.maryIndex(),
                "pools", counts, "byKind", byKind, "mutation", "none")));
        }
    }
}
