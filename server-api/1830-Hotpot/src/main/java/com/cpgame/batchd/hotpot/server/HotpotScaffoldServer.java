package com.cpgame.batchd.hotpot.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Administrative scaffold only. It intentionally exposes no guessed game protocol endpoint. */
public final class HotpotScaffoldServer {
    private static final int RAW_GAME_ID = 1830;
    public static void main(String[] args) throws Exception {
        String host = System.getProperty("server.bind", "0.0.0.0");
        int port = Integer.getInteger("server.port", 19830);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", exchange -> send(exchange, 200, "{\"status\":\"UP\",\"scope\":\"SCAFFOLD_ONLY\"}"));
        server.createContext("/scaffold-status", exchange -> send(exchange, 200,
            "{\"gameId\":" + RAW_GAME_ID + ",\"implementationAllowed\":false,\"formalPaidRounds\":0,\"browserGate\":\"ABC224_BROWSER_CONNECTION_STOPPED_WAITING_ABC222_BACKFILL\"}"));
        server.start();
        System.out.println("Hotpot raw gid 1830 证据门禁服务监听 " + host + ":" + port + "；未开放游戏协议端点");
    }
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
