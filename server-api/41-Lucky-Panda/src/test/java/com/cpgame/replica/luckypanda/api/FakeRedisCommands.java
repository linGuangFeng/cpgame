package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.RedisKeyContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class FakeRedisCommands implements RedisCommands {
    private final Map<String, TreeSet<Integer>> zsets = new LinkedHashMap<>();
    private final Map<String, List<String>> lists = new LinkedHashMap<>();
    boolean closed;

    void seed(boolean special, int ratio, String member) {
        long id = 41L;
        String index = special ? RedisKeyContract.specialIndex(id) : RedisKeyContract.normalIndex(id);
        String list = special ? RedisKeyContract.specialList(id, ratio) : RedisKeyContract.normalList(id, ratio);
        zsets.computeIfAbsent(index, key -> new TreeSet<>()).add(ratio);
        lists.computeIfAbsent(list, key -> new ArrayList<>()).add(member);
    }

    @Override
    public Object command(String... args) throws IOException {
        if (closed) throw new IOException("redis closed");
        return switch (args[0]) {
            case "PING" -> "PONG";
            case "SELECT", "AUTH" -> "OK";
            case "ZRANGE" -> {
                TreeSet<Integer> set = zsets.getOrDefault(args[1], new TreeSet<>());
                List<Object> out = new ArrayList<>();
                for (Integer value : set) out.add(Integer.toString(value));
                yield out;
            }
            case "LLEN" -> (long) lists.getOrDefault(args[1], List.of()).size();
            case "LPOP" -> {
                List<String> list = lists.getOrDefault(args[1], new ArrayList<>());
                yield list.isEmpty() ? null : list.remove(0);
            }
            case "LINDEX" -> {
                List<String> list = lists.getOrDefault(args[1], List.of());
                int index = Integer.parseInt(args[2]);
                yield index >= 0 && index < list.size() ? list.get(index) : null;
            }
            default -> throw new IOException("unsupported " + args[0]);
        };
    }

    @Override
    public void close() {
        closed = true;
    }
}
