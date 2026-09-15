package com.cpgame.admin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpgameDemoRoutingTest {
    @TempDir Path root;

    @Test void requestsFromAnOlderGameTabIgnoreTheOtherTabsCookie() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        AdminSettings settings = new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", port);
        ObjectMapper mapper = new ObjectMapper();
        int upstreamPort = upstream.getAddress().getPort();
        CpgameDemoRuntimeService demos = new CpgameDemoRuntimeService(settings, mapper) {
            @Override public Map<String, ProcessInfo> runningProcesses() {
                return Map.of(
                    "2-Jungle-Kings", new ProcessInfo("2-Jungle-Kings", 0, upstreamPort, Instant.EPOCH, ""),
                    "8-Jurassic-Jungle", new ProcessInfo("8-Jurassic-Jungle", 0, upstreamPort, Instant.EPOCH, ""));
            }
        };
        CpgamePublicLabServer server = new CpgamePublicLabServer(null, demos, null, settings, mapper);
        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String origin = "http://127.0.0.1:" + port;
            for (String path : new String[]{"/assets/game.js", "/cp/single_game.Game/initRoom"}) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(origin + path))
                    .header("Cookie", "cpgame-play=8-Jurassic-Jungle")
                    .header("Referer", origin + "/play/2-Jungle-Kings/index.html").GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("/play/2-Jungle-Kings" + path, response.body());
            }
        } finally {
            server.stop();
            upstream.stop(0);
        }
    }
}
