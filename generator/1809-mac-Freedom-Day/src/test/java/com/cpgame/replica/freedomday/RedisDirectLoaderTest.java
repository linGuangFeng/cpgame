package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RedisDirectLoaderTest {
    @TempDir Path temp;

    @Test void generatesAndCommitsAtomicBatchesDirectlyToRedis() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            Path config = temp.resolve("generator.properties");
            Files.writeString(config, """
                    redis.host=127.0.0.1
                    redis.port=%d
                    redis.game-id=8101809
                    generation.normal-count=2
                    generation.special-count=1
                    generation.batch-size=2
                    generation.max-consecutive-wins=10
                    generation.max-members-per-multiplier=3
                    generation.normal-min-win-multiplier=1
                    generation.mary-min-win-multiplier=1
                    """.formatted(redis.port()), StandardCharsets.UTF_8);
            RedisDirectLoader.LoaderConfig loaded = RedisDirectLoader.LoaderConfig.load(config);
            assertEquals(1, loaded.normalMinWinMultiplier());
            assertEquals(1, loaded.maryMinWinMultiplier());
            assertEquals(1500, loaded.normalMaxWinMultiplier());
            assertEquals(2000, loaded.maryMaxWinMultiplier());
            assertArrayEquals(FreedomDayBoardGenerator.DEFAULT_NORMAL_WEIGHTS, loaded.normalWeights());
            assertArrayEquals(FreedomDayBoardGenerator.DEFAULT_FREE_WEIGHTS, loaded.maryWeights());
            RedisDirectLoader.LoadSummary summary = RedisDirectLoader.run(config);
            redis.assertHealthy();
            assertEquals(8101809, summary.redisGameId());
            assertEquals(2, summary.normalMembers());
            assertEquals(1, summary.specialMembers());
            assertEquals(2, summary.batches());
            assertEquals(3, redis.zadds);
            assertEquals(3, redis.rpushes);
            assertEquals(3, redis.ltrims);
            assertEquals(2, redis.transactions);
            assertTrue(redis.keys.stream().anyMatch(v -> v.equals("PerKeyList_008101809")));
            assertTrue(redis.keys.stream().anyMatch(v -> v.equals("MaryKeyList_008101809")));
        }
    }

    @Test void formalPropertiesUseCapturedOpeningCellCounts() throws Exception {
        RedisDirectLoader.LoaderConfig loaded = RedisDirectLoader.LoaderConfig.load(Path.of("generator.properties"));
        assertArrayEquals(FreedomDayBoardGenerator.DEFAULT_NORMAL_WEIGHTS, loaded.normalWeights());
        assertArrayEquals(FreedomDayBoardGenerator.DEFAULT_FREE_WEIGHTS, loaded.maryWeights());
        assertEquals(43_384, java.util.Arrays.stream(loaded.normalWeights()).sum());
        assertEquals(79_220, java.util.Arrays.stream(loaded.maryWeights()).sum());
    }

    @Test void writesIndependentZeroAsHashWithZeroIndexWhenRangeIncludesZero() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            Path config = temp.resolve("zero.properties");
            Files.writeString(config, """
                    redis.host=127.0.0.1
                    redis.port=%d
                    redis.game-id=8101809
                    generation.normal-count=1
                    generation.special-count=0
                    generation.batch-size=1
                    generation.max-members-per-multiplier=3
                    generation.normal-min-win-multiplier=0
                    generation.normal-max-win-multiplier=0
                    """.formatted(redis.port()), StandardCharsets.UTF_8);
            RedisDirectLoader.LoadSummary summary = RedisDirectLoader.run(config);
            redis.assertHealthy();
            assertEquals(1, summary.normalMembers());
            assertEquals(List.of("#"), redis.payloads);
            assertTrue(redis.keys.contains("BetLog:008101809:000000"));
            assertEquals(List.of("0"), redis.ratios);
            assertEquals(1, redis.zadds);
            assertEquals(1, redis.rpushes);
            assertEquals(1, redis.ltrims);
            assertEquals(1, redis.transactions);
        }
    }

    @Test void refusedRedisConnectionExplainsHostAndPort() throws Exception {
        Path config = temp.resolve("down.properties");
        Files.writeString(config, """
                redis.host=127.0.0.1
                redis.port=1
                redis.connect-timeout-ms=500
                """, StandardCharsets.UTF_8);
        IOException ex = assertThrows(IOException.class, () -> RedisDirectLoader.run(config));
        assertTrue(ex.getMessage().contains("127.0.0.1:1"));
        assertTrue(ex.getMessage().contains("无法连接 Redis"));
    }

    @Test void omittedMinMultiplierKeysDefaultToOneAndOneHundred() throws Exception {
        Path config = temp.resolve("defaults.properties");
        Files.writeString(config, """
                redis.host=127.0.0.1
                redis.port=6379
                """, StandardCharsets.UTF_8);
        RedisDirectLoader.LoaderConfig loaded = RedisDirectLoader.LoaderConfig.load(config);
        assertEquals(1, loaded.normalMinWinMultiplier());
        assertEquals(100, loaded.maryMinWinMultiplier());
        assertEquals(1500, loaded.normalMaxWinMultiplier());
        assertEquals(2000, loaded.maryMaxWinMultiplier());
    }

    @Test void specialEntryOnlyMultipliesScatterOpeningWeightByTen() {
        int[] ordinary = {8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 1, 2};
        int[] special = RedisDirectLoader.specialEntryOpeningWeights(ordinary);
        assertEquals(10, special[11]);
        for (int i = 0; i < ordinary.length; i++) {
            if (i != 11) assertEquals(ordinary[i], special[i]);
        }
        assertEquals(1000, RedisDirectLoader.ENTRY_SWITCH_EVERY);
        assertEquals(10, RedisDirectLoader.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
    }

    @Test void capsEachMultiplierUsingOnlyCurrentRunMemory() {
        Map<Integer, Integer> currentRun = new HashMap<>();
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertFalse(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 50, 2));
        assertEquals(Map.of(25, 2, 50, 1), currentRun);
    }

    private static final class FakeRedis implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        final List<String> keys = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final List<String> ratios = new ArrayList<>();
        volatile int zadds, rpushes, ltrims, transactions;

        FakeRedis() throws IOException {
            thread = new Thread(this::serve, "fake-redis"); thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        void serve() {
            try (var socket = server.accept();
                 var in = new BufferedInputStream(socket.getInputStream());
                 var out = new BufferedOutputStream(socket.getOutputStream())) {
                int queued = 0;
                while (true) {
                    List<String> command;
                    try { command = readCommand(in); } catch (EOFException done) { break; }
                    String name = command.get(0).toUpperCase();
                    switch (name) {
                        case "PING" -> write(out, "+PONG\r\n");
                        case "MULTI" -> { queued = 0; write(out, "+OK\r\n"); }
                        case "ZADD" -> {
                            zadds++; queued++; keys.add(command.get(1)); ratios.add(command.get(2));
                            assertEquals(command.get(2), command.get(3));
                            write(out, "+QUEUED\r\n");
                        }
                        case "RPUSH" -> {
                            rpushes++; queued++; keys.add(command.get(1)); payloads.add(command.get(2));
                            int ratio = Integer.parseInt(command.get(1).substring(command.get(1).lastIndexOf(':') + 1));
                            assertEquals(ratio, new CompleteRoundCodec().verify(command.get(2), 10).multiplier().intValueExact());
                            write(out, "+QUEUED\r\n");
                        }
                        case "LTRIM" -> {
                            ltrims++; queued++; keys.add(command.get(1));
                            assertEquals(command.get(1).startsWith("MaryLog:") ? "-100" : "-3", command.get(2));
                            assertEquals("-1", command.get(3));
                            write(out, "+QUEUED\r\n");
                        }
                        case "EXEC" -> {
                            transactions++; write(out, "*" + queued + "\r\n");
                            for (int i = 0; i < queued; i++) write(out, ":1\r\n");
                        }
                        default -> throw new IllegalStateException("unexpected command " + name);
                    }
                    out.flush();
                }
            } catch (Throwable ex) { if (!server.isClosed()) failure.set(ex); }
        }
        void assertHealthy() { if (failure.get() != null) fail(failure.get()); }
        static List<String> readCommand(BufferedInputStream in) throws Exception {
            int star = in.read(); if (star < 0) throw new EOFException(); if (star != '*') throw new IOException("expected array");
            int count = Integer.parseInt(line(in)); List<String> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (in.read() != '$') throw new IOException("expected bulk"); int length = Integer.parseInt(line(in));
                byte[] data = in.readNBytes(length); if (data.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException();
                result.add(new String(data, StandardCharsets.UTF_8));
            }
            return result;
        }
        static String line(BufferedInputStream in) throws Exception {
            var result = new java.io.ByteArrayOutputStream(); int previous = -1;
            while (true) { int current = in.read(); if (current < 0) throw new EOFException(); if (previous == '\r' && current == '\n') break; if (previous >= 0) result.write(previous); previous = current; }
            return result.toString(StandardCharsets.UTF_8);
        }
        static void write(BufferedOutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
        @Override public void close() throws Exception { server.close(); thread.join(2000); assertHealthy(); }
    }
}
