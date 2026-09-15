package com.cpgame.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CpgameRedisNamespaceTest {
    @TempDir Path root;
    private void config() throws Exception {
        Path file = root.resolve("generator/41-Lucky-Panda/dist/generator.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "redis.host=redis.example\nredis.port=8021\nredis.database=0\n"
            + "redis.username=\nredis.password=test\nredis.ssl=false\nredis.connect-timeout-ms=5000\n"
            + "redis.socket-timeout-ms=30000\nredis.game-id=8000041\ngame.raw-id=41\n");
    }
    @Test void configuredCacheIdExcludesRawProtocolId() throws Exception {
        config();
        var service = new CpgameRedisCacheService(root);
        var discover = CpgameRedisCacheService.class.getDeclaredMethod("discover", String.class);
        discover.setAccessible(true);
        Object target = discover.invoke(service, "41-Lucky-Panda");
        var ids = target.getClass().getDeclaredMethod("gameIds");
        ids.setAccessible(true);
        assertEquals(List.of(8000041L), ids.invoke(target));
    }
    @Test void demoOverlayCopiesCompleteRedisConfiguration() throws Exception {
        config();
        var constructor = CpgameSharedDemoHostMain.class.getDeclaredConstructor(Path.class, int.class, String.class);
        constructor.setAccessible(true);
        Object host = constructor.newInstance(root, 50001, "test");
        var overlay = CpgameSharedDemoHostMain.class.getDeclaredMethod("overlayGeneratorRedis", String.class, Properties.class);
        overlay.setAccessible(true);
        Properties values = new Properties();
        values.setProperty("redis.game-id", "41");
        assertEquals(true, overlay.invoke(host, "41-Lucky-Panda", values));
        assertEquals("8000041", values.getProperty("redis.game-id"));
        assertEquals("8021", values.getProperty("redis.port"));
        assertEquals("0", values.getProperty("redis.database"));
        assertEquals("5000", values.getProperty("redis.connect-timeout-ms"));
        assertEquals("30000", values.getProperty("redis.socket-timeout-ms"));
        assertEquals("", values.getProperty("redis.username"));
        assertEquals("false", values.getProperty("redis.ssl"));
        assertEquals("test", values.getProperty("redis.password"));
    }
}
