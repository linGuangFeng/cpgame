package com.cpgame.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Starts one isolated Java process per playable game. Ports are allocated from 50000-59999 and
 * at most ten game processes are retained; starting the eleventh evicts the oldest process.
 */
public class CpgameDemoRuntimeService {
    private static final Pattern SAFE_DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final String HOST_MAIN = CpgameSharedDemoHostMain.class.getName();
    private static final int FIRST_PORT = 50_000;
    private static final int LAST_PORT = 59_999;
    private static final int MAX_PROCESSES = 10;

    private final Path root;
    private final Path runtimeRoot;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Object monitor = new Object();
    /** Insertion order is the required oldest-started eviction order. */
    private final LinkedHashMap<String, ManagedGame> running = new LinkedHashMap<>();
    private int nextPort = FIRST_PORT;

    public CpgameDemoRuntimeService(AdminSettings settings, ObjectMapper mapper) {
        this.root = settings.getRoot();
        this.runtimeRoot = settings.runtimeRoot().resolve("game-processes");
        this.mapper = mapper;
        recoverState();
    }

    public boolean isHostRunning() {
        synchronized (monitor) {
            refreshDeadProcesses();
            return !running.isEmpty();
        }
    }

    public int runningCount() {
        synchronized (monitor) {
            refreshDeadProcesses();
            return running.size();
        }
    }

    public Map<String, ProcessInfo> runningProcesses() {
        synchronized (monitor) {
            refreshDeadProcesses();
            LinkedHashMap<String, ProcessInfo> result = new LinkedHashMap<>();
            running.forEach((directory, game) -> result.put(directory, game.info()));
            return result;
        }
    }

    public LaunchCapability launchCapability(String directoryName) {
        CpgameSharedDemoHostMain.LaunchCapability capability = CpgameSharedDemoHostMain.inspect(root, directoryName);
        return new LaunchCapability(capability.launchable(), capability.launchable()
            ? "可启动独立试玩进程" : capability.message());
    }

    /**
     * Platform-owned acceptance smoke test. A report saying PASS is not evidence that the game
     * center can actually launch the delivered controller. Start through the exact production
     * path used by the UI and require the mounted page to answer successfully. Preserve a game
     * which the user already had running; otherwise clean up the temporary validation process.
     */
    public List<String> acceptanceLaunchProblems(String directoryName) {
        List<String> problems = new ArrayList<>();
        boolean alreadyRunning = mountedDemoUrl(directoryName) != null;
        try {
            LaunchCapability capability = launchCapability(directoryName);
            if (!capability.launchable()) {
                return List.of("游戏中心无法启动试玩模块：" + capability.message());
            }
            StartResult started = startGame(directoryName);
            if (!started.ok() || started.demoUrl() == null || started.demoUrl().isBlank()) {
                problems.add("游戏中心启动试玩失败：" + started.message());
                return List.copyOf(problems);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(started.demoUrl()))
                .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                problems.add("游戏中心试玩首页返回 HTTP " + response.statusCode());
            } else if (response.body() == null || response.body().length == 0) {
                problems.add("游戏中心试玩首页返回空内容");
            } else {
                problems.addAll(CpgameOriginalPagePlaythrough.probe(started.demoUrl(),
                    capabilitiesFile(directoryName)));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            problems.add("游戏中心试玩启动校验被中断");
        } catch (RuntimeException | IOException error) {
            problems.add("游戏中心试玩启动失败：" + concise(error));
        } finally {
            if (!alreadyRunning) {
                try { stopGame(directoryName); } catch (RuntimeException ignored) { }
            }
        }
        return List.copyOf(problems);
    }

    private Path capabilitiesFile(String directoryName) {
        Path file = root.resolve("protocol").resolve(directoryName).resolve("game-capabilities.json");
        return Files.isRegularFile(file) ? file : null;
    }

