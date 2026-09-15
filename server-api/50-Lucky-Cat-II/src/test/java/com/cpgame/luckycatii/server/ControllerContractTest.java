package com.cpgame.luckycatii.server;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ControllerContractTest {
    @Test void configRejectsSeedAndHardCodedPort() throws Exception {
        Path file = Files.createTempFile("lc50-ctrl-", ".properties");
        Properties p = new Properties();
        p.setProperty("redis.host", "192.168.10.3");
        p.setProperty("redis.port", "6379");
        p.setProperty("redis.database", "15");
        p.setProperty("redis.game-id", "50");
        p.setProperty("redis.connect-timeout-ms", "1000");
        p.setProperty("redis.socket-timeout-ms", "1000");
        p.setProperty("round.loss-weight", "1");
        p.setProperty("round.win-weight", "1");
        p.setProperty("round.special-weight", "1");
        p.setProperty("publish.directory", Path.of("..", "..", "publish", "50-Lucky-Cat-II").toAbsolutePath().toString());
        p.setProperty("seed", "1");
        try (var writer = Files.newBufferedWriter(file)) { p.store(writer, ""); }
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file, 55050));
        p.remove("seed");
        p.setProperty("server.port", "55050");
        try (var writer = Files.newBufferedWriter(file)) { p.store(writer, ""); }
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file, 55050));
        p.remove("server.port");
        try (var writer = Files.newBufferedWriter(file)) { p.store(writer, ""); }
        AppConfig config = AppConfig.load(file, 55050);
        assertEquals(55050, config.port());
        assertEquals(new BigDecimal("10000.00"), config.initialBalance());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file, 29500));
    }
}
