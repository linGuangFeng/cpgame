package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.service.GameProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GameProperties.class)
public class CoinMasterGoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoinMasterGoApplication.class, args);
    }
}
