package com.hd.cpgame.magicscroll2.server;

import com.hd.cpgame.magicscroll2.core.GameConstants;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        for (String key : options.keySet()) {
            if (!List.of("port", "config", "publish").contains(key)) {
                throw new IllegalArgumentException("Unsupported controller argument --" + key);
            }
        }
        String rawPort = options.get("port");
        if (rawPort == null || rawPort.isBlank()) rawPort = System.getenv("PORT");
        if (rawPort == null || rawPort.isBlank()) {
            throw new IllegalArgumentException("Platform must inject --port or PORT");
        }
        int port = Integer.parseInt(rawPort);
        Path configPath = Path.of(options.getOrDefault("config", "dist/controller.properties"))
                .toAbsolutePath().normalize();
        Path publish = options.containsKey("publish")
                ? Path.of(options.get("publish")).toAbsolutePath().normalize() : null;
        AppConfig config = AppConfig.load(configPath, port, publish);
        MagicScroll2Server server = new MagicScroll2Server(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Magic Scroll 2 Java API 已启动");
        System.out.println("rulesHash=" + GameConstants.RULES_HASH);
        System.out.println("监听=" + config.address + ":" + config.port);
        System.out.println("试玩=http://localhost:" + config.port + "/index.html?otk=local-demo-2001007&l=pt");
        new CountDownLatch(1).await();
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                values.put(arg.substring(2, eq), arg.substring(eq + 1));
                continue;
            }
            String key = arg.substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            values.put(key, value);
        }
        return values;
    }
}
