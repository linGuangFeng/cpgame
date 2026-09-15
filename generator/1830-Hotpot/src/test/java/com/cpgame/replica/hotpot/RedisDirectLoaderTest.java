package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoardGenerator;
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
            Files.writeString(config, minimalConfig(redis.port(), 2, 1, 3), StandardCharsets.UTF_8);
            RedisDirectLoader.LoaderConfig loaded = RedisDirectLoader.LoaderConfig.load(config);
            assertEquals(1, loaded.normalMinWinMultiplier());
            assertEquals(1, loaded.maryMinWinMultiplier());
            assertEquals(20_000, loaded.normalMaxWinMultiplier());
            assertEquals(20_000, loaded.maryMaxWinMultiplier());
            assertArrayEquals(HotpotBoardGenerator.DEFAULT_PAID_START_WEIGHTS, loaded.normalWeights());
            assertArrayEquals(HotpotBoardGenerator.DEFAULT_CASCADE_WEIGHTS, loaded.cascadeWeights());
            assertArrayEquals(HotpotBoardGenerator.DEFAULT_FREE_START_WEIGHTS, loaded.maryWeights());
            RedisDirectLoader.LoadSummary summary = RedisDirectLoader.run(config);
            redis.assertHealthy();
            assertEquals(1830, summary.redisGameId());
            assertEquals(2, summary.normalMembers());
            assertEquals(1, summary.specialMembers());
            assertEquals(0, summary.lossMembers());
            assertTrue(summary.batches() >= 1);
            assertEquals(3, redis.zadds);
            assertEquals(3, redis.rpushes);
            assertTrue(redis.keys.stream().anyMatch(v -> v.equals("PerKeyList_000001830")));
            assertTrue(redis.keys.stream().anyMatch(v -> v.equals("MaryKeyList_000001830")));
            assertFalse(redis.keys.stream().anyMatch(v -> v.equals("BetLog:000001830:000000")));
        }
    }

    @Test void zeroOnlyRangeGeneratesFullTargetBeyondRetentionCap() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            Path config = temp.resolve("zero-only.properties");
            Files.writeString(config, minimalConfig(redis.port(), 5, 0, 3)
                    .replace("generation.normal-min-win-multiplier=1", "generation.normal-min-win-multiplier=0")
                    + "generation.normal-max-win-multiplier=0\n");
            var summary = RedisDirectLoader.run(config);
            redis.assertHealthy();
            assertEquals(5, summary.normalMembers());
            assertEquals(5, summary.lossMembers());
            assertEquals(5, redis.rpushes);
            assertEquals(3, summary.batches());
        }
    }

    @Test void formalPropertiesUseCapturedCellCounts() throws Exception {
        RedisDirectLoader.LoaderConfig loaded = RedisDirectLoader.LoaderConfig.load(Path.of("generator.properties"));
        assertArrayEquals(HotpotBoardGenerator.DEFAULT_PAID_START_WEIGHTS, loaded.normalWeights());
        assertArrayEquals(HotpotBoardGenerator.DEFAULT_CASCADE_WEIGHTS, loaded.cascadeWeights());
        assertArrayEquals(HotpotBoardGenerator.DEFAULT_FREE_START_WEIGHTS, loaded.maryWeights());
        assertEquals(50_076, java.util.Arrays.stream(loaded.normalWeights()).sum());
        assertEquals(56_988, java.util.Arrays.stream(loaded.cascadeWeights()).sum());
        assertEquals(38_016, java.util.Arrays.stream(loaded.maryWeights()).sum());
        assertEquals("192.168.10.3", loaded.host());
        assertEquals(15, loaded.database());
        assertEquals(1830, loaded.redisGameId());
    }

    @Test void refusedRedisConnectionExplainsHostAndPort() throws Exception {
        Path config = temp.resolve("down.properties");
        Files.writeString(config, """
                redis.host=127.0.0.1
                redis.port=1
                redis.connect-timeout-ms=500
                generation.normal-count=1
                generation.special-count=0
                """, StandardCharsets.UTF_8);
        IOException ex = assertThrows(IOException.class, () -> RedisDirectLoader.run(config));
        assertTrue(ex.getMessage().contains("127.0.0.1:1"));
        assertTrue(ex.getMessage().contains("无法连接 Redis"));
    }

    @Test void seedKeyIsRejected() throws Exception {
        Path config = temp.resolve("seeded.properties");
        Files.writeString(config, "redis.host=127.0.0.1\nredis.port=6379\ngeneration.seed=1\n", StandardCharsets.UTF_8);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RedisDirectLoader.LoaderConfig.load(config));
        assertTrue(ex.getMessage().contains("seed"));
    }

    @Test void specialEntryMultipliesScatterWeightButCapsStayInGenerator() {
        int[] ordinary = HotpotBoardGenerator.defaultPaidStartWeights();
        int[] special = RedisDirectLoader.specialEntryOpeningWeights(ordinary);
        assertEquals(ordinary[10] * 10, special[10]);
        for (int i = 0; i < ordinary.length; i++) {
            if (i != 10) assertEquals(ordinary[i], special[i]);
        }
        assertEquals(1000, RedisDirectLoader.ENTRY_SWITCH_EVERY);
        assertEquals(10, RedisDirectLoader.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        assertEquals(1, com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil.MAX_SCATTER_PER_COLUMN);
        assertEquals(4, com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil.MAX_SCATTER_PAID_PAGE);
    }

    @Test void capsEachMultiplierUsingOnlyCurrentRunMemory() {
        Map<Integer, Integer> currentRun = new HashMap<>();
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertFalse(RedisDirectLoader.tryReserveMultiplier(currentRun, 25, 2));
        assertTrue(RedisDirectLoader.tryReserveMultiplier(currentRun, 50, 2));
        assertEquals(Map.of(25, 2, 50, 1), currentRun);
    }

    private static String minimalConfig(int port, int normal, int special, int cap) {
        return """
                redis.host=127.0.0.1
                redis.port=%d
                redis.game-id=1830
                generation.normal-count=%d
                generation.special-count=%d
                generation.batch-size=2
                generation.max-consecutive-wins=10
                generation.max-members-per-multiplier=%d
                generation.normal-min-win-multiplier=1
                generation.mary-min-win-multiplier=1
                """.formatted(port, normal, special, cap);
    }

    private static final class FakeRedis implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        final List<String> keys = new ArrayList<>();
        volatile int zadds, rpushes, ltrims, transactions;

        FakeRedis() throws IOException {
            thread = new Thread(this::serve, "fake-redis");
            thread.setDaemon(true);
            thread.start();
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
                        case "SELECT" -> write(out, "+OK\r\n");
                        case "AUTH" -> write(out, "+OK\r\n");
                        case "MULTI" -> { queued = 0; write(out, "+OK\r\n"); }
                        case "ZADD" -> { zadds++; queued++; keys.add(command.get(1)); write(out, "+QUEUED\r\n"); }
                        case "RPUSH" -> {
                            rpushes++; queued++; keys.add(command.get(1));
                            String member = command.get(2);
                            if (member.startsWith("{") || member.startsWith("[")) {
                                throw new IllegalStateException("JSON member written to Redis");
                            }
                            write(out, "+QUEUED\r\n");
                        }
                        case "LTRIM" -> {
                            ltrims++; queued++; keys.add(command.get(1));
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
            int star = in.read();
            if (star < 0) throw new EOFException();
            if (star != '*') throw new IOException("expected array");
            int count = Integer.parseInt(line(in));
            List<String> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (in.read() != '$') throw new IOException("expected bulk");
                int length = Integer.parseInt(line(in));
                byte[] data = in.readNBytes(length);
                if (data.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException();
                result.add(new String(data, StandardCharsets.UTF_8));
            }
            return result;
        }

        static String line(BufferedInputStream in) throws Exception {
            var result = new java.io.ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = in.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') break;
                if (previous >= 0) result.write(previous);
                previous = current;
            }
            return result.toString(StandardCharsets.UTF_8);
        }

        static void write(BufferedOutputStream out, String value) throws Exception {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(2000);
            assertHealthy();
        }
    }
}
