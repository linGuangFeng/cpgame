package com.cpgame.luckydragon.api;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * v18 受管试玩入口。平台为当前游戏创建唯一 Host JVM 和动态 5xxxx 监听端口，
 * 本 Controller 在同一 JVM 内挂载路由，绝不派生子进程或创建第二个监听器。
 */
public final class ServerMain {
    private static final String PROVIDER_PROPERTY = "com.sun.net.httpserver.HttpServerProvider";
    private static final String REQUIRED_PROVIDER = "com.lgf.agentai.service.CpgameSharedHttpServerProvider";
    private static final String ADMIN_PROVIDER = "com.cpgame.admin.CpgameSharedHttpServerProvider";

    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        requireSharedProvider();
        Arguments arguments = arguments(args);
        Path configFile = arguments.configFile();
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) { config.load(reader); }
        if (config.containsKey("seed") || config.containsKey("port") || config.containsKey("controller.api-port")) {
            throw new IllegalArgumentException("managed Controller forbids seed and hard-coded port settings");
        }
        Path stateDirectory = configFile.getParent().resolve(config.getProperty("state.directory", "data")).normalize();
        BigDecimal initialBalance = new BigDecimal(config.getProperty("initial.balance", "10000.00"));
        LuckyDragonService service = new LuckyDragonService(stateDirectory, initialBalance, config);
        // 在平台 mounting 上下文内，Provider 返回不绑定 Socket 的 VirtualHttpServer。
        HttpServer routes = HttpServer.create(InetSocketAddress.createUnresolved("shared-http", 1), 0);
        new LuckyDragonController(service).register(routes);
        routes.start();
        System.out.println("Lucky Dragon managed Controller mounted port=" + arguments.port()
            + "; rulesHash=" + GameRuleCore.RULES_HASH);
        new CountDownLatch(1).await();
    }

    private static Arguments arguments(String[] args) {
        Path config = null;
        Integer port = null;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if ("--config".equals(option) && index + 1 < args.length) {
                config = Path.of(args[++index]).toAbsolutePath().normalize();
            } else if ("--port".equals(option) && index + 1 < args.length) {
                try { port = Integer.parseInt(args[++index]); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("managed port must be an integer"); }
            } else if (!option.startsWith("--") && config == null) {
                config = Path.of(option).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("expected --config <path> --port <50000-59999>");
            }
        }
        if (config == null || port == null || port < 50_000 || port > 59_999) {
            throw new IllegalArgumentException("expected --config <path> --port <50000-59999>");
        }
        return new Arguments(config, port);
    }

    private static void requireSharedProvider() {
        String provider = System.getProperty(PROVIDER_PROPERTY, "");
        if (!REQUIRED_PROVIDER.equals(provider) && !ADMIN_PROVIDER.equals(provider)) {
            throw new IllegalStateException("Lucky Dragon may only run inside its platform-managed Host JVM; standalone listener refused");
        }
    }

    private record Arguments(Path configFile, int port) { }
}
