package com.hd.cpgame.magicscroll2.api;

import com.hd.cpgame.magicscroll2.api.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class StaticFileHandler implements HttpHandler {
    private static final Map<String, String> MIME = new HashMap<String, String>();
    static {
        MIME.put("html", "text/html; charset=utf-8"); MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8"); MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png"); MIME.put("jpg", "image/jpeg"); MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp"); MIME.put("mp3", "audio/mpeg"); MIME.put("ogg", "audio/ogg");
        MIME.put("wav", "audio/wav"); MIME.put("ttf", "font/ttf"); MIME.put("bin", "application/octet-stream");
    }
    private final File root;
    private final String rootPrefix;

    public StaticFileHandler(File root) throws Exception {
        this.root = root.getCanonicalFile();
        this.rootPrefix = this.root.getPath() + File.separator;
        if (!new File(this.root, "index.html").isFile()) {
            throw new IllegalArgumentException("publish directory has no root index.html: " + root);
        }
    }

    @Override public void handle(HttpExchange exchange) {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { HttpResponses.options(exchange); return; }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpResponses.error(exchange, 405, "METHOD_NOT_ALLOWED", "GET or HEAD is required"); return;
            }
            String path = URLDecoder.decode(exchange.getRequestURI().getPath(), "UTF-8");
            if (path.equals("/")) path = "/index.html";
            File file = new File(root, path.substring(1)).getCanonicalFile();
            if ((!file.getPath().startsWith(rootPrefix) && !file.equals(root)) || !file.isFile()) {
                HttpResponses.error(exchange, 404, "NOT_FOUND", "static resource not found"); return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String mime = dot < 0 ? "application/octet-stream" : MIME.get(name.substring(dot + 1).toLowerCase());
            exchange.getResponseHeaders().set("Content-Type", mime == null ? "application/octet-stream" : mime);
            exchange.getResponseHeaders().set("Cache-Control", name.equals("index.html") ? "no-cache" : "public, max-age=3600");
            HttpResponses.cors(exchange);
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            } else exchange.close();
        } catch (Exception e) {
            try { HttpResponses.error(exchange, 500, "STATIC_ERROR", e.getMessage()); } catch (Exception ignored) { exchange.close(); }
        }
    }
}
