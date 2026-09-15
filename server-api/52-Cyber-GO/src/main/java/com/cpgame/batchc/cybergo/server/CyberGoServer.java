package com.cpgame.batchc.cybergo.server;

import com.cpgame.batchc.cybergo.GameRuleCore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 平台受管的每游戏独立 Java 进程入口。
 *
 * <p>端口只能由 {@code --port}、系统属性 {@code cpgame.demo.port} 或环境变量
 * {@code CPGAME_DEMO_PORT} 注入，并且必须位于 50000-59999。入口只创建一个绑定
 * 全部网卡的 HTTP 监听器，不派生任何 Java/Node 子进程。</p>
 */
public final class CyberGoServer {
    private static final int FIRST_MANAGED_PORT = 50000;
    private static final int LAST_MANAGED_PORT = 59999;
    private static Lifecycle lifecycle;

    public static synchronized void main(String[] args) throws Exception {
        if (lifecycle != null) throw new IllegalStateException("Cyber GO Controller 已挂载");
        LaunchOptions options = LaunchOptions.parse(args);
        Path configPath = options.configPath();
        if (configPath == null) throw new IllegalArgumentException("必须通过 --config= 指定server.properties");
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) { config.load(input); }
        validateConfigurationKeys(config);

        String bind = options.bindAddress() == null ? config.getProperty("server.bind", "0.0.0.0") : options.bindAddress();
        if (!("0.0.0.0".equals(bind) || "::".equals(bind))) {
            throw new IllegalArgumentException("受管试玩必须监听所有网卡，bind只能是0.0.0.0或::");
        }
        int port = managedPort(options.port());
        Path configuredRoot = options.publishDirectory() == null
                ? Path.of(config.getProperty("static.root", "../../../publish/52-Cyber-GO"))
                : options.publishDirectory();
        Path staticRoot = (configuredRoot.isAbsolute() ? configuredRoot : configPath.getParent().resolve(configuredRoot))
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(staticRoot.resolve("index.html"))) {
            throw new IllegalArgumentException("静态目录缺少index.html: " + staticRoot);
        }

        GameRuleCore core = new GameRuleCore();
        SessionStore sessions = new SessionStore(new BigDecimal(config.getProperty("player.initialBalance", "10000.00")));
        Path redisConfig = resolveExisting(configPath.getParent(),
                config.getProperty("redis.config", "../../../generator/52-Cyber-GO/dist/generator.properties"),
                Path.of("generator", "52-Cyber-GO", "dist", "generator.properties"));
        CyberGoController controller = new CyberGoController(core, sessions, new com.cpgame.batchc.cybergo.RedisRoundPool(
                com.cpgame.batchc.cybergo.GeneratorConfig.load(redisConfig), core));
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/") || path.startsWith("/cp/api/") || path.equals("/health")
                    || path.equals("/__controller/status")) controller.handle(exchange);
            else serveStatic(exchange, staticRoot);
        });
        server.start();
        lifecycle = new Lifecycle(server, executor);
        System.out.println("Cyber GO Java API与静态试玩已监听 " + bind + ":" + port);
        System.out.println("规则哈希: " + core.rulesHash());
        System.out.println("静态目录: " + staticRoot);
        System.out.println("受管进程 PID: " + ProcessHandle.current().pid());
        System.out.println("Controller类加载器: " + CyberGoServer.class.getClassLoader());
        System.out.println("端口来源: 平台动态注入（50000-59999）");
    }

    /** 仅供进程关闭钩子或定向测试释放当前 Controller。 */
    public static synchronized void stopController() {
        Lifecycle active = lifecycle;
        if (active == null) return;
        lifecycle = null;
        active.server().stop(0);
        active.executor().shutdownNow();
    }

    private record Lifecycle(HttpServer server, ExecutorService executor) { }

    private static void serveStatic(HttpExchange exchange, Path root) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); exchange.close(); return;
        }
        String requested = exchange.getRequestURI().getPath();
        if (requested.equals("/")) requested = "/index.html";
        Path file = root.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1); exchange.close(); return;
        }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
        exchange.getResponseHeaders().set("Cache-Control", file.getFileName().toString().equals("index.html") ? "no-cache" : "public, max-age=3600");
        exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } else exchange.close();
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> item : Map.ofEntries(
                Map.entry(".html", "text/html; charset=utf-8"), Map.entry(".js", "application/javascript; charset=utf-8"),
                Map.entry(".json", "application/json; charset=utf-8"), Map.entry(".css", "text/css; charset=utf-8"),
                Map.entry(".png", "image/png"), Map.entry(".jpg", "image/jpeg"), Map.entry(".jpeg", "image/jpeg"),
                Map.entry(".mp3", "audio/mpeg"), Map.entry(".wav", "audio/wav"), Map.entry(".ttf", "font/ttf"),
                Map.entry(".ico", "image/x-icon"), Map.entry(".bin", "application/octet-stream"),
                Map.entry(".plist", "application/xml; charset=utf-8")).entrySet()) {
            if (name.endsWith(item.getKey())) return item.getValue();
        }
        return "application/octet-stream";
    }

    private static Path resolveExisting(Path base, String raw, Path repoRelative) {
        Path configured = Path.of(raw);
        if (configured.isAbsolute() && Files.isRegularFile(configured)) return configured.normalize();
        Path fromBase = base.resolve(raw).normalize();
        if (Files.isRegularFile(fromBase)) return fromBase.toAbsolutePath().normalize();
        Path cursor = base.toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(repoRelative);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("找不到 generator.properties: " + fromBase);
    }

    private static void validateConfigurationKeys(Properties config) {
        Set<String> allowed = Set.of("server.bind", "static.root", "player.initialbalance", "redis.config");
        for (String key : config.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("seed")) throw new IllegalArgumentException("正式配置禁止 seed: " + key);
            // 平台共享宿主会把 generator.properties 的 redis.host/port/database/password 叠进运行时配置。
            if (allowed.contains(lower) || lower.startsWith("redis.")) continue;
            throw new IllegalArgumentException("正式配置包含非白名单项: " + key);
        }
    }

    private static int managedPort(String argumentPort) {
        String raw = argumentPort;
        if (raw == null || raw.isBlank()) raw = System.getProperty("cpgame.demo.port");
        if (raw == null || raw.isBlank()) raw = System.getenv("CPGAME_DEMO_PORT");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少平台动态端口；请注入 --port <50000-59999>");
        }
        int port;
        try { port = Integer.parseInt(raw); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("平台动态端口不是整数: " + raw, error); }
        if (port < FIRST_MANAGED_PORT || port > LAST_MANAGED_PORT) {
            throw new IllegalArgumentException("平台动态端口必须位于50000-59999: " + port);
        }
        return port;
    }

    private record LaunchOptions(Path configPath, String port, String bindAddress, Path publishDirectory) {
        static LaunchOptions parse(String[] args) {
            Path config = null;
            String port = null;
            String bind = null;
            Path publish = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument.startsWith("--config=")) config = normalizedPath(argument.substring(9), "--config");
                else if (argument.startsWith("--port=")) port = requiredInline(argument.substring(7), "--port");
                else if (argument.startsWith("--bind=")) bind = requiredInline(argument.substring(7), "--bind");
                else if (argument.startsWith("--publish=")) publish = normalizedPath(argument.substring(10), "--publish");
                else if ("--config".equals(argument)) config = normalizedPath(requireValue(args, ++index, argument), argument);
                else if ("--port".equals(argument)) port = requireValue(args, ++index, argument);
                else if ("--bind".equals(argument)) bind = requireValue(args, ++index, argument);
                else if ("--publish".equals(argument)) publish = normalizedPath(requireValue(args, ++index, argument), argument);
                else if (!argument.startsWith("--") && config == null) config = normalizedPath(argument, "config");
                else throw new IllegalArgumentException("未知参数: " + argument);
            }
            return new LaunchOptions(config, port, bind, publish);
        }

        private static String requireValue(String[] args, int index, String argument) {
            if (index >= args.length || args[index].isBlank()) throw new IllegalArgumentException(argument + " 缺少参数值");
            return args[index];
        }

        private static String requiredInline(String value, String argument) {
            if (value.isBlank()) throw new IllegalArgumentException(argument + " 缺少参数值");
            return value;
        }

        private static Path normalizedPath(String value, String argument) {
            if (value.isBlank()) throw new IllegalArgumentException(argument + " 缺少参数值");
            return Path.of(value).toAbsolutePath().normalize();
        }
    }
}
