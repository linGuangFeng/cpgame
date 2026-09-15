package com.cpgame.admin;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CpgameRedisMemoryRecoveryTest {
    @Test
    void unsupportedInstanceDoesNotDisableOtherGamesOrLaterRequests() throws Exception {
        Path root = Files.createTempDirectory("redis-memory-recovery");
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            for (String game : List.of("61-Test", "62-Test")) {
                Path config = root.resolve("generator").resolve(game).resolve("dist/generator.properties");
                Files.createDirectories(config.getParent());
                Files.writeString(config, "redis.host=127.0.0.1\nredis.port=" + server.getLocalPort()
                    + "\nredis.database=0\n");
            }
            try (var worker = Executors.newSingleThreadExecutor()) {
                Future<?> replies = worker.submit(() -> {
                    try {
                        for (String response : List.of("-ERR unknown command 'MEMORY'", ":12239",
                                ":12000", "-NOPERM permission denied", ":13000")) {
                            try (Socket socket = server.accept()) {
                                socket.setSoTimeout(3000);
                                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                                int count = Integer.parseInt(input.readLine().substring(1));
                                List<String> command = new ArrayList<>();
                                for (int i = 0; i < count; i++) {
                                    input.readLine();
                                    command.add(input.readLine());
                                }
                                assertEquals("MEMORY", command.get(0));
                                socket.getOutputStream().write((response + "\r\n").getBytes(StandardCharsets.UTF_8));
                                socket.getOutputStream().flush();
                            }
                        }
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                });
                var service = new CpgameRedisCacheService(root);
                var old = readMemory(service, "61-Test", "BetLog:000000061:000001");
                assertTrue(old.ok());
                assertFalse(old.memorySupported());
                var other = readMemory(new CpgameRedisCacheService(root), "62-Test", "BetLog:000000062:000001");
                assertTrue(other.ok());
                assertTrue(other.memorySupported());
                assertEquals(12239L, other.memory().get("BetLog:000000062:000001"));
                var recovered = readMemory(service, "61-Test", "BetLog:000000061:000001");
                assertTrue(recovered.memorySupported());
                assertEquals(12000L, recovered.memory().get("BetLog:000000061:000001"));
                var denied = readMemory(service, "61-Test", "BetLog:000000061:000001");
                assertFalse(denied.ok());
                assertTrue(denied.memorySupported());
                assertTrue(denied.message().contains("NOPERM"));
                assertEquals(13000L, readMemory(service, "61-Test", "BetLog:000000061:000001")
                    .memory().get("BetLog:000000061:000001"));
                replies.get(5, TimeUnit.SECONDS);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CpgameRedisCacheService.MemoryBatch readMemory(CpgameRedisCacheService service, String game, String key) {
        return service.memory(game, List.of(key), Map.of(key, 1L));
    }

    public static void main(String[] args) throws Exception {
        new CpgameRedisMemoryRecoveryTest().unsupportedInstanceDoesNotDisableOtherGamesOrLaterRequests();
        System.out.println("PASS: unsupported Redis isolation, retry recovery, and permission error recovery");
    }
}
