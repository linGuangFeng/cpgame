package com.cpgame.luckywheel.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class StaticFileHandler implements HttpHandler {
    private final Path root;
    public StaticFileHandler(Path root) { this.root = root.toAbsolutePath().normalize(); }
    @Override public void handle(HttpExchange exchange) throws IOException {
        LuckyWheelController.addCors(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); exchange.close(); return;
        }
        String requested = exchange.getRequestURI().getPath();
        if (requested.equals("/")) requested = "/index.html";
        Path file = root.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        exchange.getResponseHeaders().set("Content-Type", contentType(name));
        if (name.equals("index.html") || name.equals("versionconfig.js")) exchange.getResponseHeaders().set("Cache-Control", "no-store");
        long length = Files.size(file);
        exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) Files.copy(file, exchange.getResponseBody());
        exchange.close();
    }
    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
