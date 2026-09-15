package com.cpgame.luckydragon.loader;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.MinimalFactCodec;
import com.cpgame.luckydragon.core.ResultUtil;
import com.cpgame.luckydragon.core.RoundFacts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 对正式 Loader 入口执行隔离 RESP2 Redis 合同：实际生成、分桶、原子写入、裁剪和逐 member 复核。 */
public final class RedisLoaderEndToEndTestMain {
    private RedisLoaderEndToEndTestMain() { }

    public static void main(String[] args) throws Exception {
        Path delivered = Path.of(args.length == 0
            ? "generator/42-Lucky-Dragon/dist/generator.properties" : args[0]);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            InMemoryRedis redis = new InMemoryRedis(listener);
            Thread server = new Thread(redis, "gid42-isolated-resp2-redis");
            server.start();
            Path config = isolatedConfig(delivered, listener.getLocalPort());
            LoaderMain.RunResult result = new LoaderMain.RedisLoader().run(config);
            server.join(5000);
            if (server.isAlive()) throw new AssertionError("isolated Redis did not terminate");
            if (redis.failure != null) throw new AssertionError("isolated Redis failure", redis.failure);
            if (result.redisGameId() != 42 || result.writtenMembers() != 30
                || result.lossMembers() != 10 || result.normalMembers() != 10 || result.specialMembers() != 10) {
                throw new AssertionError("formal Loader target mismatch: " + result);
            }
            redis.assertContract();
            System.out.printf(Locale.ROOT,
                "RedisLoaderEndToEndTestMain PASS written=%d batches=%d attempts=%d lists=%d atomicExec=%d%n",
                result.writtenMembers(), result.batches(), result.attempts(), redis.lists.size(), redis.atomicExecCount);
        }
    }

    private static Path isolatedConfig(Path source, int port) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { properties.load(reader); }
        properties.setProperty("redis.host", "127.0.0.1");
        properties.setProperty("redis.port", Integer.toString(port));
        properties.setProperty("redis.database", "0");
        properties.setProperty("generation.normal-count", "10");
        properties.setProperty("generation.special-count", "10");
        properties.setProperty("generation.batch-size", "4");
        properties.setProperty("generation.max-members-per-multiplier", "10");
        Path file = Files.createTempFile("gid42-isolated-generator-", ".properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "gid42 isolated Redis contract");
        }
        return file;
    }

    private static final class InMemoryRedis implements Runnable {
        private final ServerSocket listener;
        private final Map<String,Deque<String>> lists = new LinkedHashMap<>();
        private final Map<String,Set<String>> sortedSets = new LinkedHashMap<>();
        private final List<String[]> transaction = new ArrayList<>();
        private volatile Throwable failure;
        private int atomicExecCount;

        private InMemoryRedis(ServerSocket listener) { this.listener = listener; }

        @Override public void run() {
            try (Socket socket = listener.accept()) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                boolean multi = false;
                for (;;) {
                    String[] command = readCommand(input);
                    if (command == null) break;
                    String op = command[0].toUpperCase(Locale.ROOT);
                    if (multi && !"EXEC".equals(op)) {
                        transaction.add(command);
                        write(output, "+QUEUED\r\n");
                    } else if ("PING".equals(op)) write(output, "+PONG\r\n");
                    else if ("SELECT".equals(op)) write(output, "+OK\r\n");
                    else if ("MULTI".equals(op)) {
                        multi = true;
                        transaction.clear();
                        write(output, "+OK\r\n");
                    } else if ("EXEC".equals(op)) {
                        if (!multi) throw new IOException("EXEC without MULTI");
                        validateAtomicGroup(transaction);
                        List<String> replies = new ArrayList<>();
                        for (String[] queued : transaction) replies.add(apply(queued));
                        write(output, "*" + replies.size() + "\r\n");
                        for (String reply : replies) write(output, reply);
                        transaction.clear();
                        multi = false;
                        atomicExecCount++;
                    } else throw new IOException("unexpected non-transaction command " + op);
                    output.flush();
                }
            } catch (Throwable error) { failure = error; }
        }

        private String apply(String[] command) {
            String op = command[0].toUpperCase(Locale.ROOT);
            return switch (op) {
                case "ZADD" -> {
                    sortedSets.computeIfAbsent(command[1], ignored -> new LinkedHashSet<>()).add(command[3]);
                    yield ":1\r\n";
                }
                case "RPUSH" -> {
                    Deque<String> list = lists.computeIfAbsent(command[1], ignored -> new ArrayDeque<>());
                    list.addLast(command[2]);
                    yield ":" + list.size() + "\r\n";
                }
                case "LTRIM" -> {
                    int capacity = -Integer.parseInt(command[2]);
                    Deque<String> list = lists.computeIfAbsent(command[1], ignored -> new ArrayDeque<>());
                    while (list.size() > capacity) list.removeFirst();
                    yield "+OK\r\n";
                }
                default -> throw new IllegalArgumentException("unexpected queued command " + op);
            };
        }

        private static void validateAtomicGroup(List<String[]> commands) throws IOException {
            if (commands.isEmpty() || commands.size() % 3 != 0) throw new IOException("incomplete atomic batch");
            for (int index = 0; index < commands.size(); index += 3) {
                String[] zadd = commands.get(index), rpush = commands.get(index + 1), trim = commands.get(index + 2);
                if (!"ZADD".equalsIgnoreCase(zadd[0]) || !"RPUSH".equalsIgnoreCase(rpush[0])
                    || !"LTRIM".equalsIgnoreCase(trim[0]) || !rpush[1].equals(trim[1])) {
                    throw new IOException("RPUSH/LTRIM is not one atomic complete-Round group");
                }
            }
        }

        private void assertContract() {
            if (atomicExecCount == 0 || lists.isEmpty()) throw new AssertionError("no atomic complete-Round writes");
            MinimalFactCodec codec = new MinimalFactCodec();
            GameRuleCore core = new GameRuleCore();
            IndependentRoundVerifier verifier = new IndependentRoundVerifier(core);
            int members = 0;
            boolean lossSeen = false, positiveSeen = false;
            for (Map.Entry<String,Deque<String>> entry : lists.entrySet()) {
                if (entry.getValue().size() > 10) throw new AssertionError("LTRIM capacity exceeded: " + entry.getKey());
                for (String value : entry.getValue()) {
                    RoundFacts facts = codec.decode(value.getBytes(StandardCharsets.US_ASCII));
                    var result = ResultUtil.analyze(core, facts);
                    verifier.verify(new com.cpgame.luckydragon.core.RoundRequest(facts.betSize(), facts.betLevel()), result);
                    int multiplier = ResultUtil.positiveMultiplier(core, facts);
                    if (multiplier < 0 || !entry.getKey().endsWith(String.format(Locale.ROOT, ":%06d", multiplier))) {
                        throw new AssertionError("member multiplier bucket mismatch");
                    }
                    if (multiplier == 0) lossSeen = true; else positiveSeen = true;
                    if (facts.deliveryIndex() != 0 || !facts.terminal()) throw new AssertionError("incomplete Round member");
                    members++;
                }
            }
            if (members == 0) throw new AssertionError("all Redis members disappeared");
            if (!lossSeen || !positiveSeen) throw new AssertionError("loss and integer multiplier pools must both be populated");
        }

        private static void write(OutputStream output, String text) throws IOException {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }

        private static String[] readCommand(InputStream input) throws IOException {
            String header = readLine(input);
            if (header == null) return null;
            if (!header.startsWith("*")) throw new IOException("RESP array expected");
            int count = Integer.parseInt(header.substring(1));
            String[] result = new String[count];
            for (int index = 0; index < count; index++) {
                String length = readLine(input);
                if (length == null || !length.startsWith("$")) throw new IOException("RESP bulk expected");
                int size = Integer.parseInt(length.substring(1));
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size || input.read() != '\r' || input.read() != '\n') throw new IOException("truncated RESP bulk");
                result[index] = new String(bytes, StandardCharsets.UTF_8);
            }
            return result;
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int previous = -1;
            for (;;) {
                int value = input.read();
                if (value < 0) return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
                if (previous == '\r' && value == '\n') return buffer.toString(StandardCharsets.UTF_8);
                if (previous >= 0) buffer.write(previous);
                previous = value;
            }
        }
    }
}
