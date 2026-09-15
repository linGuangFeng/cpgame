package com.cpgame.coinmastergo.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "coin-master")
public class GameProperties {
    private BigDecimal initialBalance = new BigDecimal("1000.00");
    private Path stateFile = Path.of("data", "coin-master-go-state.json");
    private String publishDirectory = Path.of("..", "..", "publish", "1407-mac-Coin-Master-GO").toString();
    private Long demoSeed;

    public BigDecimal getInitialBalance() { return initialBalance; }
    public void setInitialBalance(BigDecimal initialBalance) { this.initialBalance = initialBalance; }
    public Path getStateFile() { return stateFile; }
    public void setStateFile(Path stateFile) { this.stateFile = stateFile; }
    public String getPublishDirectory() { return publishDirectory; }
    public void setPublishDirectory(String publishDirectory) { this.publishDirectory = publishDirectory; }
    public Long getDemoSeed() { return demoSeed; }
    public void setDemoSeed(Long demoSeed) { this.demoSeed = demoSeed; }
}
