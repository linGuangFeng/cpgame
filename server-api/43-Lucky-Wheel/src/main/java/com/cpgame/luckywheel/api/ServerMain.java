package com.cpgame.luckywheel.api;

import com.cpgame.luckywheel.core.GameRuleCore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class ServerMain {
    private static final String PROVIDER_PROPERTY = "com.sun.net.httpserver.HttpServerProvider";
    private static final Set<String> REQUIRED_PROVIDERS = Set.of(
            "com.lgf.agentai.service.CpgameSharedHttpServerProvider",
            "com.cpgame.admin.CpgameSharedHttpServerProvider");
    private ServerMain() { }
    public static void main(String[] args) throws Exception {
        requireManagedProvider();
        LaunchOptions options = LaunchOptions.parse(args);
        Path configFile = options.configFile().toAbsolutePath();
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) { config.load(reader); }
        if (config.containsKey("seed")) throw new IllegalArgumentException("试玩与正式服务配置禁止seed");
        String bind = options.bindAddress() == null
                ? config.getProperty("server.bind", config.getProperty("bind", "0.0.0.0"))
                : options.bindAddress();
        if (!("0.0.0.0".equals(bind) || "::".equals(bind))) {
            throw new IllegalArgumentException("受管试玩必须监听所有网卡，bind只能是0.0.0.0或::");
        }
        String injectedPort = options.port();
        if (injectedPort == null || injectedPort.isBlank()) injectedPort = System.getProperty("cpgame.demo.port");
        if (injectedPort == null || injectedPort.isBlank()) injectedPort = System.getenv("CPGAME_DEMO_PORT");
        if (injectedPort == null || injectedPort.isBlank()) injectedPort = managedProcessPort();
        if (injectedPort == null || injectedPort.isBlank()) {
            throw new IllegalArgumentException("缺少平台动态端口；请注入 --port <50000-59999>");
        }
        int port;
        try { port = Integer.parseInt(injectedPort); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("平台动态端口不是整数: " + injectedPort, error); }
        if (port < 50000 || port > 59999) {
            throw new IllegalArgumentException("平台动态端口必须位于50000-59999: " + port);
        }
        Path base = configFile.getParent();
        Path stateDir = base.resolve(config.getProperty("stateDir", "data")).normalize();
        Path staticRoot = options.publishDirectory() == null
                ? base.resolve(config.getProperty("staticRoot", "../../../publish/43-Lucky-Wheel")).normalize()
                : options.publishDirectory().toAbsolutePath().normalize();
        if (!Files.isRegularFile(staticRoot.resolve("index.html"))) throw new IllegalArgumentException("静态入口不存在: " + staticRoot);
        ControllerRuntime runtime = new ControllerRuntime(stateDir, config);
        runtime.mount();
        // v3 Host Provider 返回不额外绑定 Socket 的虚拟 HttpServer；唯一监听属于当前受管 Host JVM。
        HttpServer server = HttpServer.create(InetSocketAddress.createUnresolved("shared-http", 1), 0);
        new LuckyWheelController(runtime::service, runtime::status).register(server);
        registerLifecycle(server, runtime);
        server.start();
        System.out.println("Lucky Wheel Java API已挂载到平台受管端口: " + port);
        System.out.println("Controller 由平台以当前游戏唯一受管Java进程托管，禁止派生额外进程");
        System.out.println("rulesHash=" + GameRuleCore.RULES_HASH);
        new CountDownLatch(1).await();
    }

    private static void requireManagedProvider() {
        if (!REQUIRED_PROVIDERS.contains(System.getProperty(PROVIDER_PROPERTY, ""))) {
            throw new IllegalStateException("Lucky Wheel仅允许在平台Controller v3受管Host内启动");
        }
    }

    private static String managedProcessPort() {
        String[] processArguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int index = 0; index < processArguments.length; index++) {
            String argument = processArguments[index];
            if (argument.startsWith("--port=")) return argument.substring("--port=".length());
            if ("--port".equals(argument) && index + 1 < processArguments.length) {
                return processArguments[index + 1];
            }
        }
        return null;
    }

    private record LaunchOptions(Path configFile, String port, String bindAddress,
                                 Path publishDirectory) {
        static LaunchOptions parse(String[] args) {
            Path config = null;
            String port = null;
            String bind = null;
            Path publish = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--config".equals(argument)) config = Path.of(requireValue(args, ++index, argument));
                else if ("--port".equals(argument)) port = requireValue(args, ++index, argument);
                else if ("--bind".equals(argument)) bind = requireValue(args, ++index, argument);
                else if ("--publish".equals(argument)) publish = Path.of(requireValue(args, ++index, argument));
                else if (!argument.startsWith("--") && config == null) config = Path.of(argument);
                else throw new IllegalArgumentException("未知启动参数: " + argument);
            }
            if (config == null) config = Path.of("server.properties");
            return new LaunchOptions(config, port, bind, publish);
        }

        private static String requireValue(String[] args, int index, String argument) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException(argument + " 缺少参数值");
            }
            return args[index];
        }
    }

    private static void registerLifecycle(HttpServer server, ControllerRuntime runtime) {
        server.createContext("/__controller/status", exchange -> {
            if (!loopback(exchange)) { LuckyWheelController.send(exchange, 403, JsonCodec.write(Map.of("error", "loopback only"))); return; }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { LuckyWheelController.send(exchange, 405, JsonCodec.write(Map.of("error", "GET required"))); return; }
            LuckyWheelController.send(exchange, 200, JsonCodec.write(runtime.status()));
        });
        server.createContext("/__controller/unmount", exchange -> {
            if (!loopback(exchange)) { LuckyWheelController.send(exchange, 403, JsonCodec.write(Map.of("error", "loopback only"))); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { LuckyWheelController.send(exchange, 405, JsonCodec.write(Map.of("error", "POST required"))); return; }
            runtime.unmount();
            LuckyWheelController.send(exchange, 200, JsonCodec.write(runtime.status()));
        });
        server.createContext("/__controller/remount", exchange -> {
            if (!loopback(exchange)) { LuckyWheelController.send(exchange, 403, JsonCodec.write(Map.of("error", "loopback only"))); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { LuckyWheelController.send(exchange, 405, JsonCodec.write(Map.of("error", "POST required"))); return; }
            runtime.mount();
            LuckyWheelController.send(exchange, 200, JsonCodec.write(runtime.status()));
        });
    }

    private static boolean loopback(com.sun.net.httpserver.HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private static final class ControllerRuntime {
        private final Path stateDir;
        private final Properties config;
        private volatile LuckyWheelService service;
        private int generation;

        private ControllerRuntime(Path stateDir, Properties config) { this.stateDir = stateDir; this.config = config; }

        synchronized void mount() throws java.io.IOException {
            if (service != null) return;
            service = new LuckyWheelService(new PersistentSessionRepository(stateDir), RedisRoundStore.connect(config));
            generation++;
        }

        synchronized void unmount() {
            LuckyWheelService previous = service;
            service = null;
            if (previous != null) try { previous.close(); } catch (java.io.IOException ignored) { }
        }

        LuckyWheelService service() { return service; }

        Map<String,Object> status() {
            Map<String,Object> status = new LinkedHashMap<>();
            status.put("status", service == null ? "UNMOUNTED" : "UP");
            status.put("gameId", 43);
            status.put("rulesHash", GameRuleCore.RULES_HASH);
            status.put("sharedJvmPid", ProcessHandle.current().pid());
            status.put("controllerClassLoader", ServerMain.class.getClassLoader().getClass().getName());
            status.put("controllerClassLoaderIdentity", Integer.toHexString(System.identityHashCode(ServerMain.class.getClassLoader())));
            status.put("mountGeneration", generation);
            return status;
        }
    }
}
