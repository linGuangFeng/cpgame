package com.cpgame.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.thymeleaf.context.Context;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CpgamePublicLabServer {
    private static final Pattern SAFE_DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final int MAX_BETLOG_CONFIG_BYTES = 300 * 1024;
    private static final int MAX_BETLOG_COUNT_BYTES = 256 * 1024;
    private static final Set<String> HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "expect", "host", "content-length");
    private final CpgameLabService labService;
    private final CpgameDemoRuntimeService demoRuntime;
    private final CpgameBetLogService betLog;
    private final CpgameRedisCacheService cache;
    private final AdminSettings config;
    private final TemplateEngine templates;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile boolean running;

    public CpgamePublicLabServer(CpgameLabService labService, CpgameDemoRuntimeService demoRuntime,
                                 CpgameBetLogService betLog, AdminSettings settings,
                                 ObjectMapper mapper) {
        this.labService = labService;
        this.demoRuntime = demoRuntime;
        this.betLog = betLog;
        this.cache = settings == null ? null : new CpgameRedisCacheService(settings.getRoot());
        this.config = settings;
        this.templates = labTemplateEngine();
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    CpgamePublicLabServer(CpgameLabService labService, AdminSettings settings) {
        this(labService, null, null, settings, new ObjectMapper());
    }

    static TemplateEngine labTemplateEngine() {
        TemplateEngine engine = new TemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(
            CpgamePublicLabServer.class.getClassLoader());
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        return engine;
    }

    public synchronized void start() {
        if (running || !config.isPublicEnabled()) return;
        try {
            server = HttpServer.create(new InetSocketAddress(config.getPublicAddress(), config.getPublicPort()), 0);
            server.createContext("/health", exchange -> send(exchange, 200, "text/plain; charset=UTF-8", "ok".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/assets/", this::serveAsset);
            server.createContext("/cpgame-lab/games/", this::serveGameResource);
            server.createContext("/games/", this::serveGameResource);
            server.createContext("/play/", this::proxyGame);
            server.createContext("/", this::servePage);
            executor = Executors.newFixedThreadPool(16, runnable -> {
                Thread thread = new Thread(runnable, "cpgame-public-lab");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            running = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start standalone CPGame lab on port " + config.getPublicPort(), exception);
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || "/cpgame-lab".equals(path) || "/cpgame-lab/".equals(path)) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                byte[] body = renderPage(exchange, labService.listGames()).getBytes(StandardCharsets.UTF_8);
                send(exchange, 200, "text/html; charset=UTF-8", body);
            } catch (RuntimeException | Error error) {
                byte[] body = "public lab unavailable".getBytes(StandardCharsets.UTF_8);
                send(exchange, 500, "text/plain; charset=UTF-8", body);
            }
            return;
        }
        proxyGame(exchange);
    }

    private void serveAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
            && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith("/assets/") || path.contains("..") || path.contains("\\")) {
            send(exchange, 404, "text/plain; charset=UTF-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream input = CpgamePublicLabServer.class.getResourceAsStream("/static" + path)) {
            if (input == null) {
                // Game publish files also live under /assets/. Do not 404 them here.
                proxyGame(exchange);
                return;
            }
            send(exchange, 200, assetMediaType(path), input.readAllBytes());
        }
    }

    private void serveGameResource(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/cpgame-lab/games/")) path = path.substring("/cpgame-lab".length());
        String prefix = "/games/";
        if (!path.startsWith(prefix)) {
            send(exchange, 404, "text/plain; charset=UTF-8", new byte[0]);
            return;
        }
        if (path.endsWith("/cover") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveCover(exchange, path.substring(prefix.length(), path.length() - "/cover".length()));
            return;
        }
        if (path.endsWith("/paylines") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            servePaylineUpdate(exchange, path.substring(prefix.length(), path.length() - "/paylines".length()));
            return;
        }
        if (path.endsWith("/acceptance") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveAcceptanceUpdate(exchange, path.substring(prefix.length(), path.length() - "/acceptance".length()));
            return;
        }
        int referenceMarker = path.lastIndexOf("/references/");
        if (referenceMarker > prefix.length() && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveReferenceUpdate(exchange, path.substring(prefix.length(), referenceMarker),
                path.substring(referenceMarker + "/references/".length()));
            return;
        }
        if (path.endsWith("/start") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveGameStart(exchange, path.substring(prefix.length(), path.length() - "/start".length()));
            return;
        }
        if ("/games/stop-all".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveStopAll(exchange);
            return;
        }
        if (path.endsWith("/stop") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveGameStop(exchange, path.substring(prefix.length(), path.length() - "/stop".length()));
            return;
        }
        if ("/games/betlog/overview".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogOverview(exchange);
            return;
        }
        if ("/games/betlog/interrupt-all".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogInterruptAll(exchange);
            return;
        }
        if (path.endsWith("/betlog/run") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogRun(exchange, path.substring(prefix.length(), path.length() - "/betlog/run".length()));
            return;
        }
        if (path.endsWith("/betlog/interrupt") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogInterrupt(exchange, path.substring(prefix.length(), path.length() - "/betlog/interrupt".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/counts") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheCounts(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/counts".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/memory") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheMemory(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/memory".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/members") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheMembers(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/members".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/delete-prefix") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheDeletePrefix(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/delete-prefix".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/delete-all") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheDeleteAll(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/delete-all".length()));
            return;
        }
        if (path.endsWith("/betlog/cache/delete") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCacheDelete(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache/delete".length()));
            return;
        }
        if (path.endsWith("/betlog/cache") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogCache(exchange, path.substring(prefix.length(), path.length() - "/betlog/cache".length()));
            return;
        }
        if (path.endsWith("/betlog/config") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogConfigRead(exchange, path.substring(prefix.length(), path.length() - "/betlog/config".length()));
            return;
        }
        if (path.endsWith("/betlog/config") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogConfigWrite(exchange, path.substring(prefix.length(), path.length() - "/betlog/config".length()));
            return;
        }
        if (path.endsWith("/betlog") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            serveBetLogStatus(exchange, path.substring(prefix.length(), path.length() - "/betlog".length()));
            return;
        }
        send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
    }

    private void serveCover(HttpExchange exchange, String encodedDirectory) throws IOException {
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        var cover = labService.cover(directory);
        if (cover.isEmpty()) {
            send(exchange, 404, "text/plain; charset=UTF-8", new byte[0]);
            return;
        }
        send(exchange, 200, cover.get().mediaType(), cover.get().bytes());
    }

    private void servePaylineUpdate(HttpExchange exchange, String encodedDirectory) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(2049);
        if (payload.length > 2048) {
            send(exchange, 413, "text/plain; charset=UTF-8", "Request too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        String value = formValue(new String(payload, StandardCharsets.UTF_8), "value");
        try {
            var result = labService.updatePaylines(directory, value);
            String json = "{\"ok\":true,\"label\":\"" + jsonEscape(result.label()) + "\"}";
            send(exchange, 200, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveAcceptanceUpdate(HttpExchange exchange, String encodedDirectory) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(2049);
        if (payload.length > 2048) {
            send(exchange, 413, "text/plain; charset=UTF-8", "Request too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        String status = formValue(new String(payload, StandardCharsets.UTF_8), "status");
        try {
            var result = labService.updateAcceptance(directory, status);
            String json = "{\"ok\":true,\"status\":\"" + jsonEscape(result.status())
                + "\",\"label\":\"" + jsonEscape(result.label()) + "\"}";
            send(exchange, 200, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveReferenceUpdate(HttpExchange exchange, String encodedDirectory,
                                      String rawSlot) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(8193);
        if (payload.length > 8192) {
            send(exchange, 413, "text/plain; charset=UTF-8", "Request too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        String value = formValue(new String(payload, StandardCharsets.UTF_8), "value");
        try {
            int slot = Integer.parseInt(rawSlot);
            var result = labService.updateReferenceLink(directory, slot, value);
            String json = "{\"ok\":true,\"reference1Url\":" + jsonNullable(result.reference1Url())
                + ",\"reference2Url\":" + jsonNullable(result.reference2Url()) + "}";
            send(exchange, 200, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveGameStart(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (demoRuntime == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "试玩进程管理器未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        try {
            var result = demoRuntime.startGame(directory);
            if (result.ok()) playCookie(exchange, directory);
            String json = "{\"ok\":" + result.ok() + ",\"state\":\"" + jsonEscape(result.state())
                + "\",\"demoUrl\":" + (result.demoUrl() == null ? "null" : "\"" + jsonEscape(publicDemoUrl(exchange, result.demoUrl())) + "\"")
                + ",\"message\":\"" + jsonEscape(result.message()) + "\",\"processId\":" + result.processId()
                + ",\"port\":" + result.port() + ",\"evictedDirectory\":" + jsonNullable(result.evictedDirectory()) + "}";
            send(exchange, result.ok() ? 200 : 500, "application/json; charset=UTF-8",
                json.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveGameStop(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (demoRuntime == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "试玩进程管理器未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            var result = demoRuntime.stopGame(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8));
            String json = "{\"ok\":true,\"message\":\"" + jsonEscape(result.message())
                + "\",\"remainingCount\":" + result.remainingCount() + "}";
            send(exchange, 200, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveStopAll(HttpExchange exchange) throws IOException {
        if (demoRuntime == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "试玩进程管理器未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var result = demoRuntime.stopAll();
        String json = "{\"ok\":true,\"message\":\"" + jsonEscape(result.message())
            + "\",\"stoppedCount\":" + result.stoppedCount() + "}";
        send(exchange, 200, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void serveBetLogOverview(HttpExchange exchange) throws IOException {
        if (betLog == null || labService == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var directories = labService.listGames().stream().map(CpgameLabService.GameCard::directoryName).toList();
        sendJson(exchange, 200, java.util.Map.of(
            "ok", true,
            "runningCount", betLog.runningCount(),
            "runs", betLog.overview(directories)));
    }

    private void serveBetLogInterruptAll(HttpExchange exchange) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendJson(exchange, 200, betLog.interruptAll());
    }

    private void serveBetLogStatus(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, betLog.status(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogRun(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            var result = betLog.run(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8));
            sendJson(exchange, result.ok() ? 200 : 400, result);
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogInterrupt(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, betLog.interrupt(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCache(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.inspect(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheCounts(HttpExchange exchange, String encodedDirectory) throws IOException {
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.counts(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8), request.keys));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheMemory(HttpExchange exchange, String encodedDirectory) throws IOException {
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.memory(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8), request.keys, request.counts));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheMembers(HttpExchange exchange, String encodedDirectory) throws IOException {
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.members(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8),
                request.key, request.offset, request.limit));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheDelete(HttpExchange exchange, String encodedDirectory) throws IOException {
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.deleteLists(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8), request.keys));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheDeletePrefix(HttpExchange exchange, String encodedDirectory) throws IOException {
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, cache.deletePrefix(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8), request.indexKey));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogCacheDeleteAll(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (cache == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "缓存查看未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        CacheKeyRequest request = readCacheKeyRequest(exchange);
        if (request == null) return;
        try {
            sendJson(exchange, 200, cache.deleteAll(
                URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8), request.indexKeys, request.keys));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private CacheKeyRequest readCacheKeyRequest(HttpExchange exchange) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(MAX_BETLOG_COUNT_BYTES + 1);
        if (payload.length > MAX_BETLOG_COUNT_BYTES) {
            send(exchange, 413, "text/plain; charset=UTF-8", "Request too large".getBytes(StandardCharsets.UTF_8));
            return null;
        }
        List<String> keys = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(payload.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : payload);
            JsonNode list = root.get("keys");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    String key = item.asText("");
                    if (!key.isBlank()) keys.add(key);
                }
            }
            JsonNode countNode = root.get("counts");
            if (countNode != null && countNode.isObject()) {
                for (String key : keys) {
                    JsonNode value = countNode.get(key);
                    if (value != null && value.isNumber()) counts.put(key, value.asLong());
                }
            }
            JsonNode keyNode = root.get("key");
            String key = keyNode == null ? "" : keyNode.asText("");
            if (key.isBlank() && !keys.isEmpty()) key = keys.get(0);
            JsonNode indexNode = root.get("indexKey");
            String indexKey = indexNode == null ? "" : indexNode.asText("");
            JsonNode offsetNode = root.get("offset");
            int offset = offsetNode != null && offsetNode.isNumber() ? offsetNode.asInt() : 0;
            JsonNode limitNode = root.get("limit");
            int limit = limitNode != null && limitNode.isNumber() ? limitNode.asInt() : 20;
            List<String> indexKeys = new ArrayList<>();
            JsonNode indexKeysNode = root.get("indexKeys");
            if (indexKeysNode != null && indexKeysNode.isArray()) {
                for (JsonNode item : indexKeysNode) {
                    String value = item.asText("");
                    if (!value.isBlank()) indexKeys.add(value);
                }
            }
            if (!indexKey.isBlank()) indexKeys.add(indexKey);
            return new CacheKeyRequest(keys, counts, key, indexKey, indexKeys, offset, limit);
        } catch (RuntimeException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", "keys 无效".getBytes(StandardCharsets.UTF_8));
            return null;
        }
    }

    private record CacheKeyRequest(List<String> keys, Map<String, Long> counts, String key, String indexKey,
                                   List<String> indexKeys, int offset, int limit) { }

    private void serveBetLogConfigRead(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            sendJson(exchange, 200, betLog.readConfig(URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveBetLogConfigWrite(HttpExchange exchange, String encodedDirectory) throws IOException {
        if (betLog == null) {
            send(exchange, 503, "text/plain; charset=UTF-8", "betLog 未启用".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] payload = exchange.getRequestBody().readNBytes(MAX_BETLOG_CONFIG_BYTES + 1);
        if (payload.length > MAX_BETLOG_CONFIG_BYTES) {
            send(exchange, 413, "text/plain; charset=UTF-8", "Request too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String directory = URLDecoder.decode(encodedDirectory, StandardCharsets.UTF_8);
        String content = formValue(new String(payload, StandardCharsets.UTF_8), "content");
        try {
            sendJson(exchange, 200, betLog.writeConfig(directory, content));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException error) {
            send(exchange, 500, "text/plain; charset=UTF-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=UTF-8",
            mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }

    private String formValue(String body, String key) {
        for (String field : body.split("&")) {
            int separator = field.indexOf('=');
            String name = separator < 0 ? field : field.substring(0, separator);
            if (key.equals(URLDecoder.decode(name, StandardCharsets.UTF_8))) {
                return URLDecoder.decode(separator < 0 ? "" : field.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    String renderPage(HttpExchange exchange, List<CpgameLabService.GameCard> games) {
        var launchCapabilities = new LinkedHashMap<String, CpgameDemoRuntimeService.LaunchCapability>();
        var mountedDemoUrls = new LinkedHashMap<String, String>();
        Map<String, CpgameDemoRuntimeService.ProcessInfo> runningProcesses = demoRuntime == null
            ? Map.of() : demoRuntime.runningProcesses();
        for (var game : games) {
            launchCapabilities.put(game.directoryName(), demoRuntime == null
                ? new CpgameDemoRuntimeService.LaunchCapability(true, "可启动")
                : demoRuntime.launchCapability(game.directoryName()));
            String mounted = demoRuntime == null ? null : demoRuntime.mountedDemoUrl(game.directoryName());
            if (mounted == null && demoRuntime == null && game.playable()) mounted = game.demoUrl();
            mountedDemoUrls.put(game.directoryName(), mounted == null ? null : publicDemoUrl(exchange, mounted));
        }
        Context context = new Context(Locale.CHINA);
        context.setVariable("games", games);
        context.setVariable("launchCapabilities", launchCapabilities);
        context.setVariable("mountedDemoUrls", mountedDemoUrls);
        context.setVariable("runningProcesses", runningProcesses);
        context.setVariable("runningProcessCount", demoRuntime == null ? 0 : demoRuntime.runningCount());
        context.setVariable("playableCount", mountedDemoUrls.values().stream().filter(Objects::nonNull).count());
        context.setVariable("acceptedCount", games.stream().filter(CpgameLabService.GameCard::workflowAccepted).count());
        context.setVariable("artifactRoot", labService == null ? "" : labService.root().toString());
        var directories = games.stream().map(CpgameLabService.GameCard::directoryName).toList();
        var betLogCapabilities = new LinkedHashMap<String, CpgameBetLogService.BetLogCapability>();
        var betLogRuns = new LinkedHashMap<String, CpgameBetLogService.BetLogStatus>();
        if (betLog == null) {
            for (String directory : directories) {
                betLogCapabilities.put(directory, new CpgameBetLogService.BetLogCapability(
                    false, directory, null, null, null, null, "betLog 未启用", -1, -1, -1));
            }
        } else {
            betLogCapabilities.putAll(betLog.capabilities(directories));
            betLogRuns.putAll(betLog.overview(directories));
        }
        context.setVariable("labView", labView(exchange));
        context.setVariable("betLogCapabilities", betLogCapabilities);
        context.setVariable("betLogRuns", betLogRuns);
        context.setVariable("betLogRunningCount", betLog == null ? 0 : betLog.runningCount());
        context.setVariable("betLogReadyCount", betLogCapabilities.values().stream()
            .filter(CpgameBetLogService.BetLogCapability::available).count());
        return templates.process("cpgame-lab", context);
    }

    private String labView(HttpExchange exchange) {
        if (exchange == null || exchange.getRequestURI() == null) return "play";
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return "play";
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? part : part.substring(0, separator), StandardCharsets.UTF_8);
            if (!"view".equals(name)) continue;
            String value = URLDecoder.decode(separator < 0 ? "" : part.substring(separator + 1), StandardCharsets.UTF_8);
            return "betlog".equalsIgnoreCase(value) ? "betlog" : "play";
        }
        return "play";
    }

    private String publicDemoUrl(HttpExchange exchange, String value) {
        if (value == null) return "";
        if (exchange == null) return value;
        try {
            URI uri = URI.create(value);
            if (!isLoopbackHost(uri.getHost())) return value;
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            if (hostHeader == null || hostHeader.isBlank()) return value;
            URI request = URI.create("http://" + hostHeader);
            String requestHost = request.getHost();
            if (requestHost == null || requestHost.isBlank() || isLoopbackHost(requestHost)) return value;
            int publicPort = request.getPort() > 0 ? request.getPort() : exchange.getLocalAddress().getPort();
            String query = rewriteLoopbackEndpoints(uri.getQuery(), requestHost, publicPort);
            return new URI(uri.getScheme(), uri.getUserInfo(), requestHost, publicPort, uri.getPath(), query, uri.getFragment()).toString();
        } catch (IllegalArgumentException | URISyntaxException | NullPointerException ignored) {
            return value;
        }
    }

    private String rewriteLoopbackEndpoints(String query, String requestHost, int fallbackPort) {
        if (query == null || query.isBlank()) return query;
        String portSuffix = fallbackPort > 0 ? ":" + fallbackPort : "";
        return List.of(query.split("&", -1)).stream().map(parameter -> {
            int separator = parameter.indexOf('=');
            if (separator < 0) return parameter;
            String name = parameter.substring(0, separator);
            if (!("sip".equals(name) || "apiHost".equals(name))) return parameter;
            String value = parameter.substring(separator + 1);
            if (!(value.startsWith("127.0.0.1") || value.toLowerCase(Locale.ROOT).startsWith("localhost"))) return parameter;
            return name + "=" + requestHost + portSuffix;
        }).collect(Collectors.joining("&"));
    }

    private void proxyGame(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String hinted = directoryFromPlayPath(rawPath);
        String directory = gamePort(hinted) != null ? hinted : null;
        // Cookies are shared by all game tabs; prefer this request's page over the last opened game.
        if (directory == null) directory = directoryFromReferer(exchange.getRequestHeaders().getFirst("Referer"));
        if (directory == null) directory = directoryFromCookie(exchange);
        if (directory == null) directory = soleRunningDirectory();
        Integer port = gamePort(directory);
        if (port == null || port < 1) {
            send(exchange, 404, "text/plain; charset=UTF-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        proxyTo(exchange, port, directory);
    }

    void proxyTo(HttpExchange exchange, int port) throws IOException {
        proxyTo(exchange, port, directoryFromPlayPath(exchange.getRequestURI().getRawPath()));
    }

    static String rebasePlayPath(String rawPath, String directory) {
        if (rawPath == null || rawPath.isBlank()) rawPath = "/";
        if (directory == null || directory.isBlank()) return rawPath;
        String prefix = "/play/" + URLEncoder.encode(directory, StandardCharsets.UTF_8).replace("+", "%20");
        if (rawPath.equals(prefix) || rawPath.startsWith(prefix + "/")) return rawPath;
        if (rawPath.startsWith("/play/")) {
            return prefix + "/" + rawPath.substring("/play/".length());
        }
        if (!rawPath.startsWith("/")) rawPath = "/" + rawPath;
        return prefix + rawPath;
    }

    void proxyTo(HttpExchange exchange, int port, String directory) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        String rawPath = rebasePlayPath(incoming.getRawPath(), directory);
        URI upstream = URI.create("http://127.0.0.1:" + port + rawPath
            + (query == null || query.isBlank() ? "" : "?" + query));
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        HttpRequest.Builder builder = HttpRequest.newBuilder(upstream)
            .timeout(Duration.ofSeconds(30))
            .method(exchange.getRequestMethod(), requestBody.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name == null || values == null || hopByHop(name)) return;
            values.forEach(value -> builder.header(name, value));
        });
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            response.headers().map().forEach((name, values) -> {
                if (name == null || hopByHop(name) || "content-length".equalsIgnoreCase(name)) return;
                List<String> forwarded = "location".equalsIgnoreCase(name)
                    ? values.stream().map(value -> rewriteLocation(value, exchange)).toList()
                    : values;
                forwarded.forEach(value -> exchange.getResponseHeaders().add(name, value));
            });
            String method = exchange.getRequestMethod();
            boolean bodyless = "HEAD".equalsIgnoreCase(method) || status == 204 || status == 304 || status == 205;
            try (InputStream input = response.body()) {
                if (bodyless) {
                    exchange.sendResponseHeaders(status, -1);
                    return;
                }
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                exchange.sendResponseHeaders(status, length);
                try (OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            send(exchange, 502, "text/plain; charset=UTF-8", "试玩代理中断".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            if (exchange.getResponseCode() == -1) {
                send(exchange, 502, "text/plain; charset=UTF-8", "试玩页面无法连接".getBytes(StandardCharsets.UTF_8));
            } else {
                throw error instanceof IOException io ? io : new IOException(error);
            }
        }
    }

    String directoryFromPlayPath(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/play/")) return null;
        String remainder = rawPath.substring("/play/".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) return null;
        String directory = URLDecoder.decode(remainder.substring(0, slash), StandardCharsets.UTF_8);
        return safeDirectory(directory) ? directory : null;
    }

    String directoryFromCookie(HttpExchange exchange) {
        if (exchange == null) return null;
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            if (header == null || header.isBlank()) continue;
            for (String part : header.split(";")) {
                int separator = part.indexOf('=');
                if (separator <= 0) continue;
                String name = part.substring(0, separator).trim();
                if (!"cpgame-play".equals(name)) continue;
                String value = URLDecoder.decode(part.substring(separator + 1).trim(), StandardCharsets.UTF_8);
                return safeDirectory(value) ? value : null;
            }
        }
        return null;
    }

    String directoryFromReferer(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            return directoryFromPlayPath(URI.create(referer).getRawPath());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Integer gamePort(String directory) {
        if (demoRuntime == null || directory == null) return null;
        CpgameDemoRuntimeService.ProcessInfo info = demoRuntime.runningProcesses().get(directory);
        return info == null ? null : info.port();
    }

    private String soleRunningDirectory() {
        if (demoRuntime == null) return null;
        var running = demoRuntime.runningProcesses();
        return running.size() == 1 ? running.keySet().iterator().next() : null;
    }

    private void playCookie(HttpExchange exchange, String directory) {
        if (exchange == null || !safeDirectory(directory)) return;
        exchange.getResponseHeaders().add("Set-Cookie",
            "cpgame-play=" + url(directory) + "; Path=/; SameSite=Lax");
    }

    private String rewriteLocation(String location, HttpExchange exchange) {
        try {
            URI uri = URI.create(location);
            if (uri.getHost() == null || !isLoopbackHost(uri.getHost())) return location;
            return publicDemoUrl(exchange, uri.toString());
        } catch (RuntimeException ignored) {
            return location;
        }
    }

    private boolean hopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private boolean safeDirectory(String value) {
        return value != null && SAFE_DIRECTORY.matcher(value).matches();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n");
    }

    private String jsonNullable(String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    private String assetMediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    public synchronized void stop() {
        running = false;
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        server = null;
        executor = null;
    }

    public boolean isRunning() { return running; }
}
