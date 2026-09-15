package com.cpgame.clubgoddess.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.github.fppt.jedismock.RedisServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** package 后从非 dist 工作目录实际执行自定位 CMD 和带依赖 JAR。 */
class DistLauncherIT {
    @TempDir Path temp;

    @Test void packagedCmdRunsShadedJarAgainstIsolatedRedisFromAnotherWorkingDirectory() throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path sourceDist = project.resolve("dist");
        Path isolatedDist = temp.resolve("isolated-dist");
        Path elsewhere = temp.resolve("elsewhere");
        Files.createDirectories(isolatedDist);
        Files.createDirectories(elsewhere);
        Files.copy(sourceDist.resolve("club-goddess-redis-loader.jar"),
                isolatedDist.resolve("club-goddess-redis-loader.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sourceDist.resolve("start-redis-loader.cmd"),
                isolatedDist.resolve("start-redis-loader.cmd"), StandardCopyOption.REPLACE_EXISTING);

        RedisServer redis = RedisServer.newRedisServer();
        redis.start();
        try {
            Path config = isolatedDist.resolve("generator.properties");
            Properties p = IsolatedRedisIntegrationTest.propertiesForExternalUse(redis.getBindPort(), 250, 5,
                    88_882_060L);
            try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { p.store(writer, null); }

            Process process = new ProcessBuilder("cmd.exe", "/d", "/c",
                    isolatedDist.resolve("start-redis-loader.cmd").toString(), "--no-pause")
                    .directory(elsewhere.toFile()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CMD 超时");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);

            RedisLoader.LoaderConfig loaded = RedisLoader.LoaderConfig.load(config);
            try (RedisLoader.RedisConnection connection = RedisLoader.RedisConnection.connect(loaded)) {
                Object keys = connection.command("KEYS", "*");
                Object value = connection.command("ZRANGE", "PerKeyList_088882060", "0", "-1");
                assertInstanceOf(List.class, value);
                assertFalse(((List<?>) value).isEmpty(), "实际 CMD/JAR 未写入预期倍率索引；keys=" + keys
                        + "；output=" + output);
            }
        } finally {
            redis.stop();
        }
    }
}
