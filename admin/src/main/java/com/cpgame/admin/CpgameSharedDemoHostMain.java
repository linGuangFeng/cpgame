package com.cpgame.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CPGame 的共享试玩 JVM。每个游戏仍有独立 Controller 和类加载器，但不再启动独立 java.exe。
 * 该类刻意只依赖 JDK，既可从开发 classpath 启动，也可通过 Spring Boot PropertiesLauncher
 * 从 agent-ai 的可执行 JAR 启动为独立进程。
 */
public final class CpgameSharedDemoHostMain {
    public static final String TOKEN_HEADER = "X-CPGame-Demo-Token";
    private static final Pattern SAFE_DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final Set<String> BOOT_LAUNCHERS = Set.of(
        "org.springframework.boot.loader.JarLauncher",
        "org.springframework.boot.loader.launch.JarLauncher");

    private final Path root;
    private final int port;
    private final String token;
    private final Map<String, GameRuntime> games = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "cpgame-shared-demo-http");
        thread.setDaemon(false);
        return thread;
    });

    private CpgameSharedDemoHostMain(Path root, int port, String token) {
        this.root = root.toAbsolutePath().normalize();
        this.port = port;
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path root = Path.of(required(options, "root"));
        int port = Integer.parseInt(required(options, "port"));
        String token = required(options, "token");
        new CpgameSharedDemoHostMain(root, port, token).run();
    }

    static LaunchCapability inspect(Path root, String directory) {
        if (!safeDirectory(directory)) return new LaunchCapability(false, "游戏目录名无效");
        CpgameSharedDemoHostMain inspector = new CpgameSharedDemoHostMain(root, 1, "inspect");
        try {
            ControllerDescriptor descriptor = inspector.descriptor(directory);
            if (descriptor.configFile != null && !descriptor.bootApplication) {
                Properties values = new Properties();
                try (InputStream input = Files.newInputStream(descriptor.configFile)) { values.load(input); }
                if (Boolean.parseBoolean(values.getProperty("redis.enabled", "false"))) {
                    String host = values.getProperty("redis.host", "127.0.0.1").strip();
                    int redisPort = Integer.parseInt(values.getProperty("redis.port", "6379").strip());
                    boolean configured = inspector.portOpen(host, redisPort);
                    boolean fallback = inspector.isLoopback(host) && inspector.portOpen("127.0.0.1", 6379)
                        && inspector.inferPreloaderMain(descriptor.jar) != null;
                    if (!configured && !fallback) {
                        return new LaunchCapability(false, "试玩依赖的 Redis 未就绪：" + host + ":" + redisPort);
                    }
                }
            }
            return new LaunchCapability(true, "可挂载到共享 Java 进程和共享端口");
        } catch (Exception error) {
            return new LaunchCapability(false, concise(error));
        } finally {
            inspector.requests.shutdownNow();
        }
    }

    private void run() throws Exception {
        if (!Files.isDirectory(root.resolve("server-api")) || !Files.isDirectory(root.resolve("publish"))) {
            throw new IllegalArgumentException("CPGame 产物根目录不完整：" + root);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        server.createContext("/health", exchange -> text(exchange, 200, "ok"));
        server.createContext("/games/", this::serveGameControl);
        server.createContext("/play/", this::servePublishedFile);
        server.createContext("/", this::serveSharedRequest);
        server.setExecutor(requests);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            requests.shutdownNow();
            games.values().forEach(GameRuntime::close);
        }, "cpgame-shared-demo-shutdown"));
        server.start();
        System.out.println("CPGame 共享试玩进程已启动：http://0.0.0.0:" + port);
        new CountDownLatch(1).await();
    }

    private void serveGameControl(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String prefix = "/games/";
        String suffix = rawPath.endsWith("/start") ? "/start"
            : rawPath.endsWith("/stop") ? "/stop" : "";
        if (!rawPath.startsWith(prefix) || suffix.isEmpty()) {
            text(exchange, 404, "未找到试玩控制接口");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            text(exchange, 405, "只允许 POST");
            return;
        }
        if (!constantEquals(token, exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
            text(exchange, 403, "共享试玩启动令牌无效");
            return;
        }
        String encoded = rawPath.substring(prefix.length(), rawPath.length() - suffix.length());
        String directory = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (!safeDirectory(directory)) {
            text(exchange, 400, "游戏目录名无效");
            return;
        }
        try {
            if ("/stop".equals(suffix)) {
                GameRuntime runtime = games.remove(directory);
                if (runtime != null) {
                    synchronized (runtime) {
                        runtime.close();
                    }
                }
                json(exchange, 200, new StartOutcome(true, "STOPPED", null,
                    "Controller removed from shared JVM"));
                return;
            }
            GameRuntime runtime = games.computeIfAbsent(directory, key -> new GameRuntime());
            StartOutcome result;
            synchronized (runtime) {
                if (runtime.ready && runtime.demoUrl != null) {
                    result = new StartOutcome(true, "RUNNING", runtime.demoUrl, "试玩 Controller 已在共享 JVM 中运行");
                } else {
                    result = startGame(directory, runtime);
                }
            }
            json(exchange, result.ok ? 200 : 500, result);
        } catch (IllegalArgumentException error) {
            json(exchange, 400, new StartOutcome(false, "INVALID", null, error.getMessage()));
        } catch (Exception error) {
            System.err.println("共享试玩挂载游戏失败：" + directory);
            error.printStackTrace(System.err);
            json(exchange, 500, new StartOutcome(false, "FAILED", null,
                "共享试玩启动失败：" + error.getClass().getSimpleName() + "：" + concise(error)));
        }
    }

    private StartOutcome startGame(String directory, GameRuntime runtime) throws Exception {
        ControllerDescriptor descriptor = prepareRuntimeConfig(descriptor(directory));
        runtime.close();
        runtime.ready = false;
        runtime.failure = null;
        runtime.directory = directory;
        if (descriptor.builtinController) {
            runtime.freedomDay = CpgameFreedomDayControllerMount.start(root, directory);
            runtime.ready = true;
            runtime.demoUrl = demoUrl(directory, descriptor);
            return new StartOutcome(true, "RUNNING", runtime.demoUrl,
                "旧版试玩已接入同一个共享 Java 进程和同一个 HTTP 端口");
        }
        runtime.loader = controllerClassLoader(descriptor);
        Class<?> mainType = Class.forName(descriptor.mainClass, true, runtime.loader);
        Thread controllerThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(runtime.loader);
            try {
                if (descriptor.bootApplication) {
                    runtime.spring = CpgameSpringControllerMount.start(directory, runtime.loader, mainType,
                        springProperties(descriptor));
                    runtime.ready = true;
                } else {
                    if (descriptor.preloaderMain != null) {
                        Class<?> preloader = Class.forName(descriptor.preloaderMain, true, runtime.loader);
                        preloader.getMethod("main", String[].class).invoke(null,
                            (Object) new String[]{"--config", descriptor.configFile.toString()});
                    }
                    Method main = mainType.getMethod("main", String[].class);
                    String[] mainArgs = legacyArguments(descriptor);
                    CpgameSharedHttpServerProvider.mounting(runtime,
                        () -> main.invoke(null, (Object) mainArgs));
                }
            } catch (InvocationTargetException error) {
                runtime.failure = error.getTargetException();
            } catch (Throwable error) {
                runtime.failure = error;
            }
        }, "cpgame-controller-" + directory);
        controllerThread.setDaemon(false);
        runtime.thread = controllerThread;
        controllerThread.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (runtime.failure != null) throw new IllegalStateException(concise(runtime.failure), runtime.failure);
            if (runtime.ready) {
                runtime.demoUrl = demoUrl(directory, descriptor);
                return new StartOutcome(true, "RUNNING", runtime.demoUrl,
                    "已挂载到同一个共享 Java 进程和同一个 HTTP 端口");
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("等待游戏 Controller 注册共享端口路由超时");
    }

    private String[] legacyArguments(ControllerDescriptor descriptor) {
        List<String> arguments = new ArrayList<>();
        if (descriptor.configFile != null) {
            if (descriptor.configArgument == null || descriptor.configArgument.isBlank()) {
                arguments.add(descriptor.configFile.toString());
            } else {
                arguments.add(descriptor.configArgument);
                arguments.add(descriptor.configFile.toString());
            }
        }
        if (descriptor.portArgument != null && !descriptor.portArgument.isBlank()) {
            arguments.add(descriptor.portArgument);
            arguments.add(Integer.toString(port));
        } else if (descriptor.mainClass.endsWith(".DemoServer")) {
            arguments.add("--port");
            arguments.add(Integer.toString(port));
        }
        if (descriptor.bindArgument != null && !descriptor.bindArgument.isBlank()) {
            arguments.add(descriptor.bindArgument);
            arguments.add(descriptor.bindAddress == null || descriptor.bindAddress.isBlank()
                ? "0.0.0.0" : descriptor.bindAddress);
        }
        if (descriptor.publishArgument != null && !descriptor.publishArgument.isBlank()) {
            arguments.add(descriptor.publishArgument);
            arguments.add(root.resolve("publish").resolve(descriptor.directory).toString());
        } else if (descriptor.mainClass.endsWith(".DemoServer")) {
            arguments.add("--publish");
            arguments.add(root.resolve("publish").resolve(descriptor.directory).toString());
        }
        return arguments.toArray(String[]::new);
    }

    private List<String> springProperties(ControllerDescriptor descriptor) throws IOException {
        List<String> result = new ArrayList<>();
        Path config = descriptor.configFile;
        if (config == null) {
            Path candidate = descriptor.serverDirectory.resolve("dist/application.properties");
            if (Files.isRegularFile(candidate)) config = candidate;
        }
        if (config != null) {
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(config)) { values.load(input); }
            overlayGeneratorRedis(descriptor.directory, values);
            Path base = config.getParent();
            for (String name : values.stringPropertyNames()) {
                String value = values.getProperty(name).strip();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean pathProperty = lower.contains("directory") || lower.endsWith(".root")
                    || lower.endsWith(".file") || lower.endsWith(".path") || lower.endsWith(".basedir");
                if (!value.isBlank() && pathProperty) {
                    try {
                        if (!Path.of(value).isAbsolute()) {
                            value = base.resolve(value).normalize().toAbsolutePath().toString();
                        }
                    } catch (RuntimeException ignored) { }
                }
                result.add("--" + name + "=" + value);
            }
        }
        return result;
    }

    private ControllerDescriptor prepareRuntimeConfig(ControllerDescriptor descriptor) throws IOException {
        if (descriptor.configFile == null || descriptor.bootApplication) return descriptor;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(descriptor.configFile)) { values.load(input); }
        boolean overlay = overlayGeneratorRedis(descriptor.directory, values);
        boolean redisEnabled = Boolean.parseBoolean(values.getProperty("redis.enabled", "false"))
            || values.getProperty("redis.host") != null;
        if (!redisEnabled && !overlay) return descriptor;
        String redisHost = values.getProperty("redis.host", "127.0.0.1").strip();
        int configuredPort = Integer.parseInt(values.getProperty("redis.port", "6379").strip());
        boolean hostChanged = false;
        if (!portOpen(redisHost, configuredPort)) {
            if (!isLoopback(redisHost) || !portOpen("127.0.0.1", 6379)) {
                throw new IllegalStateException("Controller 依赖的 Redis 不可用：" + redisHost + ":" + configuredPort);
            }
            values.setProperty("redis.host", "127.0.0.1");
            values.setProperty("redis.port", "6379");
            hostChanged = true;
        }
        if (!overlay && !hostChanged) return descriptor;
        // Sharing Redis settings must not launch an embedded generator with Controller settings.
        // The demo consumes existing rounds; generation is started explicitly through runRedis.
        String preloader = descriptor.preloaderMain;
        absolutizeProperty(values, "publish.root", descriptor.configFile.getParent());
        Path runtimeConfig = root.resolve("reports/_shared-demo/runtime/config")
            .resolve(descriptor.directory + ".properties");
        Files.createDirectories(runtimeConfig.getParent());
        try (OutputStream output = Files.newOutputStream(runtimeConfig)) {
            values.store(output, "共享试玩运行时配置；复用 generator Redis，不修改游戏正式配置");
        }
        return new ControllerDescriptor(descriptor.directory, descriptor.serverDirectory, descriptor.jar,
            descriptor.mainClass, descriptor.bootApplication, descriptor.apiPort, runtimeConfig,
            descriptor.configArgument, descriptor.originalDemo, descriptor.demoMode, preloader,
            descriptor.builtinController, descriptor.portArgument, descriptor.bindArgument,
            descriptor.bindAddress, descriptor.publishArgument);
    }

    private boolean overlayGeneratorRedis(String directory, Properties values) {
        Path dist = root.resolve("generator").resolve(directory).resolve("dist");
        for (String name : List.of("generator.properties", "loader.properties")) {
            Path file = dist.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            Properties generator = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                generator.load(input);
            } catch (IOException ignored) {
                continue;
            }
            boolean changed = false;
            changed |= copyProperty(generator, values, "redis.host");
            changed |= copyProperty(generator, values, "redis.port");
            changed |= copyProperty(generator, values, "redis.database");
            changed |= copyProperty(generator, values, "redis.password");
            for (String key : List.of("redis.username", "redis.ssl", "redis.connect-timeout-ms",
                    "redis.socket-timeout-ms", "redis.game-id")) {
                changed |= copyProperty(generator, values, key);
            }
            return changed;
        }
        return false;
    }

    private boolean copyProperty(Properties source, Properties target, String key) {
        String value = source.getProperty(key);
        if (value == null) return false;
        String current = target.getProperty(key);
        if (value.equals(current)) return false;
        target.setProperty(key, value);
        return true;
    }

    private void absolutizeProperty(Properties values, String key, Path base) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) return;
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) values.setProperty(key, base.resolve(path).normalize().toAbsolutePath().toString());
        } catch (RuntimeException ignored) { }
    }

    private String inferPreloaderMain(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.stream().map(ZipEntry::getName)
                .filter(name -> name.endsWith("LoaderMain.class") && !name.contains("$")
                    && !name.startsWith("BOOT-INF/"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .findFirst().orElse(null);
        }
    }

    private ControllerDescriptor descriptor(String directory) throws Exception {
        Path serverDirectory = root.resolve("server-api").resolve(directory).normalize();
        if (!serverDirectory.startsWith(root.resolve("server-api")) || !Files.isDirectory(serverDirectory)) {
            throw new IllegalArgumentException("未找到游戏 server-api：" + directory);
        }
        Path descriptorFile = serverDirectory.resolve("dist/demo-controller.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(descriptorFile)) {
            try (InputStream input = Files.newInputStream(descriptorFile)) {
                properties.load(input);
            }
        }
        URI originalDemo = demoUri(serverDirectory);
        if (CpgameFreedomDayControllerMount.supports(root, directory)) {
            return new ControllerDescriptor(directory, serverDirectory, null, null, false,
                apiPort(originalDemo), null, "", originalDemo, "publish", null, true,
                "", "", "", "");
        }
        Path jar = controllerJar(serverDirectory, properties.getProperty("controller.jar"));
        Manifest manifest;
        try (JarFile archive = new JarFile(jar.toFile())) {
            manifest = archive.getManifest();
        }
        String manifestMain = attribute(manifest, "Main-Class");
        String startClass = attribute(manifest, "Start-Class");
        String mainClass = value(properties, "controller.main-class",
            startClass == null || startClass.isBlank() ? manifestMain : startClass);
        if (mainClass == null || mainClass.isBlank()) {
            // 兼容旧工作流产物：旧 JAR 经常遗漏 Main-Class，但 Controller 类本身完整存在。
            // 新工作流仍由产物硬校验强制写 demo-controller.properties，不依赖此推断。
            mainClass = inferLegacyMainClass(jar);
        }
        boolean boot = startClass != null && !startClass.isBlank()
            && (manifestMain == null || BOOT_LAUNCHERS.contains(manifestMain));
        int inferredPort = apiPort(originalDemo);
        int apiPort = integer(properties, "controller.api-port", inferredPort);
        Path configFile = optionalPath(serverDirectory, properties.getProperty("controller.config"));
        String configArgument = properties.getProperty("controller.config-argument", "").strip();
        if (configFile == null) {
            Path legacyConfig = serverDirectory.resolve("dist/server.properties");
            if (Files.isRegularFile(legacyConfig)) {
                configFile = legacyConfig;
                // 多数旧版纯 JDK Controller 的启动约定是 --config <文件>；显式描述文件
                // 可以把该参数留空，兼容仅接收单个路径参数的少数游戏。
                configArgument = "--config";
            }
        }
        if (configFile == null && !boot) {
            Path legacyApplication = serverDirectory.resolve("dist/application.properties");
            if (Files.isRegularFile(legacyApplication)) {
                configFile = legacyApplication;
                configArgument = "";
            }
        }
        String mode = value(properties, "demo.mode",
            originalDemo.getPort() == apiPort ? "controller" : "publish").toLowerCase(Locale.ROOT);
        if (!("managed-process".equals(mode) || "controller".equals(mode)
            || "publish".equals(mode) || "shared-http".equals(mode))) {
            throw new IllegalArgumentException("demo.mode 只能是 managed-process、controller、publish 或 shared-http");
        }
        String portArgument = properties.getProperty("controller.port-argument", "").strip();
        String bindArgument = properties.getProperty("controller.bind-argument", "").strip();
        String bindAddress = properties.getProperty("controller.bind-address", "0.0.0.0").strip();
        String publishArgument = properties.getProperty("controller.publish-argument", "").strip();
        return new ControllerDescriptor(directory, serverDirectory, jar, mainClass, boot, apiPort,
            configFile, configArgument, originalDemo, mode, null, false, portArgument,
            bindArgument, bindAddress, publishArgument);
    }

    private Path controllerJar(Path serverDirectory, String configured) throws IOException {
        if (configured != null && !configured.isBlank()) {
            Path result = resolveUnder(serverDirectory, configured);
            if (!Files.isRegularFile(result)) throw new IllegalArgumentException("Controller JAR 不存在：" + result);
            return result;
        }
        List<Path> candidates = new ArrayList<>();
        for (Path location : List.of(serverDirectory.resolve("dist"), serverDirectory.resolve("target"))) {
            if (!Files.isDirectory(location)) continue;
            try (var files = Files.list(location)) {
                files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).contains("generator"))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).contains("sources"))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).contains("original"))
                    .forEach(candidates::add);
            }
        }
        return candidates.stream().max(Comparator
            .comparing((Path path) -> preferredServerJar(path.getFileName().toString()))
            .thenComparingLong(this::size)).orElseThrow(() ->
            new IllegalArgumentException("server-api/dist 缺少可由共享 JVM 加载的 Controller JAR"));
    }

    private String inferLegacyMainClass(Path jar) throws IOException {
        List<String> candidates = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            archive.stream().map(ZipEntry::getName)
                .filter(name -> name.endsWith(".class") && !name.contains("$")
                    && !name.startsWith("META-INF/") && !name.startsWith("BOOT-INF/"))
                .filter(name -> name.endsWith("ApiServerMain.class")
                    || name.endsWith("ServerMain.class") || name.endsWith("Application.class"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .forEach(candidates::add);
        }
        return candidates.stream().min(Comparator
            .comparingInt(CpgameSharedDemoHostMain::legacyMainRank)
            .thenComparing(String::length)
            .thenComparing(String::compareTo))
            .orElseThrow(() -> new IllegalArgumentException(
                "Controller JAR 缺少 main class，且无法从旧产物推断：" + jar));
    }

    private static int legacyMainRank(String className) {
        if (className.endsWith("ApiServerMain")) return 0;
        if (className.endsWith("ServerMain")) return 1;
        if (className.toLowerCase(Locale.ROOT).contains(".api.")) return 2;
        return 3;
    }

    private URLClassLoader controllerClassLoader(ControllerDescriptor descriptor) throws Exception {
        List<URL> urls = new ArrayList<>();
        if (descriptor.bootApplication) {
            Path cache = root.resolve("reports/_shared-demo/cache")
                .resolve(descriptor.directory + "-" + size(descriptor.jar) + "-" + modified(descriptor.jar));
            Path marker = cache.resolve(".ready");
            if (!Files.isRegularFile(marker)) {
                Path staging = cache.resolveSibling(cache.getFileName() + ".tmp-" + System.nanoTime());
                unpackBootJar(descriptor.jar, staging);
                Files.createDirectories(cache.getParent());
                if (!Files.exists(cache)) Files.move(staging, cache, StandardCopyOption.ATOMIC_MOVE);
                Files.writeString(marker, "ready", StandardCharsets.UTF_8);
            }
            urls.add(cache.resolve("classes").toUri().toURL());
            Path lib = cache.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var files = Files.list(lib)) {
                    files.filter(Files::isRegularFile).sorted().forEach(path -> addUrl(urls, path));
                }
            }
        } else {
            urls.add(descriptor.jar.toUri().toURL());
            try (var files = Files.list(descriptor.jar.getParent())) {
                files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> !path.equals(descriptor.jar)).sorted()
                    .forEach(path -> addUrl(urls, path));
            }
            for (Path lib : List.of(descriptor.jar.getParent().resolve("lib"),
                descriptor.serverDirectory.resolve("target/dependency"))) {
                if (!Files.isDirectory(lib)) continue;
                try (var files = Files.list(lib)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted().forEach(path -> addUrl(urls, path));
                }
            }
        }
        // null parent keeps game dependencies isolated while bootstrap/JDK classes remain visible.
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private void unpackBootJar(Path jar, Path staging) throws IOException {
        Files.createDirectories(staging.resolve("classes"));
        Files.createDirectories(staging.resolve("lib"));
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                Path output = null;
                if (name.startsWith("BOOT-INF/classes/")) {
                    output = staging.resolve("classes").resolve(name.substring("BOOT-INF/classes/".length()));
                } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    output = staging.resolve("lib").resolve(Path.of(name).getFileName().toString());
                }
                if (output == null) continue;
                Path normalized = output.normalize();
                if (!normalized.startsWith(staging)) throw new IOException("JAR 包含非法路径");
                Files.createDirectories(normalized.getParent());
                Files.copy(input, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void servePublishedFile(HttpExchange exchange) throws IOException {
        String remainder = exchange.getRequestURI().getRawPath().substring("/play/".length());
        int separator = remainder.indexOf('/');
        if (separator < 0) {
            text(exchange, 404, "缺少游戏资源路径");
            return;
        }
        String directory = URLDecoder.decode(remainder.substring(0, separator), StandardCharsets.UTF_8);
        if (!safeDirectory(directory)) {
            text(exchange, 400, "游戏目录名无效");
            return;
        }
        String relative = URLDecoder.decode(remainder.substring(separator + 1), StandardCharsets.UTF_8);
        if (("GET".equalsIgnoreCase(exchange.getRequestMethod())
            || "HEAD".equalsIgnoreCase(exchange.getRequestMethod()))
            && serveStaticFile(exchange, directory, relative)) {
            return;
        }
        GameRuntime runtime = games.get(directory);
        if (runtime == null || !runtime.ready) {
            text(exchange, 404, "试玩资源或 Controller 路由不存在");
            return;
        }
        String controllerPath = relative.isBlank() ? "/" : "/" + relative;
        runtime.dispatch(new RebasedExchange(exchange, controllerPath));
    }

    private void serveSharedRequest(HttpExchange exchange) throws IOException {
        String directory = directoryFromReferer(exchange.getRequestHeaders().getFirst("Referer"));
        if (directory == null || !games.containsKey(directory)) {
            if (games.size() == 1) directory = games.keySet().iterator().next();
        }
        if (directory == null) {
            text(exchange, 404, "请从 /play/游戏目录/ 访问共享试玩路由");
            return;
        }
        String relative = exchange.getRequestURI().getPath();
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (("GET".equalsIgnoreCase(exchange.getRequestMethod())
            || "HEAD".equalsIgnoreCase(exchange.getRequestMethod()))
            && serveStaticFile(exchange, directory, relative)) {
            return;
        }
        GameRuntime runtime = games.get(directory);
        if (runtime == null || !runtime.ready) {
            text(exchange, 503, "当前游戏 Controller 尚未挂载");
            return;
        }
        runtime.dispatch(exchange);
    }

    private String directoryFromReferer(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            String rawPath = URI.create(referer).getRawPath();
            String prefix = "/play/";
            if (rawPath == null || !rawPath.startsWith(prefix)) return null;
            String remainder = rawPath.substring(prefix.length());
            int separator = remainder.indexOf('/');
            if (separator < 0) return null;
            String directory = URLDecoder.decode(remainder.substring(0, separator), StandardCharsets.UTF_8);
            return safeDirectory(directory) ? directory : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean serveStaticFile(HttpExchange exchange, String directory, String relative) throws IOException {
        String resource = relative;
        if (resource.isBlank() || resource.endsWith("/")) resource += "index.html";
        Path publishRoot = root.resolve("publish").resolve(directory).normalize();
        Path file = publishRoot.resolve(resource.replace('/', java.io.File.separatorChar)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) return false;
        if (CpgameFreedomDayControllerMount.DIRECTORY.equals(directory)
            && "assets/main/index.57fd2.js".equals(relative)) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String onlineLogin = "app.ClientConfigManager().StartRouter(),\n"
                + "                this.LoginPushGs(),\n"
                + "                this.LoginGoGs(),\n"
                + "                app.GoLoginManager().LoginGoGs()";
            byte[] body = source.replace(onlineLogin, "app.ClientConfigManager().StartRouter()")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200,
                "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : body.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
            } else {
                exchange.close();
            }
            return true;
        }
        long length = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", mediaType(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (OutputStream output = exchange.getResponseBody()) {
                Files.copy(file, output);
            }
        } else {
            exchange.close();
        }
        return true;
    }

    private String demoUrl(String directory, ControllerDescriptor descriptor) {
        String query = sharedQuery(descriptor.originalDemo.getRawQuery());
        return "http://127.0.0.1:" + port + "/play/" + url(directory) + "/index.html"
            + (query == null || query.isBlank() ? "" : "?" + query);
    }

    private String sharedQuery(String query) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        if (query != null) {
            for (String field : query.split("&")) {
                int equals = field.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? field : field.substring(0, equals), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(equals < 0 ? "" : field.substring(equals + 1), StandardCharsets.UTF_8);
                fields.put(name, value);
            }
        }
        fields.put("sip", "127.0.0.1:" + port);
        fields.put("apiHost", "127.0.0.1:" + port);
        if (fields.containsKey("apiPort")) fields.put("apiPort", Integer.toString(port));
        return fields.entrySet().stream().map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private URI demoUri(Path serverDirectory) throws IOException {
        Path file = serverDirectory.resolve("demo-url.txt");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("server-api 缺少 demo-url.txt");
        String raw = CpgameLabService.firstNonBlankLine(Files.readString(file, StandardCharsets.UTF_8));
        URI uri;
        try {
            uri = raw == null ? null : URI.create(raw);
        } catch (RuntimeException ignored) {
            uri = null;
        }
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getPort() < 1) {
            throw new IllegalArgumentException("demo-url.txt 不是有效的本机 HTTP 地址");
        }
        return uri;
    }

    private int apiPort(URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String field : query.split("&")) {
                int equals = field.indexOf('=');
                if (equals < 0 || !"sip".equalsIgnoreCase(URLDecoder.decode(field.substring(0, equals), StandardCharsets.UTF_8))) continue;
                String sip = URLDecoder.decode(field.substring(equals + 1), StandardCharsets.UTF_8);
                int colon = sip.lastIndexOf(':');
                if (colon >= 0) {
                    try { return Integer.parseInt(sip.substring(colon + 1)); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        return uri.getPort();
    }

    private boolean portOpen(int targetPort) {
        return portOpen("127.0.0.1", targetPort);
    }

    private boolean portOpen(String host, int targetPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, targetPort), 300);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private Path optionalPath(Path base, String value) {
        if (value == null || value.isBlank()) return null;
        Path result = resolveUnder(base, value);
        if (!Files.isRegularFile(result)) throw new IllegalArgumentException("Controller 配置不存在：" + result);
        return result;
    }

    private Path resolveUnder(Path base, String value) {
        Path result = base.resolve(value.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(base)) throw new IllegalArgumentException("Controller 配置路径超出当前游戏目录");
        return result;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--") || !argument.contains("=")) continue;
            int equals = argument.indexOf('=');
            values.put(argument.substring(2, equals), argument.substring(equals + 1));
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数 --" + key);
        return value;
    }

    private static String attribute(Manifest manifest, String name) {
        return manifest == null ? null : manifest.getMainAttributes().getValue(name);
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int integer(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        int result = value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
        if (result < 1 || result > 65535) throw new IllegalArgumentException(key + " 超出端口范围");
        return result;
    }

    private static boolean safeDirectory(String value) {
        return value != null && SAFE_DIRECTORY.matcher(value).matches()
            && !value.equals(".") && !value.equals("..");
    }

    private static boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length == 0 || right.length == 0) return false;
        int difference = left.length ^ right.length;
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            difference |= left[index % left.length] ^ right[index % right.length];
        }
        return difference == 0;
    }

    private static void addUrl(List<URL> urls, Path path) {
        try { urls.add(path.toUri().toURL()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static int preferredServerJar(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("server") || normalized.contains("api") || normalized.contains("demo") ? 1 : 0;
    }

    private long size(Path path) {
        try { return Files.size(path); }
        catch (IOException ignored) { return 0L; }
    }

    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static void text(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void json(HttpExchange exchange, int status, StartOutcome outcome) throws IOException {
        String value = "{\"ok\":" + outcome.ok + ",\"state\":\"" + escape(outcome.state)
            + "\",\"demoUrl\":" + (outcome.demoUrl == null ? "null" : "\"" + escape(outcome.demoUrl) + "\"")
            + ",\"message\":\"" + escape(outcome.message) + "\"}";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class RebasedExchange extends HttpExchange {
        private final HttpExchange delegate;
        private final URI uri;

        private RebasedExchange(HttpExchange delegate, String path) {
            this.delegate = delegate;
            String query = delegate.getRequestURI().getRawQuery();
            this.uri = URI.create(path.replace(" ", "%20")
                + (query == null || query.isBlank() ? "" : "?" + query));
        }

        @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
        @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
        @Override public HttpContext getHttpContext() { return delegate.getHttpContext(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getRequestBody() { return delegate.getRequestBody(); }
        @Override public OutputStream getResponseBody() { return delegate.getResponseBody(); }
        @Override public void sendResponseHeaders(int code, long length) throws IOException {
            delegate.sendResponseHeaders(code, length);
        }
        @Override public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
        @Override public int getResponseCode() { return delegate.getResponseCode(); }
        @Override public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public String getProtocol() { return delegate.getProtocol(); }
        @Override public Object getAttribute(String name) { return delegate.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { delegate.setStreams(input, output); }
        @Override public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
    }

    private record ControllerDescriptor(String directory, Path serverDirectory, Path jar,
                                         String mainClass, boolean bootApplication, int apiPort,
                                         Path configFile, String configArgument, URI originalDemo,
                                         String demoMode, String preloaderMain,
                                         boolean builtinController, String portArgument,
                                         String bindArgument, String bindAddress,
                                         String publishArgument) { }

    private record StartOutcome(boolean ok, String state, String demoUrl, String message) { }
    record LaunchCapability(boolean launchable, String message) { }

    private static final class GameRuntime implements CpgameSharedHttpServerProvider.MountTarget {
        private volatile String directory;
        private volatile boolean ready;
        private volatile String demoUrl;
        private volatile Throwable failure;
        private volatile Thread thread;
        private volatile URLClassLoader loader;
        private volatile CpgameSharedHttpServerProvider.VirtualHttpServer virtualServer;
        private volatile CpgameSpringControllerMount spring;
        private volatile CpgameFreedomDayControllerMount freedomDay;

        @Override public void mounted(CpgameSharedHttpServerProvider.VirtualHttpServer server) {
            virtualServer = server;
            ready = true;
        }

        @Override public void unmounted(CpgameSharedHttpServerProvider.VirtualHttpServer server) {
            if (virtualServer == server) {
                virtualServer = null;
                ready = false;
            }
        }

        private void dispatch(HttpExchange exchange) throws IOException {
            CpgameSharedHttpServerProvider.VirtualHttpServer virtual = virtualServer;
            if (virtual != null) {
                virtual.dispatch(exchange);
                return;
            }
            CpgameSpringControllerMount mountedSpring = spring;
            if (mountedSpring != null) {
                mountedSpring.dispatch(exchange);
                return;
            }
            CpgameFreedomDayControllerMount mountedFreedomDay = freedomDay;
            if (mountedFreedomDay != null) {
                mountedFreedomDay.dispatch(exchange);
                return;
            }
            text(exchange, 503, "当前游戏 Controller 尚未完成挂载");
        }

        private void close() {
            ready = false;
            demoUrl = null;
            CpgameSharedHttpServerProvider.VirtualHttpServer virtual = virtualServer;
            virtualServer = null;
            if (virtual != null) virtual.stop(0);
            CpgameSpringControllerMount mountedSpring = spring;
            spring = null;
            if (mountedSpring != null) mountedSpring.close();
            CpgameFreedomDayControllerMount mountedFreedomDay = freedomDay;
            freedomDay = null;
            if (mountedFreedomDay != null) mountedFreedomDay.close();
            if (thread != null) thread.interrupt();
            thread = null;
            if (loader != null) {
                try { loader.close(); }
                catch (IOException ignored) { }
            }
            loader = null;
        }
    }
}
