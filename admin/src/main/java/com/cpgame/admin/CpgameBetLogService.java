package com.cpgame.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs each game's generator/dist Redis Loader so BetLog/MaryLog keys can be filled,
 * inspected, interrupted and reconfigured from the game lab.
 */
public class CpgameBetLogService {
    private static final Pattern SAFE_DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final Pattern BATCH_LINE = Pattern.compile("BATCH_COMMITTED|批次已提交", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETE_LINE = Pattern.compile("LOAD_COMPLETE\\b|生成完成|加载完成", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRASH_LINE = Pattern.compile(
        "Exception in thread|Unable to access jarfile", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATCH_NUM = Pattern.compile("\\bbatch=(\\d+)");
    private static final Pattern BATCHES_NUM = Pattern.compile("\\bbatches=(\\d+)|批次=(\\d+)");
    private static final Pattern MEMBERS_NUM = Pattern.compile("\\bmembers=(\\d+)");
    private static final Pattern LOADED_NUM = Pattern.compile("\\bloaded=(\\d+)");
    private static final Pattern NORMAL_WRITTEN = Pattern.compile("\\bnormal=(\\d+)|普通写入=(\\d+)");
    private static final Pattern SPECIAL_WRITTEN = Pattern.compile("\\bspecial=(\\d+)|特殊写入=(\\d+)");
    private static final List<String> LAUNCHER_NAMES = List.of(
        "start-redis-loader.cmd", "start-loader.cmd", "run-loader.cmd", "run-generator.cmd",
        "start-redis-loader.sh", "start-loader.sh", "run-loader.sh", "run-generator.sh");
    private static final List<String> CONFIG_NAMES = List.of(
        "generator.properties", "loader.properties", "config.properties");
    private static final int MAX_CONCURRENT = 15;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final int LOG_PARSE_BYTES = 256 * 1024;
    private static final int LOG_TAIL_BYTES = 48 * 1024;

    private final Path root;
    private final Path runtimeRoot;
    private final ObjectMapper mapper;
    private final Object monitor = new Object();
    private final LinkedHashMap<String, RunState> runs = new LinkedHashMap<>();

    public CpgameBetLogService(AdminSettings settings, ObjectMapper mapper) {
        this.root = settings.getRoot();
        this.runtimeRoot = settings.runtimeRoot().resolve("betlog");
        this.mapper = mapper;
        recoverState();
    }

    public Map<String, BetLogCapability> capabilities(Collection<String> directories) {
        LinkedHashMap<String, BetLogCapability> result = new LinkedHashMap<>();
        if (directories == null) return result;
        for (String directory : directories) result.put(directory, capability(directory));
        return result;
    }

    public BetLogCapability capability(String directoryName) {
        try {
            requireSafeDirectory(directoryName);
        } catch (IllegalArgumentException error) {
            return unavailable(directoryName, error.getMessage());
        }
        DistBundle dist = discover(directoryName);
        if (dist == null) {
            return unavailable(directoryName, "未找到 generator/" + directoryName + "/dist");
        }
        if (dist.jar() == null) {
            return new BetLogCapability(false, directoryName, dist.launcherName(), null, dist.configName(),
                dist.dist().toString(), "dist 中没有可执行 JAR", -1, -1, -1);
        }
        CountTargets targets = readTargets(dist.config());
        return new BetLogCapability(true, directoryName, dist.launcherName(), dist.jarName(), dist.configName(),
            dist.dist().toString(), "可执行 " + dist.displayCommand(),
            targets.normal(), targets.special(), targets.total());
    }

    public Map<String, BetLogStatus> overview(Collection<String> directories) {
        LinkedHashMap<String, BetLogStatus> result = new LinkedHashMap<>();
        if (directories == null) return result;
        for (String directory : directories) {
            result.put(directory, status(directory, false));
        }
        return result;
    }

    public int runningCount() {
        synchronized (monitor) {
            refreshDeadProcesses();
            return (int) runs.values().stream().filter(run -> "RUNNING".equals(run.state)).count();
        }
    }

    public BetLogStatus status(String directoryName) {
        return status(directoryName, true);
    }

    public BetLogStatus run(String directoryName) {
        requireSafeDirectory(directoryName);
        DistBundle dist = discover(directoryName);
        if (dist == null) {
            return statusWithMessage(directoryName, false, "UNAVAILABLE", "未找到 generator/" + directoryName + "/dist");
        }
        if (dist.jar() == null) {
            return statusWithMessage(directoryName, false, "UNAVAILABLE", "dist 中没有可执行 JAR，无法 runRedis");
        }
        synchronized (monitor) {
            refreshDeadProcesses();
            RunState existing = runs.get(directoryName);
            if (existing != null && "RUNNING".equals(existing.state)
                && ProcessHandle.of(existing.processId).map(ProcessHandle::isAlive).orElse(false)) {
                return snapshot(existing, dist, true, "生成任务已在运行");
            }
            if (runningLocked() >= MAX_CONCURRENT) {
                throw new IllegalStateException("同时最多运行 " + MAX_CONCURRENT + " 个 Redis 生成任务");
            }
            Path logFile = newLogFile(directoryName);
            Process process = null;
            try {
                Files.createDirectories(runtimeDirectory());
                List<String> command = launchCommand(dist);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(dist.dist().toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                process = builder.start();
                closeQuietly(process.getOutputStream());
                RunState state = new RunState();
                state.directoryName = directoryName;
                state.state = "RUNNING";
                state.processId = process.pid();
                state.process = process;
                state.startedAt = Instant.now();
                state.logFile = logFile.toString();
                state.commandName = dist.launcherName();
                state.jarName = dist.jarName();
                state.configName = dist.configName();
                state.commandLine = dist.displayCommand();
                state.message = "已启动 runRedis";
                runs.put(directoryName, state);
                persist(state);
                process.onExit().thenAccept(done -> finalizeRun(directoryName, done.toHandle(), logFile, done.exitValue()));
                return snapshot(state, dist, true, state.message);
            } catch (IOException error) {
                stopProcess(process);
                throw new IllegalStateException("无法启动 Redis Loader：" + concise(error), error);
            }
        }
    }

    public BetLogStatus interrupt(String directoryName) {
        requireSafeDirectory(directoryName);
        ProcessHandle handle;
        RunState snapshot;
        synchronized (monitor) {
            refreshDeadProcesses();
            RunState state = runs.get(directoryName);
            if (state == null || !"RUNNING".equals(state.state)) {
                return statusWithMessage(directoryName, true, state == null ? "IDLE" : state.state, "当前没有生成任务");
            }
            handle = ProcessHandle.of(state.processId).orElse(null);
            state.state = "INTERRUPTED";
            state.finishedAt = Instant.now();
            state.message = "已中断 runRedis";
            if (state.logFile != null) appendExit(Path.of(state.logFile), "INTERRUPTED", null);
            persist(state);
            snapshot = state;
        }
        stopProcess(handle);
        return snapshot(snapshot, discover(directoryName), true, snapshot.message);
    }

    public InterruptAllResult interruptAll() {
        List<ProcessHandle> handles = new ArrayList<>();
        int stopped;
        synchronized (monitor) {
            refreshDeadProcesses();
            stopped = 0;
            for (RunState state : new ArrayList<>(runs.values())) {
                if (!"RUNNING".equals(state.state)) continue;
                ProcessHandle handle = ProcessHandle.of(state.processId).orElse(null);
                if (handle != null) handles.add(handle);
                state.state = "INTERRUPTED";
                state.finishedAt = Instant.now();
                state.message = "已中断 runRedis";
                if (state.logFile != null) appendExit(Path.of(state.logFile), "INTERRUPTED", null);
                persist(state);
                stopped++;
            }
        }
        handles.forEach(this::stopProcess);
        return new InterruptAllResult(true, stopped, stopped == 0 ? "当前没有生成任务" : "已中断 " + stopped + " 个生成任务");
    }

    public ConfigDocument readConfig(String directoryName) {
        requireSafeDirectory(directoryName);
        DistBundle dist = discover(directoryName);
        if (dist == null) throw new IllegalArgumentException("未找到 generator/" + directoryName + "/dist");
        if (dist.config() == null) {
            return new ConfigDocument(false, null, "", "dist 中没有 generator.properties / loader.properties");
        }
        try {
            long size = Files.size(dist.config());
            if (size > MAX_CONFIG_BYTES) {
                throw new IllegalStateException("配置文件超过 " + MAX_CONFIG_BYTES + " 字节");
            }
            return new ConfigDocument(true, dist.configName(),
                Files.readString(dist.config(), StandardCharsets.UTF_8), "已读取 " + dist.configName());
        } catch (IOException error) {
            throw new IllegalStateException("无法读取配置：" + concise(error), error);
        }
    }

    public ConfigDocument writeConfig(String directoryName, String content) {
        requireSafeDirectory(directoryName);
        if (content == null) throw new IllegalArgumentException("配置内容不能为空");
        if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("配置不能包含空字符");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("配置不能超过 " + MAX_CONFIG_BYTES + " 字节");
        }
        DistBundle dist = discover(directoryName);
        if (dist == null) throw new IllegalArgumentException("未找到 generator/" + directoryName + "/dist");
        Path target = dist.config();
        if (target == null) {
            target = dist.dist().resolve("generator.properties");
        }
        try {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(target, Path.of(target.toString() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return new ConfigDocument(true, target.getFileName().toString(), content, "已保存 " + target.getFileName());
        } catch (IOException error) {
            throw new IllegalStateException("无法保存配置：" + concise(error), error);
        }
    }

    private BetLogStatus status(String directoryName, boolean includeLog) {
        requireSafeDirectory(directoryName);
        DistBundle dist = discover(directoryName);
        synchronized (monitor) {
            refreshDeadProcesses();
            RunState state = runs.get(directoryName);
            if (state == null) {
                if (dist == null) {
                    return emptyStatus(directoryName, false, "UNAVAILABLE", null, null, null, null,
                        "未找到 generator/" + directoryName + "/dist", CountTargets.unknown());
                }
                CountTargets targets = readTargets(dist.config());
                String message = dist.jar() == null ? "dist 中没有可执行 JAR" : "尚未 runRedis";
                return emptyStatus(directoryName, dist.jar() != null, dist.jar() == null ? "UNAVAILABLE" : "IDLE",
                    dist.launcherName(), dist.jarName(), dist.configName(), dist.displayCommand(),
                    message, targets);
            }
            return snapshot(state, dist, includeLog, state.message);
        }
    }

    private BetLogStatus statusWithMessage(String directoryName, boolean ok, String state, String message) {
        DistBundle dist = discover(directoryName);
        CountTargets targets = dist == null ? CountTargets.unknown() : readTargets(dist.config());
        return emptyStatus(directoryName, ok, state,
            dist == null ? null : dist.launcherName(),
            dist == null ? null : dist.jarName(),
            dist == null ? null : dist.configName(),
            dist == null ? null : dist.displayCommand(),
            message, targets);
    }

    private BetLogStatus emptyStatus(String directoryName, boolean ok, String state, String commandName,
                                     String jarName, String configName, String commandLine, String message,
                                     CountTargets targets) {
        return new BetLogStatus(ok, state, directoryName, commandName, jarName, configName, commandLine,
            0L, null, null, null, message,
            new BetLogCounts(-1, -1, -1, -1, targets.normal(), targets.special(), targets.total()),
            "", 0L);
    }

    private BetLogStatus snapshot(RunState state, DistBundle dist, boolean includeLog, String message) {
        Path log = state.logFile == null || state.logFile.isBlank() ? null : Path.of(state.logFile);
        long logBytes = 0L;
        String tail = "";
        if (log != null && Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            try { logBytes = Files.size(log); } catch (IOException ignored) { }
            if (includeLog) tail = tail(log, LOG_TAIL_BYTES);
        }
        if ("FAILED".equals(state.state)) {
            String reason = failureReason(tail);
            if (reason != null) message = "runRedis 失败：" + reason;
        }
        CountTargets targets = dist == null ? CountTargets.unknown() : readTargets(dist.config());
        BetLogCounts counts = parseCounts(log, targets);
        String commandName = state.commandName != null ? state.commandName : (dist == null ? null : dist.launcherName());
        String jarName = state.jarName != null ? state.jarName : (dist == null ? null : dist.jarName());
        String configName = state.configName != null ? state.configName : (dist == null ? null : dist.configName());
        String commandLine = state.commandLine != null ? state.commandLine : (dist == null ? null : dist.displayCommand());
        return new BetLogStatus(true, state.state, state.directoryName, commandName, jarName, configName,
            commandLine, state.processId,
            state.startedAt == null ? null : state.startedAt.toEpochMilli(),
            state.finishedAt == null ? null : state.finishedAt.toEpochMilli(),
            state.exitCode, message, counts, tail, logBytes);
    }

    private void finalizeRun(String directoryName, ProcessHandle done, Path logFile, Integer exitCode) {
        synchronized (monitor) {
            RunState state = runs.get(directoryName);
            if (state == null || state.processId != done.pid()) return;
            if ("INTERRUPTED".equals(state.state)) return;
            String logText = tail(logFile, LOG_PARSE_BYTES);
            FinishClassification result = classifyFinish(exitCode, logText);
            state.exitCode = result.exitCode();
            state.finishedAt = Instant.now();
            state.state = result.state();
            state.message = result.message();
            appendExit(logFile, state.state, result.exitCode());
            persist(state);
        }
    }

    static FinishClassification classifyFinish(Integer exitCode, String logText) {
        String text = logText == null ? "" : logText;
        String reason = failureReason(text);
        if (reason != null) return new FinishClassification("FAILED", "runRedis 失败：" + reason, exitCode);
        boolean crashed = CRASH_LINE.matcher(text).find();
        boolean complete = COMPLETE_LINE.matcher(text).find();
        if (crashed) {
            return new FinishClassification("FAILED", "runRedis 失败，进程异常退出", exitCode);
        }
        if (exitCode != null && exitCode != 0) {
            return new FinishClassification("FAILED", "runRedis 失败，退出码 " + exitCode, exitCode);
        }
        if (complete || (exitCode != null && exitCode == 0)) {
            return new FinishClassification("COMPLETED", "runRedis 已完成", exitCode);
        }
        return new FinishClassification("FAILED", "生成进程已退出", exitCode);
    }

    static String failureReason(String logText) {
        if (logText == null) return null;
        String reason = null;
        for (String line : logText.split("\\R")) {
            String value = line.strip();
            if (value.matches("(?i)^(?:\\[(?:失败|FAILED|ERROR)\\]|生成失败[：:]|Exception in thread|Caused by:|[\\w.$]+(?:Exception|Error):).*")) {
                reason = value.replaceFirst("(?i)^(?:\\[(?:失败|FAILED|ERROR)\\]\\s*|生成失败[：:]\\s*)", "");
            }
        }
        return reason == null || reason.isBlank() ? null : reason;
    }

    private DistBundle discover(String directoryName) {
        Path generatorRoot = root.resolve("generator").toAbsolutePath().normalize();
        Path dist = generatorRoot.resolve(directoryName).resolve("dist").normalize();
        if (!dist.startsWith(generatorRoot)
            || !Files.isDirectory(dist, LinkOption.NOFOLLOW_LINKS)) return null;
        List<Path> files = listRegularFiles(dist);
        Path launcher = findLauncher(files);
        String launcherText = launcher == null ? "" : readAscii(launcher);
        Path jar = findJar(files, launcherText);
        Path config = findConfig(files, launcherText);
        boolean configFlag = launcherText.contains("--config");
        return new DistBundle(dist, jar, config, launcher, configFlag);
    }

    private Path findLauncher(List<Path> files) {
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path file : files) byName.put(file.getFileName().toString().toLowerCase(Locale.ROOT), file);
        for (String name : LAUNCHER_NAMES) {
            Path match = byName.get(name);
            if (match != null) return match;
        }
        return files.stream()
            .filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".sh");
            })
            .min(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
            .orElse(null);
    }

    private Path findJar(List<Path> files, String launcherText) {
        List<Path> jars = files.stream()
            .filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
            })
            .toList();
        for (Path jar : jars) {
            if (launcherText.contains(jar.getFileName().toString())) return jar;
        }
        return jars.stream()
            .min(Comparator.comparingInt(this::jarRank)
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
            .orElse(null);
    }

    private int jarRank(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("redis-loader")) return 0;
        if (name.contains("loader")) return 1;
        if (name.contains("generator")) return 2;
        return 3;
    }

