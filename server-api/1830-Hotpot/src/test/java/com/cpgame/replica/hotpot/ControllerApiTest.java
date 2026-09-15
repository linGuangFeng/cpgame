package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotIndependentLossGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ControllerApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void initConfigSpinHistoryResume() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        fake.seed(false, 0, codec.encode(lossFact()));
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        Properties config = new Properties();
        config.setProperty("session.initial-balance", "1000.00");
        Path publish = Path.of("D:/work/hd/cpgame/publish/1830-Hotpot");
        HotpotController controller = new HotpotController(publish, config, store, new AlwaysLossRandom());
        HttpServer server = bindFree();
        int port = server.getAddress().getPort();
        server.createContext("/", invoke(controller));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + port;
            JsonNode init = post(client, base + "/cp/single_game.Game/initRoom", "token=api-test&gid=1830");
            assertEquals(0, init.path("code").asInt());
            assertEquals(1830, new HotpotSpinProjector().core().rawGameId());
            assertTrue(init.path("data").path("prop_odds").isArray());
            JsonNode cfg = post(client, base + "/cp/config/initialData", "language=pt-br&gid=1830");
            assertEquals("pt-pt", cfg.path("data").path("language").asText());
            assertEquals(1830, cfg.path("data").path("game_info").path("gid").asInt());
            JsonNode user = post(client, base + "/cp/account/getUserInfo", "token=api-test");
            assertEquals(1000.0d, user.path("data").path("gold").asDouble(), 0.001);
            JsonNode spin = post(client, base + "/cp/single_game.Game/gameResult",
                    "token=api-test&bet_gold=0.02&level=1&gid=1830");
            assertEquals(0, spin.path("code").asInt());
            assertEquals(0.0d, spin.path("data").path("total_win").asDouble(), 0.0001);
            assertEquals(0.4d, spin.path("data").path("bet_gold").asDouble(), 0.0001);
            assertEquals(999.6d, spin.path("data").path("end_gold").asDouble(), 0.0001);
            assertEquals("redis-db15-complete-round", spin.path("data").path("_source").asText());
            JsonNode resume = post(client, base + "/cp/single_game.Game/initRoom", "token=api-test&gid=1830");
            assertEquals(spin.path("data").path("oid").asLong(), resume.path("data").path("oid").asLong());
            JsonNode gold = post(client, base + "/cp/goldgame/single_game_user_gold_history",
                    "token=api-test&page=1&page_size=30&gid=1830");
            assertEquals(1, gold.path("data").path("list").size());
            JsonNode day = post(client, base + "/cp/goldgame/single_game_user_history",
                    "token=api-test&page=1&page_size=30&gid=1830");
            assertEquals(1, day.path("data").path("list").size());
            assertEquals(1, day.path("data").path("list").get(0).path("results").size());
            HttpResponse<String> buy = postStatus(client, base + "/cp/single_game.Game/gameResult",
                    "token=api-test&bet_gold=0.02&level=1&bet_type=3");
            assertEquals(400, buy.statusCode());
            HttpResponse<String> home = client.send(HttpRequest.newBuilder(URI.create(base + "/index.html")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, home.statusCode());
            assertTrue(home.body().contains("<html"));
            assertFalse(home.body().contains("game-shell"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer bindFree() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static final class AlwaysLossRandom extends java.security.SecureRandom {
        @Override public boolean nextBoolean() { return false; }
        @Override public int nextInt(int bound) { return 0; }
    }

    private static CompleteRoundFact lossFact() {
        return new CompleteRoundFact(CompleteRoundFact.VERSION, List.of(List.of(
                CompleteRoundFact.fromBoard(new HotpotIndependentLossGenerator().generate(new Random(7L))))));
    }

    private static com.sun.net.httpserver.HttpHandler invoke(HotpotController controller) {
        return exchange -> {
            try {
                var method = HotpotController.class.getDeclaredMethod("handle", com.sun.net.httpserver.HttpExchange.class);
                method.setAccessible(true);
                method.invoke(controller, exchange);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };
    }

    private static JsonNode post(HttpClient client, String url, String body) throws Exception {
        HttpResponse<String> response = postStatus(client, url, body);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> postStatus(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
