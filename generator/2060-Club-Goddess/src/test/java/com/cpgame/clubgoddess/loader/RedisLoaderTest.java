package com.cpgame.clubgoddess.loader;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisLoaderTest {
    @TempDir Path temp;

    @Test void formalConfigurationHasPositiveReadWeightsAndRejectsForbiddenOrExcessiveValues() throws Exception {
        Path formal = Path.of("dist", "generator.properties").toAbsolutePath().normalize();
        RedisLoader.LoaderConfig config = RedisLoader.LoaderConfig.load(formal);
        assertEquals(100_000_000, config.normalCount());
        assertEquals(1_000_000, config.specialCount());
        assertEquals(300, config.maxMembersPerMultiplier());
        String text = Files.readString(formal, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertFalse(text.contains("seed=") || text.contains("redis.enabled") || text.contains("write-enabled")
                || text.contains("jsonl") || text.contains("output.file") || text.contains("weight=0"));

        Properties tooMany = properties(1);
        tooMany.setProperty("generation.normal-count", "2147483648");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(tooMany));
        Properties zeroWeight = properties(1);
        zeroWeight.setProperty("generation.symbol.9.weight", "0");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(zeroWeight));
        Properties seed = properties(1);
        seed.setProperty("seed", "7");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(seed));
        Properties special = properties(1);
        special.setProperty("generation.special-count", "2147483648");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(special));
    }

    @Test void generatedPositiveRoundsCommitZaddRpushLtrimInSameTransactions() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            Path config = temp.resolve("generator.properties");
            Properties properties = properties(40);
            properties.setProperty("redis.port", Integer.toString(redis.port()));
            properties.setProperty("generation.batch-size", "3");
            properties.setProperty("generation.max-members-per-multiplier", "7");
            try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { properties.store(writer, null); }
            RedisLoader.LoadSummary summary = RedisLoader.run(config);
            redis.assertHealthy();
            assertEquals(40, summary.normalGenerated());
            assertEquals(0, summary.specialGenerated());
            assertTrue(summary.normalWritten() > 0);
            assertEquals(summary.normalWritten(), redis.zadds);
            assertEquals(summary.normalWritten(), redis.rpushes);
            assertEquals(summary.normalWritten(), redis.ltrims);
            assertTrue(redis.transactions > 0);
            assertTrue(redis.keys.contains("PerKeyList_008002060"));
            assertTrue(redis.keys.stream().anyMatch(key -> key.startsWith("BetLog:008002060:")));
            assertTrue(redis.members.stream().allMatch(member -> member.startsWith("CG2~")
                    && !member.startsWith("{") && !member.contains("totalWin")));
        }
    }

    @Test void keysUseCommonPlatformContractAndIntegerMultiplier() {
        assertEquals("PerKeyList_008002060", RedisLoader.normalIndex(8_002_060));
        assertEquals("MaryKeyList_008002060", RedisLoader.specialIndex(8_002_060));
        assertEquals("BetLog:008002060:000001", RedisLoader.normalList(8_002_060, "1"));
        assertEquals("MaryLog:008002060:000012", RedisLoader.specialList(8_002_060, "12"));
        assertEquals("12", RedisLoader.multiplier(new java.math.BigDecimal("12")));
    }

    private static Properties properties(int normalCount) {
        Properties p = new Properties();
        p.setProperty("redis.host", "127.0.0.1"); p.setProperty("redis.port", "6379");
        p.setProperty("redis.username", ""); p.setProperty("redis.password", "");
        p.setProperty("redis.database", "0"); p.setProperty("redis.ssl", "false");
        p.setProperty("redis.connect-timeout-ms", "5000"); p.setProperty("redis.socket-timeout-ms", "30000");
        p.setProperty("redis.game-id", "8002060"); p.setProperty("generation.normal-count", Integer.toString(normalCount));
        p.setProperty("generation.special-count", "0"); p.setProperty("generation.batch-size", "10");
        p.setProperty("generation.max-members-per-multiplier", "300");
        p.setProperty("generation.max-consecutive-wins", "10");
        p.setProperty("generation.normal-max-win-multiplier", "20000");
        p.setProperty("generation.special-max-win-multiplier", "20000");
        return p;
    }

    private static final class FakeRedis implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        final List<String> keys = new ArrayList<>();
        final List<String> members = new ArrayList<>();
        volatile int zadds, rpushes, ltrims, transactions;
        FakeRedis() throws IOException { thread = new Thread(this::serve, "isolated-fake-redis"); thread.setDaemon(true); thread.start(); }
        int port() { return server.getLocalPort(); }
        void serve() {
            try (var socket = server.accept(); var in = new BufferedInputStream(socket.getInputStream());
                 var out = new BufferedOutputStream(socket.getOutputStream())) {
                int queued = 0;
                while (true) {
                    List<String> command;
                    try { command = readCommand(in); } catch (EOFException done) { break; }
                    switch (command.get(0).toUpperCase(Locale.ROOT)) {
                        case "PING" -> write(out, "+PONG\r\n");
                        case "MULTI" -> { queued = 0; write(out, "+OK\r\n"); }
                        case "ZADD" -> { zadds++; queued++; keys.add(command.get(1)); write(out, "+QUEUED\r\n"); }
                        case "RPUSH" -> { rpushes++; queued++; keys.add(command.get(1)); members.add(command.get(2)); write(out, "+QUEUED\r\n"); }
                        case "LTRIM" -> { ltrims++; queued++; keys.add(command.get(1)); assertEquals("-7", command.get(2)); assertEquals("-1", command.get(3)); write(out, "+QUEUED\r\n"); }
                        case "EXEC" -> { transactions++; write(out, "*" + queued + "\r\n"); for (int i=0;i<queued;i++) write(out, ":1\r\n"); }
                        default -> throw new IllegalStateException("unexpected Redis command " + command.get(0));
                    }
                    out.flush();
                }
            } catch (Throwable error) { if (!server.isClosed()) failure.set(error); }
        }
        void assertHealthy() { if (failure.get() != null) fail(failure.get()); }
        static List<String> readCommand(BufferedInputStream in) throws Exception {
            int star = in.read(); if (star < 0) throw new EOFException(); if (star != '*') throw new IOException("expected array");
            int count = Integer.parseInt(line(in)); List<String> result = new ArrayList<>(count);
            for (int i=0;i<count;i++) { if (in.read() != '$') throw new IOException("expected bulk"); int length=Integer.parseInt(line(in)); byte[] data=in.readNBytes(length); if(data.length!=length||in.read()!='\r'||in.read()!='\n') throw new EOFException(); result.add(new String(data,StandardCharsets.UTF_8)); }
            return result;
        }
        static String line(BufferedInputStream in) throws Exception { var result=new java.io.ByteArrayOutputStream(); int previous=-1; while(true){int current=in.read();if(current<0)throw new EOFException();if(previous=='\r'&&current=='\n')break;if(previous>=0)result.write(previous);previous=current;}return result.toString(StandardCharsets.UTF_8); }
        static void write(BufferedOutputStream out,String value)throws Exception{out.write(value.getBytes(StandardCharsets.US_ASCII));}
        @Override public void close() throws Exception { server.close(); thread.join(2000); assertHealthy(); }
    }
}
