package com.cpgame.batchc.cybergo;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RedisRoundPoolTest {
    @Test
    void emptyPoolFailsExplicitlyWithoutRuntimeGenerationFallback() throws Exception {
        try (EmptyRedis redis = new EmptyRedis()) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            properties.setProperty("redis.host", "127.0.0.1");
            properties.setProperty("redis.password", "");
            properties.setProperty("redis.port", Integer.toString(redis.port()));
            properties.setProperty("redis.database", "0");
            Path file = Files.createTempFile("cyber-go-empty-pool-", ".properties");
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(writer, "test"); }
            file.toFile().deleteOnExit();

            RedisRoundPool pool = new RedisRoundPool(GeneratorConfig.load(file), new GameRuleCore());
            IOException error = assertThrows(IOException.class, pool::claim);
            assertTrue(error.getMessage().startsWith("POOL_EMPTY_"), error.getMessage());
            redis.awaitHealthy();
        }
    }

    private static final class EmptyRedis implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        EmptyRedis() throws IOException {
            server = new ServerSocket(0);
            thread = Thread.ofPlatform().name("empty-redis-52").start(this::serve);
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket socket = server.accept();
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                while (true) {
                    List<String> command;
                    try { command = readCommand(input); }
                    catch (EOFException done) { break; }
                    switch (command.getFirst()) {
                        case "PING" -> output.write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                        case "SELECT" -> output.write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
                        case "ZRANGE" -> output.write("*0\r\n".getBytes(StandardCharsets.US_ASCII));
                        case "LLEN" -> output.write(":0\r\n".getBytes(StandardCharsets.US_ASCII));
                        case "EVAL" -> output.write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
                        default -> throw new IOException("unexpected command: " + command.getFirst());
                    }
                    output.flush();
                }
            } catch (Throwable error) { failure.set(error); }
        }

        void awaitHealthy() throws Exception {
            thread.join(5_000);
            if (failure.get() != null) throw new AssertionError("Empty Redis failed", failure.get());
        }

        private static List<String> readCommand(BufferedInputStream input) throws Exception {
            int prefix = input.read();
            if (prefix < 0) throw new EOFException();
            if (prefix != '*') throw new IOException("not RESP array");
            int count = Integer.parseInt(readLine(input));
            List<String> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                if (input.read() != '$') throw new IOException("not RESP bulk");
                int length = Integer.parseInt(readLine(input));
                byte[] value = input.readNBytes(length);
                if (value.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
                result.add(new String(value, StandardCharsets.UTF_8));
            }
            return result;
        }

        private static String readLine(BufferedInputStream input) throws Exception {
            StringBuilder value = new StringBuilder();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') return value.toString();
                if (previous >= 0) value.append((char) previous);
                previous = current;
            }
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(1_000);
        }
    }
}
