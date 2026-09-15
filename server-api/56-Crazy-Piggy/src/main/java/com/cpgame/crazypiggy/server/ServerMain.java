package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.GameRules;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "demo-controller.properties").toAbsolutePath().normalize();
        AppConfig config = AppConfig.load(configPath);
        CrazyPiggyServer server = new CrazyPiggyServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Crazy Piggy Java API 已启动");
        System.out.println("rulesHash=" + GameRules.RULES_HASH);
        System.out.println("监听=" + config.address() + ":" + config.port());
        System.out.println("试玩=http://localhost:" + config.port() + "/?gid=56&t=demo-launch&ai=luck_single_10229&btt=1&l=en&language=en");
        new CountDownLatch(1).await();
    }
}
