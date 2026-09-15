package com.cpgame.coinmastergo.generator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** 仅覆盖本次 generator follow-up 缺陷，不扩展到其他产物。 */
class IndependentAcceptanceTest {
    private final Path project = Path.of("").toAbsolutePath().normalize();

    @Test
    void sourceTransactionContainsZaddPushTrimBeforeSingleExec() throws Exception {
        String source = Files.readString(project.resolve(
                "src/main/java/com/cpgame/coinmastergo/generator/JedisRedisListWriter.java"));
        int multi = source.indexOf("jedis.multi()");
        int zadd = source.indexOf("transaction.zadd", multi);
        int push = source.indexOf("transaction.rpush", zadd);
        int trim = source.indexOf("transaction.ltrim", push);
        int exec = source.indexOf("transaction.exec()", trim);
        assertTrue(multi >= 0 && zadd > multi && push > zadd && trim > push && exec > trim);
    }

    @Test
    void formalSourcesHaveNoHistoricalRuntimeSourceOrForcedScenario() throws Exception {
        String source;
        try (var files = Files.walk(project.resolve("src/main"))) {
            source = files.filter(Files::isRegularFile).map(path -> {
                try { return Files.readString(path); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).reduce("", String::concat).toLowerCase(Locale.ROOT);
        }
        assertFalse(source.contains("fixtures/") || source.contains("fixtures\\")
                || source.contains("captures/") || source.contains("captures\\") || source.contains(".jsonl")
                || source.contains("demoscript") || source.contains("demo-script"));
        assertTrue(source.contains("securerandom"));
        assertFalse(source.contains("runtimeuseallowed") || source.contains("安全停止") || source.contains("安全退出"));
    }

    @Test
    void distNamesAndScriptContractAreExact() throws Exception {
        Path dist = project.resolve("dist");
        // Contract tokens are ASCII; read bytes losslessly so the assertion is independent of
        // the host JDK's platform-specific GBK decoder implementation.
        String command = Files.readString(dist.resolve("start-redis-loader.cmd"), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        String configuration = Files.readString(dist.resolve("generator.properties")).toLowerCase(Locale.ROOT);
        assertTrue(command.contains("%~dp0coin-master-go-redis-loader.jar"));
        assertTrue(command.contains("%~dp0generator.properties"));
        assertTrue(command.contains("--no-pause") && command.contains("pause"));
        assertFalse(configuration.contains("seed="));
        assertTrue(configuration.contains("generation.symbol.wild.weight=0"));
        assertTrue(configuration.contains("generation.card.silver.weight=5737"));
        assertTrue(configuration.contains("generation.card.gold.weight=1573"));
        assertTrue(configuration.contains("12.734531%"));
        assertTrue(configuration.contains("78.481532%"));
        assertTrue(configuration.contains("21.518468%"));
        assertTrue(configuration.contains("0倍结果概率不在配置中"));
        assertTrue(configuration.contains("generation.special-count=100000000"));
        assertTrue(configuration.contains("不宣称等于原厂长期概率或 rtp"));
    }
}
