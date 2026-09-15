package com.cpgame.batchc.cybergo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RedisLoaderTest {
    @Test
    void embeddedEmpiricalModelProducesNonUniformRuleValidDistribution() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        RandomCandidateGenerator generator = new RandomCandidateGenerator(new java.util.Random(520052L),
                config.symbolWeights);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String symbol : CyberGoRules.PAYING_SYMBOLS) counts.put(symbol, 0);
        counts.put("WILD", 0); counts.put("SC", 0);
        for (int index = 0; index < 20_000; index++) {
            List<String> paid = generator.paidBoardCandidate();
            for (String symbol : paid) counts.merge(symbol, 1, Integer::sum);
            assertFalse(paid.subList(0, 3).contains("WILD"));
            assertFalse(paid.subList(12, 15).contains("WILD"));
            assertTrue(java.util.Collections.frequency(paid, "SC") <= 4);
            assertFalse(generator.freeBoardCandidate().contains("SC"));
        }
        counts.forEach((symbol, count) -> assertTrue(count > 0, symbol));
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int minimum = counts.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        assertTrue(maximum > minimum * 3, counts.toString());
        assertTrue(counts.get("A") > counts.get("WILD") * 25 / 10, counts.toString());
    }

    @Test
    void productionConfigurationUsesBoundedPoolsAndRejectsUnknownOrSeedKeys() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        assertEquals(52, config.redisGameId);
        assertEquals(5_000, config.normalCount);
        assertEquals(5_000, config.lossCount);
        assertEquals(1_000, config.specialCount);
        assertEquals(300, config.maxMembersPerMultiplier);
        assertFalse(config.symbolWeights.free().containsKey("SC"));

        Properties seeded = productionProperties();
        seeded.setProperty("generation.seed", "52");
        Path seededFile = write(seeded);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(seededFile));
        Properties unknownKey = productionProperties();
        unknownKey.setProperty("output.file", "forbidden");
        Path unknownKeyFile = write(unknownKey);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(unknownKeyFile));
    }

    @Test void olderConfigsCanOmitLimitsThatAlreadyHaveDefaults() throws Exception {
        Properties properties = productionProperties();
        properties.remove("generation.normal-min-win-multiplier");
        properties.remove("generation.special-min-win-multiplier");
        properties.remove("generation.special-max-members-per-multiplier");
        GeneratorConfig config = GeneratorConfig.load(write(properties));
        assertTrue(config.outputLimits.accepts(false, 0));
        assertTrue(config.outputLimits.accepts(true, 0));
        assertEquals(100, config.outputLimits.specialCap);
    }

    @Test
    void directRedisLoaderCommitsVerifiedRoundsWithAtomicTrim() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            Properties properties = productionProperties();
            properties.setProperty("redis.host", "127.0.0.1");
            properties.setProperty("redis.port", Integer.toString(redis.port()));
            properties.setProperty("redis.database", "0");
            properties.setProperty("generation.normal-count", "3");
            properties.setProperty("generation.special-count", "1");
            properties.setProperty("generation.loss-count", "2");
            properties.setProperty("generation.batch-size", "2");
            properties.setProperty("generation.max-members-per-multiplier", "2");
            properties.setProperty("generation.special-max-members-per-multiplier", "2");
            GeneratorConfig config = GeneratorConfig.load(write(properties));
            RedisLoader.LoadSummary summary = new RedisLoader().load(config);
            assertEquals(3, summary.normalMembers());
            assertEquals(1, summary.specialMembers());
            assertEquals(3, summary.batches());

            redis.awaitHealthy();
            assertEquals(3, redis.transactions.size());
            MinimalFactCodec codec = new MinimalFactCodec();
            GameRuleCore core = new GameRuleCore();
            int members = 0;
            for (List<List<String>> transaction : redis.transactions) {
                for (int index = 0; index < transaction.size();) {
                    List<String> zadd = null;
                    if ("ZADD".equals(transaction.get(index).getFirst())) zadd = transaction.get(index++);
                    List<String> rpush = transaction.get(index++);
                    List<String> ltrim = transaction.get(index++);
                    assertEquals("RPUSH", rpush.getFirst());
                    assertEquals("LTRIM", ltrim.getFirst());
                    assertEquals(rpush.get(1), ltrim.get(1));
                    assertEquals("-2", ltrim.get(2));
                    boolean special = rpush.get(1).startsWith("MaryLog:000000052:");
                    assertTrue(special || rpush.get(1).startsWith("BetLog:000000052:"));
                    String multiplier = rpush.get(1).substring(rpush.get(1).lastIndexOf(':') + 1);
                    assertNotNull(zadd);
                    assertEquals(special ? RedisRoundPool.SPECIAL_INDEX : RedisRoundPool.INDEX, zadd.get(1));
                    assertEquals(Integer.parseInt(multiplier), Integer.parseInt(zadd.get(2)));
                    assertEquals(Integer.parseInt(multiplier), Integer.parseInt(zadd.get(3)));
                    CyberGoModels.CompleteRound rebuilt = core.rebuild(
                            codec.decodeFromRedis(rpush.get(2).getBytes(StandardCharsets.US_ASCII)));
                    ResultUtil.RoundResult result = ResultUtil.reverse(rebuilt);
                    assertEquals(new java.math.BigDecimal(multiplier).stripTrailingZeros(),
                            result.totalWin().divide(CyberGoRules.MINIMUM_BET).stripTrailingZeros());
                    members++;
                }
            }
            assertEquals(6, members);
            assertEquals(java.util.Set.of(RedisRoundPool.INDEX, RedisRoundPool.SPECIAL_INDEX), java.util.Set.copyOf(redis.indexKeys));
        }
    }

    private static Properties productionProperties() throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Path write(Properties properties) throws Exception {
        Path file = Files.createTempFile("cyber-go-generator-", ".properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(writer, "test"); }
        file.toFile().deleteOnExit();
        return file;
    }

    private static final class FakeRedis implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        final List<List<List<String>>> transactions = new ArrayList<>();
        final List<String> indexKeys = new ArrayList<>();

        FakeRedis() throws Exception {
            thread = Thread.ofPlatform().name("fake-redis-52").start(this::serve);
        }

        int port() { return server.getLocalPort(); }

        void awaitHealthy() throws Exception {
            thread.join(5_000);
            if (thread.isAlive()) fail("Fake Redis线程未结束");
            if (failure.get() != null) throw new AssertionError("Fake Redis失败", failure.get());
        }

        private void serve() {
            try (Socket socket = server.accept();
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                List<List<String>> queued = null;
                while (true) {
                    List<String> command;
                    try { command = readCommand(input); }
                    catch (EOFException finished) { break; }
                    String name = command.getFirst();
                    if ("PING".equals(name)) writeSimple(output, "PONG");
                    else if ("MULTI".equals(name)) { queued = new ArrayList<>(); writeSimple(output, "OK"); }
                    else if ("EXEC".equals(name)) {
                        if (queued == null) throw new IllegalStateException("EXEC没有MULTI");
                        transactions.add(List.copyOf(queued));
                        writeIntegerArray(output, queued.size());
                        queued = null;
                    } else {
                        if (queued == null) throw new IllegalStateException("事务命令不在MULTI中: " + name);
                        queued.add(List.copyOf(command));
                        if ("ZADD".equals(name)) indexKeys.add(command.get(1));
                        writeSimple(output, "QUEUED");
                    }
                    output.flush();
                }
            } catch (Throwable error) { failure.set(error); }
        }

        private static List<String> readCommand(BufferedInputStream input) throws Exception {
            int prefix = input.read();
            if (prefix < 0) throw new EOFException();
            if (prefix != '*') throw new IllegalStateException("不是RESP数组");
            int count = Integer.parseInt(readLine(input));
            List<String> command = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                if (input.read() != '$') throw new IllegalStateException("不是RESP bulk");
                int length = Integer.parseInt(readLine(input));
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
                command.add(new String(bytes, StandardCharsets.UTF_8));
            }
            return command;
        }

        private static String readLine(BufferedInputStream input) throws Exception {
            StringBuilder value = new StringBuilder();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') break;
                if (previous >= 0) value.append((char) previous);
                previous = current;
            }
            return value.toString();
        }

        private static void writeSimple(BufferedOutputStream output, String value) throws Exception {
            output.write(("+" + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        private static void writeIntegerArray(BufferedOutputStream output, int size) throws Exception {
            output.write(("*" + size + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (int index = 0; index < size; index++) output.write(":1\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(1_000);
        }
    }
}
