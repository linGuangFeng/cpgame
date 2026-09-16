package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoardGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ScatterRoundWalkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void oneClaimWalksPaidAndAllFreeSpins() throws Exception {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        CompleteRoundFactory.GeneratedRound generated = null;
        Random random = new Random(1830L);
        int[] opening = RedisDirectLoader.specialEntryOpeningWeights(HotpotBoardGenerator.defaultPaidStartWeights());
        for (int i = 0; i < 4000 && generated == null; i++) {
            try {
                CompleteRoundFactory.GeneratedRound candidate = factory.generate(random, 10, 30, opening,
                        HotpotBoardGenerator.defaultCascadeWeights(), HotpotBoardGenerator.defaultFreeStartWeights());
                if (candidate.kind() == HotpotRoundKind.SCATTER_FREE_SPINS && candidate.multiplier() > 0) {
                    generated = candidate;
                }
            } catch (CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        assertNotNull(generated, "could not construct a scatter complete round for the walk test");
        FakeRedisCommands fake = new FakeRedisCommands();
        fake.seed(true, generated.multiplier(), codec.encode(generated.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        RedisRoundStore.ClaimedRound claimed = store.claim(new AlwaysWinRandom());
        assertEquals(HotpotRoundKind.SCATTER_FREE_SPINS, claimed.kind());
        Properties config = new Properties();
        config.setProperty("session.initial-balance", "1000.00");
        HotpotController controller = new HotpotController(Path.of("D:/work/hd/cpgame/publish/1830-Hotpot"),
                config, store, new AlwaysWinRandom());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                var method = HotpotController.class.getDeclaredMethod("handle", com.sun.net.httpserver.HttpExchange.class);
                method.setAccessible(true);
                method.invoke(controller, exchange);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonNode paid = post(client, base + "/cp/single_game.Game/gameResult",
                    "token=scatter-walk&bet_gold=0.02&level=1&gid=1830");
            assertTrue(paid.path("data").path("frees").path("st").asInt() > 0);
            assertEquals(1, paid.path("data").path("type").asInt());
            assertNull(HotpotClientRoundWalk.hangReason(paid.path("data")),
                    HotpotClientRoundWalk.hangReason(paid.path("data")));
            int steps = 1;
            JsonNode current = paid;
            while (current.path("data").path("frees").path("st").asInt() > 0) {
                current = post(client, base + "/cp/single_game.Game/gameResult",
                        "token=scatter-walk&bet_gold=0.02&level=1&gid=1830");
                assertEquals(2, current.path("data").path("type").asInt());
                assertNull(HotpotClientRoundWalk.hangReason(current.path("data")),
                        HotpotClientRoundWalk.hangReason(current.path("data")));
                steps++;
                if (steps == 4) {
                    JsonNode resumed = post(client, base + "/cp/single_game.Game/initRoom",
                            "token=scatter-walk&gid=1830");
                    assertTrue(resumed.path("data").path("_resumeAvailable").asBoolean());
                    assertEquals(1, resumed.path("data").path("type").asInt());
                    assertEquals(4, resumed.path("data").path("resumeNextDelivery").asInt());
                    assertEquals(0, resumed.path("data").path("frees").path("st").asInt());
                    JsonNode replay = post(client, base + "/cp/single_game.Game/gameResult",
                            "token=scatter-walk&bet_gold=0.02&level=1&gid=1830");
                    assertTrue(replay.path("data").path("_resume").asBoolean());
                    assertEquals(1, replay.path("data").path("type").asInt());
                    assertEquals(4, replay.path("data").path("resumeNextDelivery").asInt());
                }
                assertTrue(steps <= 31);
            }
            assertEquals(generated.fact().spins().size(), steps);
            JsonNode history = post(client, base + "/cp/goldgame/single_game_user_history",
                    "token=scatter-walk&page=1&page_size=30&gid=1830");
            assertEquals(steps, history.path("data").path("list").get(0).path("results").size());
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode post(HttpClient client, String url, String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static final class AlwaysWinRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return true; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public long nextLong(long origin, long bound) { return bound - 1; }
        @Override public long nextLong(long bound) { return 0; }
    }
}