    private Path findConfig(List<Path> files, String launcherText) {
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path file : files) byName.put(file.getFileName().toString().toLowerCase(Locale.ROOT), file);
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".properties") && launcherText.contains(name)) return file;
        }
        for (String name : CONFIG_NAMES) {
            Path match = byName.get(name);
            if (match != null) return match;
        }
        return files.stream()
            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties"))
            .min(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
            .orElse(null);
    }

    private List<String> launchCommand(DistBundle dist) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-jar");
        command.add(dist.jar().toAbsolutePath().normalize().toString());
        if (dist.config() != null) {
            String configPath = dist.config().toAbsolutePath().normalize().toString();
            command.add(dist.configFlag() ? "--config=" + configPath : configPath);
        }
        return command;
    }

    private CountTargets readTargets(Path config) {
        if (config == null || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) return CountTargets.unknown();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException ignored) {
            return CountTargets.unknown();
        }
        long normal = longProperty(properties, "generation.normal-count");
        long special = longProperty(properties, "generation.special-count");
        long total = longProperty(properties, "generation.total-members");
        if (total < 0 && (normal >= 0 || special >= 0)) {
            total = Math.max(normal, 0) + Math.max(special, 0);
        }
        return new CountTargets(normal, special, total);
    }

    private long longProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private BetLogCounts parseCounts(Path log, CountTargets targets) {
        String text = tail(log, LOG_PARSE_BYTES);
        long loaded = -1;
        long batches = -1;
        long normal = -1;
        long special = -1;
        long lastMembers = -1;
        long maxMembers = -1;
        if (!text.isBlank()) {
            for (String line : text.split("\\R")) {
                if (BATCH_LINE.matcher(line).find()) {
                    long batch = namedLong(line, BATCH_NUM);
                    long members = namedLong(line, MEMBERS_NUM);
                    long lineLoaded = namedLong(line, LOADED_NUM);
                    if (batch >= 0) batches = batch;
                    if (members >= 0) {
                        lastMembers = members;
                        maxMembers = Math.max(maxMembers, members);
                    }
                    if (lineLoaded >= 0) loaded = lineLoaded;
                }
                if (COMPLETE_LINE.matcher(line).find()) {
                    long writtenNormal = namedLong(line, NORMAL_WRITTEN);
                    long writtenSpecial = namedLong(line, SPECIAL_WRITTEN);
                    long completeBatches = namedLong(line, BATCHES_NUM);
                    if (writtenNormal >= 0) normal = writtenNormal;
                    if (writtenSpecial >= 0) special = writtenSpecial;
                    if (completeBatches >= 0) batches = completeBatches;
                    if (normal >= 0 || special >= 0) {
                        loaded = Math.max(normal, 0) + Math.max(special, 0);
                    } else {
                        long lineLoaded = namedLong(line, LOADED_NUM);
                        if (lineLoaded >= 0) loaded = lineLoaded;
                    }
                } else {
                    long lineLoaded = namedLong(line, LOADED_NUM);
                    if (lineLoaded >= 0) loaded = lineLoaded;
                }
            }
        }
        if (loaded < 0 && batches > 0 && maxMembers > 0) {
            loaded = lastMembers >= 0 && lastMembers < maxMembers && batches > 1
                ? (batches - 1) * maxMembers + lastMembers
                : batches * maxMembers;
        }
        return new BetLogCounts(loaded, batches, normal, special,
            targets.normal(), targets.special(), targets.total());
    }

    private static long namedLong(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) return -1;
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (matcher.group(i) != null) return parseLongValue(matcher.group(i));
        }
        return -1;
    }

    private static long parseLongValue(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }

    private String tail(Path log, int maxBytes) {
        if (log == null || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) return "";
        try {
            long size = Files.size(log);
            if (size <= 0) return "";
            long start = Math.max(0, size - maxBytes);
            try (var channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
                channel.position(start);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break;
                }
                String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
                if (start > 0) {
                    int newline = text.indexOf('\n');
                    if (newline >= 0 && newline + 1 < text.length()) text = text.substring(newline + 1);
                }
                return text;
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    private List<Path> listRegularFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
        return files;
    }

    private String readAscii(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 64 * 1024) {
                byte[] prefix = new byte[64 * 1024];
                System.arraycopy(bytes, 0, prefix, 0, prefix.length);
                bytes = prefix;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private Path newLogFile(String directoryName) {
        return runtimeDirectory().resolve(safeLogName(directoryName) + "-" + System.currentTimeMillis() + ".log");
    }

    private void refreshDeadProcesses() {
        for (RunState state : runs.values()) {
            if (!"RUNNING".equals(state.state)) continue;
            if (ProcessHandle.of(state.processId).map(ProcessHandle::isAlive).orElse(false)) continue;
            Path log = state.logFile == null ? null : Path.of(state.logFile);
            String text = tail(log, LOG_PARSE_BYTES);
            Integer exitCode = state.process != null && !state.process.isAlive() ? state.process.exitValue() : state.exitCode;
            FinishClassification result = classifyFinish(exitCode, text);
            state.exitCode = result.exitCode();
            state.state = result.state();
            state.finishedAt = Instant.now();
            state.message = result.message();
            persist(state);
        }
    }

    private int runningLocked() {
        return (int) runs.values().stream().filter(run -> "RUNNING".equals(run.state)).count();
    }

    private void recoverState() {
        Path runtime = runtimeDirectory();
        if (!Files.isDirectory(runtime, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.list(runtime)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted()
                .forEach(this::recoverFile);
        } catch (IOException ignored) {
        }
        synchronized (monitor) {
            refreshDeadProcesses();
        }
    }

    private void recoverFile(Path file) {
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String directory = node.path("directoryName").asString("");
            if (directory.isBlank()) return;
            requireSafeDirectory(directory);
            RunState state = new RunState();
            state.directoryName = directory;
            state.state = node.path("state").asString("IDLE");
            state.processId = node.path("processId").asLong(0L);
            long started = node.path("startedAt").asLong(0L);
            if (started > 0) state.startedAt = Instant.ofEpochMilli(started);
            long finished = node.path("finishedAt").asLong(0L);
            if (finished > 0) state.finishedAt = Instant.ofEpochMilli(finished);
            if (node.path("exitCode").isNumber()) state.exitCode = node.path("exitCode").asInt();
            state.logFile = node.path("logFile").asString(null);
            state.commandName = node.path("commandName").asString(null);
            state.jarName = node.path("jarName").asString(null);
            state.configName = node.path("configName").asString(null);
            state.commandLine = node.path("commandLine").asString(null);
            state.message = node.path("message").asString("");
            if ("RUNNING".equals(state.state)) {
                ProcessHandle handle = ProcessHandle.of(state.processId).orElse(null);
                if (handle != null && handle.isAlive()) {
                    Path log = state.logFile == null ? null : Path.of(state.logFile);
                    handle.onExit().thenAccept(done -> finalizeRun(directory, done, log, null));
                }
            }
            synchronized (monitor) {
                runs.put(directory, state);
            }
        } catch (RuntimeException | IOException ignored) {
        }
    }

    private void persist(RunState state) {
        try {
            Files.createDirectories(runtimeDirectory());
            ObjectNode node = mapper.createObjectNode();
            node.put("directoryName", state.directoryName);
            node.put("state", state.state);
            node.put("processId", state.processId);
            node.put("startedAt", state.startedAt == null ? 0L : state.startedAt.toEpochMilli());
            node.put("finishedAt", state.finishedAt == null ? 0L : state.finishedAt.toEpochMilli());
            if (state.exitCode != null) node.put("exitCode", state.exitCode);
            if (state.logFile != null) node.put("logFile", state.logFile);
            if (state.commandName != null) node.put("commandName", state.commandName);
            if (state.jarName != null) node.put("jarName", state.jarName);
            if (state.configName != null) node.put("configName", state.configName);
            if (state.commandLine != null) node.put("commandLine", state.commandLine);
            if (state.message != null) node.put("message", state.message);
            Path target = stateFile(state.directoryName);
            Files.writeString(target, mapper.writeValueAsString(node), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
        }
    }

    private Path runtimeDirectory() {
        return runtimeRoot;
    }

    private Path stateFile(String directoryName) {
        return runtimeDirectory().resolve(safeLogName(directoryName) + ".json");
    }

    private void stopProcess(Process process) {
        if (process != null) stopProcess(process.toHandle());
    }

    private void stopProcess(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) return;
        handle.descendants().forEach(child -> {
            try { child.destroy(); } catch (RuntimeException ignored) { }
        });
        handle.destroy();
        try {
            handle.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            handle.descendants().forEach(child -> {
                try { if (child.isAlive()) child.destroyForcibly(); } catch (RuntimeException ignoredChild) { }
            });
            if (handle.isAlive()) handle.destroyForcibly();
        }
    }

    private void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) { }
    }

    private void appendExit(Path log, String state, Integer exitCode) {
        if (log == null) return;
        try {
            String line = System.lineSeparator() + "[exit] state=" + state
                + (exitCode == null ? "" : " code=" + exitCode) + System.lineSeparator();
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
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

    private static BetLogCapability unavailable(String directoryName, String message) {
        return new BetLogCapability(false, directoryName, null, null, null, null, message, -1, -1, -1);
    }

    private static final class RunState {
        Process process;
        String directoryName;
        String state;
        long processId;
        Instant startedAt;
        Instant finishedAt;
        Integer exitCode;
        String logFile;
        String commandName;
        String jarName;
        String configName;
        String commandLine;
        String message;
    }

    private record DistBundle(Path dist, Path jar, Path config, Path launcher, boolean configFlag) {
        String launcherName() { return launcher == null ? null : launcher.getFileName().toString(); }
        String jarName() { return jar == null ? null : jar.getFileName().toString(); }
        String configName() { return config == null ? null : config.getFileName().toString(); }
        String displayCommand() {
            if (jar == null) return launcherName();
            if (config == null) return "java -jar " + jarName();
            return configFlag
                ? "java -jar " + jarName() + " --config=" + configName()
                : "java -jar " + jarName() + " " + configName();
        }
    }

    private record CountTargets(long normal, long special, long total) {
        static CountTargets unknown() { return new CountTargets(-1, -1, -1); }
    }

    public record BetLogCapability(boolean available, String directoryName, String commandName, String jarName,
                                   String configName, String distPath, String message,
                                   long targetNormal, long targetSpecial, long targetTotal) { }

    public record BetLogCounts(long loaded, long batches, long normal, long special,
                               long targetNormal, long targetSpecial, long targetTotal) { }

    public record BetLogStatus(boolean ok, String state, String directoryName, String commandName, String jarName,
                               String configName, String commandLine, long processId, Long startedAt, Long finishedAt,
                               Integer exitCode, String message, BetLogCounts counts, String logTail, long logBytes) {
        public String stateLabel() {
            if (state == null) return "未运行";
            return switch (state) {
                case "RUNNING" -> "生成中";
                case "COMPLETED" -> "已完成";
                case "FAILED" -> "失败";
                case "INTERRUPTED" -> "已中断";
                case "UNAVAILABLE" -> "不可用";
                default -> "未运行";
            };
        }

        public String getStateLabel() {
            return stateLabel();
        }
    }

    public record ConfigDocument(boolean ok, String fileName, String content, String message) { }

    public record InterruptAllResult(boolean ok, int stoppedCount, String message) { }

    record FinishClassification(String state, String message, Integer exitCode) { }
}
