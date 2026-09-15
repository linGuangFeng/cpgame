package com.cpgame.luckycatii.server;

import com.cpgame.luckycatii.GameRules;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Path configPath = Path.of(options.getOrDefault("config", "dist/controller.properties")).toAbsolutePath().normalize();
        if (options.get("publish") != null && !options.get("publish").isBlank()) {
            System.setProperty("lucky-cat-ii.publish", options.get("publish"));
        }
        AppConfig config = AppConfig.load(configPath, port);
        if (options.get("publish") != null && !options.get("publish").isBlank()) {
            config = new AppConfig(config.port(), config.initialBalance(),
                    Path.of(options.get("publish")).toAbsolutePath().normalize(),
                    config.redisHost(), config.redisPort(), config.redisUsername(), config.redisPassword(),
                    config.redisDatabase(), config.redisConnectTimeoutMs(), config.redisSocketTimeoutMs(),
                    config.redisGameId(), config.lossWeight(), config.winWeight(), config.specialWeight());
        }
        LuckyCatIIServer server = new LuckyCatIIServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "lucky-cat-ii-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=%d port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s redis=%s:%d db=%d%n",
                GameRules.GAME_ID, config.port(), ProcessHandle.current().pid(), GameRules.RULES_VERSION,
                GameRules.RULES_HASH, config.publishDirectory(), config.redisHost(), config.redisPort(),
                config.redisDatabase());
        System.out.println("试玩=http://localhost:" + config.port()
                + "/?gid=50&t=local-demo-token&ai=luck_single_50&btt=1&l=pt&sip=localhost:" + config.port());
        new CountDownLatch(1).await();
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                if (!result.containsKey("config")) result.put("config", args[i]);
                continue;
            }
            String key = args[i].substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            result.put(key, value);
        }
        return result;
    }

    private static int requiredPort(Map<String, String> options) {
        String raw = firstNonBlank(options.get("port"), System.getProperty("cpgame.demo.port"),
                System.getenv("CPGAME_DEMO_PORT"));
        if (raw == null) throw new IllegalArgumentException("platform must inject --port in range 50000-59999");
        int port = Integer.parseInt(raw);
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        return port;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
