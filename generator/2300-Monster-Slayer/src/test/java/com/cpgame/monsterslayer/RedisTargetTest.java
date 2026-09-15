package com.cpgame.monsterslayer;

import com.cpgame.monsterslayer.generator.GeneratorConfig;
import com.cpgame.monsterslayer.loader.RedisLoader;
import com.cpgame.monsterslayer.redis.RedisKeyContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class RedisTargetTest {
    @TempDir Path temp;

    private Path config(String settings) throws IOException {
        Path path = temp.resolve("generator.properties");
        Files.writeString(path, "generation.loss-count=2\ngeneration.win-count=2\ngeneration.buy-count-per-mode=2\n" + settings + "\n");
        return path;
    }

    @Test void configuredEndpointAndNamespaceAreAccepted() throws Exception {
        GeneratorConfig c = GeneratorConfig.load(config("redis.host=example.test\nredis.port=8021\nredis.database=0\nredis.game-id=8002300"));
        assertEquals("example.test", c.redisHost);
        assertEquals(8021, c.redisPort);
        assertEquals(0, c.redisDatabase);
        assertEquals(8002300, c.redisGameId);
        assertEquals("PerKeyList_008002300", RedisKeyContract.normalIndex(c.redisGameId));
        assertEquals("BetLog:008002300:000350", RedisKeyContract.normalList(c.redisGameId, 350));
        assertEquals("MaryLog:008002300:000350", RedisKeyContract.buyList(c.redisGameId, 3, 350));
        assertEquals("MaryKeyList_108002300", RedisKeyContract.buyIndex(c.redisGameId, 4));
        assertEquals("MaryLog:208002300:000350", RedisKeyContract.buyList(c.redisGameId, 5, 350));
        assertFalse(RedisKeyContract.buyIndexesToDelete(c.redisGameId, 3).contains("PerKeyListt_0"));
    }

    @Test void completionLogMatchesAdminContract() throws Exception {
        var method = RedisLoader.class.getDeclaredMethod("completionLine", RedisLoader.Summary.class);
        method.setAccessible(true);
        assertEquals("LOAD_COMPLETE loaded=840 normal=600 special=240",
                method.invoke(null, new RedisLoader.Summary(300, 300, 80, 80, 80)));
    }

    @Test void invalidConnectionSettingsFailBeforeConnecting() throws Exception {
        for (String setting : List.of("redis.host=", "redis.port=0", "redis.port=65536", "redis.database=-1",
                "redis.game-id=0", "redis.game-id=100000000", "redis.connect-timeout-ms=0", "redis.socket-timeout-ms=-1")) {
            Path path = config(setting);
            assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(path), setting);
        }
    }

    @Test void loaderUsesConfiguredDatabaseAndNamespaceForEveryMode() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<List<String>>> received = executor.submit(() -> recordRedis(server, 0));
            try {
                RedisLoader.Summary summary = RedisLoader.run(config("redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                        + "\nredis.database=4\nredis.game-id=8002300\nredis.username=test-user\nredis.password=test-password\ngeneration.batch-size=3\ngeneration.loss-count=7\ngeneration.win-count=5\ngeneration.buy-count-per-mode=4\ngeneration.special-max-members-per-multiplier=2"));
                assertEquals(new RedisLoader.Summary(7, 5, 4, 4, 4), summary);
                List<List<String>> commands = received.get(15, TimeUnit.SECONDS);
                assertTrue(commands.contains(List.of("SELECT", "4")));
                assertTrue(commands.contains(List.of("AUTH", "test-user", "test-password")));
                Set<String> writtenIndexes = new HashSet<>();
                int pushes = 0;
                for (List<String> command : commands) {
                    if (List.of("ZRANGE", "DEL", "ZADD", "RPUSH", "LTRIM").contains(command.get(0))) {
                        assertTrue(command.get(1).matches("(?:PerKeyList_|MaryKeyList_|BetLog:|MaryLog:)[012]08002300(?::[0-9]+)?"), command.toString());
                    }
                    if (command.get(0).equals("ZADD")) writtenIndexes.add(command.get(1));
                    if (command.get(0).equals("RPUSH")) pushes++;
                }
                assertEquals(24, pushes);
                List<Integer> batchSizes = new ArrayList<>();
                int current = 0;
                Map<String, Integer> deletions = new HashMap<>();
                for (List<String> command : commands) {
                    if (command.get(0).equals("MULTI")) current = 0;
                    if (command.get(0).equals("RPUSH")) current++;
                    if (command.get(0).equals("EXEC")) batchSizes.add(current);
                    if (command.get(0).equals("DEL")) deletions.merge(command.get(1), 1, Integer::sum);
                    if (command.get(0).equals("LTRIM") && command.get(1).startsWith("MaryLog:"))
                        assertEquals("-2", command.get(2));
                }
                assertEquals(List.of(3, 3, 3, 3, 3, 3, 2, 1, 1, 1, 1), batchSizes);
                List<String> phases = new ArrayList<>();
                String batchPhase = "";
                for (List<String> command : commands) {
                    if (command.get(0).equals("RPUSH")) {
                        String key = command.get(1);
                        batchPhase = key.startsWith("BetLog:") ? (key.endsWith(":000000") ? "loss" : "win")
                                : "buy" + (3 + (key.charAt("MaryLog:".length()) - '0'));
                    }
                    if (command.get(0).equals("EXEC")) phases.add(batchPhase);
                }
                assertEquals(List.of("loss", "win", "buy3", "buy4", "buy5", "loss", "win", "buy3", "buy4", "buy5", "loss"), phases);
                assertEquals(6, deletions.size());
                assertTrue(deletions.values().stream().allMatch(count -> count == 1), "must not clear earlier batches");
                assertEquals(Set.of("PerKeyList_008002300", "MaryKeyList_008002300",
                        "MaryKeyList_108002300", "MaryKeyList_208002300"), writtenIndexes);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void hundredsOfMillionsStartInSmallHeapAndStopOnFailedBatch() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<List<String>>> received = executor.submit(() -> recordRedis(server, 3));
            Process process = null;
            try {
                Path path = config("redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                        + "\nredis.game-id=8002300\ngeneration.batch-size=100\ngeneration.loss-count=30000000"
                        + "\ngeneration.win-count=300000000\ngeneration.buy-count-per-mode=5000000");
                Path log = temp.resolve("large-run.log");
                String classes = Path.of(RedisLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx32m", "-cp", classes, RedisLoader.class.getName(), path.toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "must start writing without generating entire target");
                assertEquals(1, process.exitValue());
                String output = Files.readString(log);
                assertTrue(output.contains("LOAD_START target=345000000 batchSize=100"), output);
                assertTrue(output.contains("BATCH_COMMITTED batch=1 members=100 loaded=100"), output);
                assertTrue(output.contains("BATCH_COMMITTED batch=2 members=100 loaded=200"), output);
                assertTrue(output.contains("injected batch failure"), output);
                assertFalse(output.contains("LOAD_COMPLETE"), output);
                assertFalse(output.contains("BATCH_COMMITTED batch=3"), output);
                List<List<String>> commands = received.get(15, TimeUnit.SECONDS);
                assertEquals(300, commands.stream().filter(c -> c.get(0).equals("RPUSH")).count());
                assertEquals(3, commands.stream().filter(c -> c.get(0).equals("EXEC")).count());
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
                executor.shutdownNow();
            }
        }
    }

    @Test void largeBatchDrainsRepliesAndZeroCountPoolsAreUntouched() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<List<String>>> received = executor.submit(() -> recordRedis(server, 0));
            try {
                RedisLoader.Summary summary = RedisLoader.run(config("redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                        + "\nredis.game-id=8002300\ngeneration.batch-size=513\ngeneration.loss-count=515"
                        + "\ngeneration.win-count=0\ngeneration.buy-count-per-mode=0"));
                assertEquals(new RedisLoader.Summary(515, 0, 0, 0, 0), summary);
                List<List<String>> commands = received.get(15, TimeUnit.SECONDS);
                assertEquals(515, commands.stream().filter(c -> c.get(0).equals("RPUSH")).count());
                assertEquals(2, commands.stream().filter(c -> c.get(0).equals("EXEC")).count());
                assertEquals(List.of(List.of("DEL", "PerKeyList_008002300")),
                        commands.stream().filter(c -> c.get(0).equals("DEL")).toList());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void rejectedFirstBatchDoesNotClearOrWritePool() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<List<String>>> received = executor.submit(() -> recordRedis(server, 0));
            try {
                Path path = config("redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                        + "\ngeneration.loss-count=0\ngeneration.win-count=1\ngeneration.buy-count-per-mode=0"
                        + "\ngeneration.max-centi-multiplier=0");
                assertThrows(IllegalStateException.class, () -> RedisLoader.run(path));
                List<List<String>> commands = received.get(15, TimeUnit.SECONDS);
                assertEquals(List.of("SELECT", "PING"), commands.stream().map(c -> c.get(0)).toList());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void zeroLossStartsWithNonzeroWinningBatch() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<List<List<String>>> received = executor.submit(() -> recordRedis(server, 0));
            try {
                RedisLoader.run(config("redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                        + "\ngeneration.loss-count=0\ngeneration.win-count=5\ngeneration.buy-count-per-mode=0"
                        + "\ngeneration.batch-size=3\ngeneration.normal-min-win-multiplier=1"
                        + "\ngeneration.normal-max-win-multiplier=200\ngeneration.max-centi-multiplier=200"));
                var writes = received.get(15, TimeUnit.SECONDS).stream().filter(c -> c.get(0).equals("RPUSH")).toList();
                assertEquals(5, writes.size());
                for (var command : writes) {
                    assertTrue(command.get(1).startsWith("BetLog:"));
                    int mult = Integer.parseInt(command.get(1).substring(command.get(1).lastIndexOf(':') + 1));
                    assertTrue(mult >= 1 && mult <= 200, command.get(1));
                }
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void purchaseIndexesOnlyExposeRealMaryLogResultsAndRetireOldAliases() {
        for (long gameId : new long[]{2300, 8002300}) {
            for (int type = 3; type <= 5; type++) {
                assertEquals(List.of(RedisKeyContract.buyIndex(gameId, type)),
                        RedisKeyContract.buyIndexesToWrite(gameId, type));
                if (type != 3) assertTrue(RedisKeyContract.buyIndexesToDelete(gameId, type)
                        .contains(RedisKeyContract.buyPerKeyIndex(gameId, type)));
            }
        }
        assertEquals(Set.of("PerKeyList_000002300", "MaryKeyList_000002300", "MaryKeyList_100002300", "MaryKeyList_200002300"),
                RedisKeyContract.requiredIndexNames());
    }

    @Test void zeroOrOmittedNaturalSpecialCountIsDisabledAndAccepted() throws Exception {
        assertEquals(0, GeneratorConfig.load(config("generation.special-count=0")).specialCount);
        assertEquals(0, GeneratorConfig.load(config("")).specialCount);
    }

    @Test void exampleConfigurationDoesNotAdvertiseUnsupportedNaturalGeneration() throws Exception {
        Path path = Path.of("generator.properties");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        assertFalse(properties.containsKey("generation.special-count"));
        assertFalse(properties.containsKey("generation.special-trigger-weight-multiplier"));
        assertTrue(properties.stringPropertyNames().stream().noneMatch(k -> k.endsWith(".mary-weight")));
        assertEquals(0, GeneratorConfig.load(path).specialCount);
    }

    @Test void unsupportedNaturalSpecialCountFailsBeforeAnyRedisConnection() throws Exception {
        Path path = config("redis.host=invalid.example\ngeneration.special-count=30000000");
        var error = assertThrows(IllegalArgumentException.class, () -> RedisLoader.run(path));
        assertTrue(error.getMessage().contains("generation.special-count=30000000"));
        assertTrue(error.getMessage().contains("not implemented"));
    }

    private static List<List<String>> recordRedis(ServerSocket server, int failAtBatch) throws IOException {
        List<List<String>> commands = new ArrayList<>();
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            int queued = -1;
            int commits = 0;
            String line;
            while ((line = in.readLine()) != null) {
                int size = Integer.parseInt(line.substring(1));
                List<String> command = new ArrayList<>();
                for (int i = 0; i < size; i++) { in.readLine(); command.add(in.readLine()); }
                commands.add(command);
                String op = command.get(0);
                String reply;
                if (op.equals("MULTI")) { queued = 0; reply = "+OK\r\n"; }
                else if (op.equals("EXEC")) {
                    commits++;
                    reply = commits == failAtBatch ? "-ERR injected batch failure\r\n"
                            : "*" + queued + "\r\n" + ":1\r\n".repeat(queued);
                    queued = -1;
                }
                else if (queued >= 0) { queued++; reply = "+QUEUED\r\n"; }
                else reply = switch (op) { case "PING" -> "+PONG\r\n"; case "ZRANGE" -> "*0\r\n"; default -> "+OK\r\n"; };
                out.write(reply); out.flush();
            }
        }
        return commands;
    }
}
