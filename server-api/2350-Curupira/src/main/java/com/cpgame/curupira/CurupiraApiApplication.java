package com.cpgame.curupira;

import com.cpgame.curupira.api.InMemoryRoundSource;
import com.cpgame.curupira.api.RedisRoundStore;
import com.cpgame.curupira.api.RoundSource;
import com.cpgame.curupira.core.GameRuleCore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Properties;

@SpringBootApplication
public class CurupiraApiApplication {
    @Bean
    GameRuleCore gameRuleCore() {
        return new GameRuleCore();
    }

    @Bean
    RoundSource roundSource(GameRuleCore core,
                            @Value("${curupira.redis.enabled}") boolean redisEnabled,
                            @Value("${curupira.redis.host}") String host,
                            @Value("${curupira.redis.port}") int port,
                            @Value("${curupira.redis.database}") int database,
                            @Value("${curupira.redis.username:}") String username,
                            @Value("${curupira.redis.password:}") String password,
                            @Value("${curupira.redis.game-id:8002350}") int gameId,
                            @Value("${curupira.redis.ssl:false}") boolean ssl) throws Exception {
        if (!redisEnabled) return new InMemoryRoundSource(core);
        Properties config = new Properties();
        config.setProperty("redis.host", host);
        config.setProperty("redis.port", Integer.toString(port));
        config.setProperty("redis.database", Integer.toString(database));
        config.setProperty("redis.username", username);
        config.setProperty("redis.password", password);
        config.setProperty("redis.ssl", Boolean.toString(ssl));
        config.setProperty("redis.game-id", Integer.toString(gameId));
        return RedisRoundStore.connect(config);
    }

    public static void main(String[] args) {
        SpringApplication.run(CurupiraApiApplication.class, args);
    }
}
