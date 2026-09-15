package com.hd.cpgame.riocarnival.loader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 构建后手工入口：以独立 JVM 执行 fat JAR，并对隔离 Redis 状态做黑盒断言。 */
public final class LoaderJarSmokeHarness {
    private LoaderJarSmokeHarness() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要传入 dist Loader JAR 路径");
        Path jar = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("找不到 Loader JAR: " + jar);
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = new Properties();
            Path source = Paths.get("src", "dist", "generator.properties");
            try (java.io.Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { p.load(reader); }
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.normal-count", "4");
            p.setProperty("generation.special-count", "1");
            p.setProperty("generation.batch-size", "2");
            p.setProperty("generation.max-members-per-multiplier", "3");
            p.setProperty("generation.max-consecutive-wins", "100");
            p.setProperty("generation.normal-max-win-multiplier", "2147483647");
            p.setProperty("generation.special-max-win-multiplier", "2147483647");
            Path config = Files.createTempFile(Paths.get("target"), "rio-carnival-jar-smoke-", ".properties");
            try (java.io.Writer writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { p.store(writer, "smoke"); }

            Process process = new ProcessBuilder("java", "-Dfile.encoding=UTF-8", "-jar",
                jar.toString(), config.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("fat JAR 隔离 Redis 验证超时");
            }
            String output = read(process.getInputStream());
            if (process.exitValue() != 0 || !output.contains("LOAD_COMPLETE")
                || !output.contains("normal=4") || !output.contains("special=1"))
                throw new AssertionError("fat JAR 输出异常: " + output);
            if (!redis.zsets.containsKey("PerKeyList_000000045")
                || !redis.zsets.containsKey("MaryKeyList_000000045"))
                throw new AssertionError("普通/特殊索引 Key 不完整: " + redis.zsets.keySet());
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                if (entry.getValue().size() > 3) throw new AssertionError("LTRIM 未生效: " + entry.getKey());
            }
            if (redis.transactions.size() != 3) throw new AssertionError("事务批次数错误: " + redis.transactions.size());
            redis.assertHealthy();
            System.out.println("JAR_SMOKE_PASS transactions=" + redis.transactions.size()
                + " zsets=" + redis.zsets.keySet() + " lists=" + redis.lists.keySet());
            System.out.println(output.trim());
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
