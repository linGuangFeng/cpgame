package com.cpgame.curupira;

import com.cpgame.curupira.api.RedisRoundStore;
import com.cpgame.curupira.api.RoundSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Properties;

@SpringBootApplication
public class CurupiraApiApplication {
    @Bean
    @ConditionalOnProperty(name = "curupira.redis.enabled", havingValue = "true", matchIfMissing = true)
    RoundSource roundSource(@Value("${curupira.redis.host}") String host,
                            @Value("${curupira.redis.port}") int port,
                            @Value("${curupira.redis.database}") int database,
                            @Value("${curupira.redis.username:}") String username,
                            @Value("${curupira.redis.password:}") String password,
                            @Value("${curupira.redis.game-id:8002350}") int gameId,
                            @Value("${curupira.redis.ssl:false}") boolean ssl,
                            @Value("${curupira.redis.connect-timeout-ms:30000}") int connectTimeout,
                            @Value("${curupira.redis.socket-timeout-ms:30000}") int socketTimeout) throws Exception {
        Properties config = new Properties();
        config.setProperty("redis.host", host);
        config.setProperty("redis.port", Integer.toString(port));
        config.setProperty("redis.database", Integer.toString(database));
        config.setProperty("redis.username", username);
        config.setProperty("redis.password", password);
        config.setProperty("redis.ssl", Boolean.toString(ssl));
        config.setProperty("redis.game-id", Integer.toString(gameId));
        config.setProperty("redis.connect-timeout-ms", Integer.toString(connectTimeout));
        config.setProperty("redis.socket-timeout-ms", Integer.toString(socketTimeout));
        return RedisRoundStore.connect(config);
    }

    public static void main(String[] args) {
        SpringApplication.run(CurupiraApiApplication.class, args);
    }
}
