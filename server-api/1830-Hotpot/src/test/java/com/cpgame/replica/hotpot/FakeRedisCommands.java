package com.cpgame.replica.hotpot;

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
        long id = 1830L;
        String index = special ? RedisKeys.maryIndex(id) : RedisKeys.normalIndex(id);
        String list = special ? RedisKeys.maryList(id, ratio) : RedisKeys.normalList(id, ratio);
        zsets.computeIfAbsent(index, key -> new TreeSet<>()).add(ratio);
        lists.computeIfAbsent(list, key -> new ArrayList<>()).add(member);
    }

    @Override
    public Object command(String... args) throws IOException {
        if (closed) throw new IOException("redis closed");
        return switch (args[0]) {
            case "PING" -> "PONG";
            case "SELECT", "AUTH" -> "OK";
            case "ZREVRANGEBYSCORE" -> {
                if (args.length != 7 || !args[4].equals("LIMIT") || !args[5].equals("0") || !args[6].equals("1"))
                    throw new AssertionError("floor lookup must fetch one bucket");
                boolean exclusive = args[2].startsWith("(");
                int max = Integer.parseInt(exclusive ? args[2].substring(1) : args[2]);
                int min = Integer.parseInt(args[3]);
                yield zsets.getOrDefault(args[1], new TreeSet<>()).descendingSet().stream()
                        .filter(n -> n >= min && (exclusive ? n < max : n <= max)).limit(1).map(String::valueOf).toList();
            }
            case "ZRANGE" -> {
                TreeSet<Integer> set = zsets.getOrDefault(args[1], new TreeSet<>());
                List<Object> out = new ArrayList<>();
                for (Integer value : set) out.add(Integer.toString(value));
                yield out;
            }
            case "LLEN" -> (long) lists.getOrDefault(args[1], List.of()).size();
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
