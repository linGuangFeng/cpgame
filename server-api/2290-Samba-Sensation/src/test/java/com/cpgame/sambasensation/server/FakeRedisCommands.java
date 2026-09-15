package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.redis.RedisKeyContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class FakeRedisCommands implements RedisCommands {
    private final Map<String, List<String>> lists = new LinkedHashMap<>();
    private final Map<String, TreeSet<Integer>> indexes = new LinkedHashMap<>();

    void seed(boolean special, int multiplier, String... members) {
        String index = special ? RedisKeyContract.specialIndex(2290) : RedisKeyContract.normalIndex(2290);
        String key = special ? RedisKeyContract.specialList(2290, multiplier) : RedisKeyContract.normalList(2290, multiplier);
        indexes.computeIfAbsent(index, ignored -> new TreeSet<>()).add(multiplier);
        lists.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(List.of(members));
    }

    void seedFloor(int floor, int multiplier, String... members) {
        String index = RedisKeyContract.normalIndex(2290, floor);
        String key = RedisKeyContract.normalList(2290, multiplier, floor);
        indexes.computeIfAbsent(index, ignored -> new TreeSet<>()).add(multiplier);
        lists.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(List.of(members));
    }

    int size(boolean special, int multiplier) {
        String key = special ? RedisKeyContract.specialList(2290, multiplier) : RedisKeyContract.normalList(2290, multiplier);
        return lists.getOrDefault(key, List.of()).size();
    }

    @Override public Object command(String... args) {
        return switch (args[0]) {
            case "ZRANGE" -> new ArrayList<>(indexes.getOrDefault(args[1], new TreeSet<>())).stream().map(String::valueOf).toList();
            case "LRANGE" -> new ArrayList<>(lists.getOrDefault(args[1], List.of()));
            case "LINDEX" -> {
                List<String> values = lists.getOrDefault(args[1], List.of()); yield values.isEmpty() ? null : values.get(0);
            }
            case "LLEN" -> (long) lists.getOrDefault(args[1], List.of()).size();
            case "LREM" -> remove(args[1], args[3]);
            case "LPOP" -> {
                List<String> values = lists.get(args[1]); yield values == null || values.isEmpty() ? null : values.remove(0);
            }
            default -> throw new IllegalArgumentException("unsupported fake Redis command " + args[0]);
        };
    }

    private long remove(String key, String member) {
        List<String> values = lists.get(key);
        if (values == null) return 0L;
        int index = values.indexOf(member); if (index < 0) return 0L; values.remove(index); return 1L;
    }
    @Override public void close() throws IOException { }
}