    public StartResult startGame(String directoryName) {
        requireSafeDirectory(directoryName);
        LaunchCapability capability = launchCapability(directoryName);
        if (!capability.launchable()) {
            return new StartResult(false, "UNAVAILABLE", null, capability.message(), 0L, 0, null);
        }
        synchronized (monitor) {
            refreshDeadProcesses();
            ManagedGame existing = running.get(directoryName);
            if (existing != null) {
                return new StartResult(true, "RUNNING", existing.demoUrl,
                    "试玩进程已在运行", existing.pid, existing.port, null);
            }

            String evictedDirectory = null;
            if (running.size() >= MAX_PROCESSES) {
                evictedDirectory = running.keySet().iterator().next();
                stopManaged(evictedDirectory, running.remove(evictedDirectory));
            }

            int port = allocatePort();
            String token = UUID.randomUUID().toString();
            Process process = null;
            try {
                Path runtime = runtimeDirectory();
                Files.createDirectories(runtime);
                List<String> command = launchCommand(token, port);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(root.toFile());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                String logPrefix = safeLogName(directoryName) + "-" + System.currentTimeMillis();
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    runtime.resolve(logPrefix + ".stdout.log").toFile()));
                builder.redirectError(ProcessBuilder.Redirect.appendTo(
                    runtime.resolve(logPrefix + ".stderr.log").toFile()));
                process = builder.start();
                waitUntilHealthy(process, port);
                StartOutcome outcome = invokeStart(directoryName, port, token);
                if (!outcome.ok || outcome.demoUrl == null || outcome.demoUrl.isBlank()) {
                    throw new IllegalStateException(outcome.message == null
                        ? "游戏 JAR 启动失败" : outcome.message);
                }
                ManagedGame managed = new ManagedGame(directoryName, process.pid(), port, token,
                    Instant.now(), outcome.demoUrl);
                running.put(directoryName, managed);
                nextPort = port == LAST_PORT ? FIRST_PORT : port + 1;
                saveState();
                String message = evictedDirectory == null ? "游戏 JAR 已启动"
                    : "游戏 JAR 已启动；已关闭最早启动的 " + evictedDirectory;
                return new StartResult(true, "RUNNING", outcome.demoUrl, message,
                    managed.pid, managed.port, evictedDirectory);
            } catch (IOException error) {
                stopProcess(process);
                throw new IllegalStateException("无法启动游戏 Java 进程：" + concise(error), error);
            } catch (RuntimeException error) {
                stopProcess(process);
                throw error;
            }
        }
    }

    public StopResult stopGame(String directoryName) {
        requireSafeDirectory(directoryName);
        synchronized (monitor) {
            refreshDeadProcesses();
            ManagedGame game = running.remove(directoryName);
            if (game == null) return new StopResult(true, "STOPPED", "游戏未运行", 0L, 0, running.size());
            stopManaged(directoryName, game);
            saveState();
            return new StopResult(true, "STOPPED", "游戏进程已关闭", game.pid, game.port, running.size());
        }
    }

    public StopAllResult stopAll() {
        synchronized (monitor) {
            refreshDeadProcesses();
            List<Map.Entry<String, ManagedGame>> snapshot = new ArrayList<>(running.entrySet());
            running.clear();
            snapshot.forEach(entry -> stopManaged(entry.getKey(), entry.getValue()));
            saveState();
            return new StopAllResult(true, snapshot.size(), "已关闭全部试玩进程");
        }
    }

    public String mountedDemoUrl(String directoryName) {
        synchronized (monitor) {
            ManagedGame game = running.get(directoryName);
            if (game == null) return null;
            if (!ProcessHandle.of(game.pid).map(ProcessHandle::isAlive).orElse(false)) {
                running.remove(directoryName);
                saveState();
                return null;
            }
            return game.demoUrl;
        }
    }

    private void stopManaged(String directoryName, ManagedGame game) {
        try {
            HttpRequest request = HttpRequest.newBuilder(controlUri(game.port, directoryName, "stop"))
                .timeout(Duration.ofSeconds(3))
                .header(CpgameSharedDemoHostMain.TOKEN_HEADER, game.token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException ignored) {
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        stopProcess(ProcessHandle.of(game.pid).orElse(null));
    }

    private void stopProcess(Process process) {
        if (process != null) stopProcess(process.toHandle());
    }

    private void stopProcess(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) return;
        handle.destroy();
        try {
            handle.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            if (handle.isAlive()) handle.destroyForcibly();
        }
    }

    private int allocatePort() {
        int candidate = nextPort;
        for (int attempts = 0; attempts <= LAST_PORT - FIRST_PORT; attempts++) {
            final int port = candidate;
            if (!running.values().stream().anyMatch(game -> game.port == port) && portAvailable(port)) {
                return port;
            }
            candidate = candidate == LAST_PORT ? FIRST_PORT : candidate + 1;
        }
        throw new IllegalStateException("50000-59999 没有可用试玩端口");
    }

    private boolean portAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private List<String> launchCommand(String launchToken, int port) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("--add-exports=jdk.httpserver/sun.net.httpserver=ALL-UNNAMED");
        command.add("-D" + CpgameSharedHttpServerProvider.PROVIDER_PROPERTY + "="
            + CpgameSharedHttpServerProvider.class.getName());
        command.add("-cp");
        command.add(hostClassPath());
        command.add(HOST_MAIN);
        command.add("--root=" + root);
        command.add("--port=" + port);
        command.add("--token=" + launchToken);
        return command;
    }

    private String hostClassPath() {
        try {
            URI location = CpgameSharedDemoHostMain.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize().toString();
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
        }
        String separator = System.getProperty("path.separator");
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return Arrays.stream(System.getProperty("java.class.path", "").split(Pattern.quote(separator)))
            .filter(part -> !part.isBlank())
            .map(part -> {
                Path path = Path.of(part);
                return (path.isAbsolute() ? path : cwd.resolve(path)).toAbsolutePath().normalize().toString();
            })
            .collect(Collectors.joining(separator));
    }

    private Path writeProviderBootstrapJar() throws IOException {
        Path runtime = runtimeDirectory();
        Files.createDirectories(runtime);
        Path target = runtime.resolve("http-provider.jar");
        if (Files.isRegularFile(target)) return target.toAbsolutePath().normalize();

        Path temporary = runtime.resolve("http-provider.jar.tmp-" + UUID.randomUUID());
        try {
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                writeBootstrapClass(output, CpgameSharedHttpServerProvider.class);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another launch completed the same stable bootstrap first.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target.toAbsolutePath().normalize();
    }

    private void writeBootstrapClass(JarOutputStream output, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resource));
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("HTTP Provider 类资源不存在：" + resource);
            input.transferTo(output);
        }
        output.closeEntry();
        for (Class<?> nested : type.getDeclaredClasses()) writeBootstrapClass(output, nested);
    }

    private void waitUntilHealthy(Process process, int port) {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("游戏 Java 进程启动后立即退出，退出码 " + process.exitValue());
            }
            if (health(port)) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待游戏 Java 进程时被中断", error);
            }
        }
        throw new IllegalStateException("游戏 Java 进程未在 25 秒内完成 HTTP 就绪");
    }

    private StartOutcome invokeStart(String directoryName, int port, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(controlUri(port, directoryName, "start"))
                .timeout(Duration.ofSeconds(55))
                .header(CpgameSharedDemoHostMain.TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = mapper.readTree(response.body());
            boolean ok = response.statusCode() == 200 && body.path("ok").asBoolean(false);
            String demoUrl = body.path("demoUrl").isTextual() ? body.path("demoUrl").asText() : null;
            return new StartOutcome(ok, demoUrl,
                body.path("message").asText(ok ? "游戏已启动" : "游戏启动失败"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待游戏启动时被中断", error);
        } catch (IOException error) {
            throw new IllegalStateException("无法连接游戏 Java 进程：" + concise(error), error);
        }
    }

    private boolean health(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofMillis(700)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private URI controlUri(int port, String directoryName, String action) {
        String encoded = java.net.URLEncoder.encode(directoryName, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("http://127.0.0.1:" + port + "/games/" + encoded + "/" + action);
    }

    private void refreshDeadProcesses() {
        boolean changed = running.entrySet().removeIf(entry ->
            !ProcessHandle.of(entry.getValue().pid).map(ProcessHandle::isAlive).orElse(false));
        if (changed) saveState();
    }

    private void recoverState() {
        synchronized (monitor) {
            Path state = stateFile();
            if (!Files.isRegularFile(state)) return;
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(state)) {
                values.load(input);
            } catch (IOException ignored) {
                return;
            }
            values.stringPropertyNames().stream().sorted(Comparator.comparing(name -> {
                String[] fields = values.getProperty(name, "").split("\\|", 6);
                try { return Instant.ofEpochMilli(Long.parseLong(fields[3])); }
                catch (RuntimeException ignored) { return Instant.EPOCH; }
            })).forEach(directory -> {
                try {
                    requireSafeDirectory(directory);
                    String[] fields = values.getProperty(directory, "").split("\\|", 6);
                    long pid = Long.parseLong(fields[0]);
                    int port = Integer.parseInt(fields[1]);
                    String token = fields[2];
                    Instant startedAt = Instant.ofEpochMilli(Long.parseLong(fields[3]));
                    String demoUrl = new String(java.util.Base64.getUrlDecoder().decode(fields[4]), StandardCharsets.UTF_8);
                    if (port < FIRST_PORT || port > LAST_PORT || !health(port)
                        || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return;
                    StartOutcome outcome = invokeStart(directory, port, token);
                    if (!outcome.ok) return;
                    running.put(directory, new ManagedGame(directory, pid, port, token, startedAt,
                        outcome.demoUrl == null ? demoUrl : outcome.demoUrl));
                    nextPort = port == LAST_PORT ? FIRST_PORT : port + 1;
                } catch (RuntimeException ignored) { }
            });
            refreshDeadProcesses();
            saveState();
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(runtimeDirectory());
            Properties values = new Properties();
            running.forEach((directory, game) -> values.setProperty(directory,
                game.pid + "|" + game.port + "|" + game.token + "|" + game.startedAt.toEpochMilli()
                    + "|" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(game.demoUrl.getBytes(StandardCharsets.UTF_8))));
            try (OutputStream output = Files.newOutputStream(stateFile(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                values.store(output, "CPGame managed game processes; contains local control tokens");
            }
        } catch (IOException ignored) { }
    }

    private Path runtimeDirectory() {
        return runtimeRoot;
    }

    private Path stateFile() {
        return runtimeDirectory().resolve("game-processes.properties");
    }

    private void requireSafeDirectory(String value) {
        if (value == null || !SAFE_DIRECTORY.matcher(value).matches()
            || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("游戏目录名无效");
        }
    }

    private String safeLogName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String concise(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record ManagedGame(String directory, long pid, int port, String token,
                               Instant startedAt, String demoUrl) {
        ProcessInfo info() { return new ProcessInfo(directory, pid, port, startedAt, demoUrl); }
    }

    private record StartOutcome(boolean ok, String demoUrl, String message) { }

    public record ProcessInfo(String directoryName, long processId, int port,
                              Instant startedAt, String demoUrl) { }
    public record StartResult(boolean ok, String state, String demoUrl, String message,
                              long processId, int port, String evictedDirectory) { }
    public record StopResult(boolean ok, String state, String message, long processId,
                             int port, int remainingCount) { }
    public record StopAllResult(boolean ok, int stoppedCount, String message) { }
    public record LaunchCapability(boolean launchable, String message) { }
}
