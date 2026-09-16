package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerApiTest {
    @Test
    void authConfigSpinHistoryResumeOnOriginalPublish() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        fake.seed(false, 0, TestMembers.lossMember());
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        LuckyPandaService service = new LuckyPandaService(store, new BigDecimal("1000.00"), new AlwaysLossRandom());
        Path publish = Path.of("D:/work/hd/cpgame/publish/41-Lucky-Panda");
        LuckyPandaController controller = new LuckyPandaController(publish, service, store);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", controller::handle);
        server.start();
        int port = server.getAddress().getPort();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + port;
            HttpResponse<String> home = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(home.statusCode() == 200 || home.statusCode() == 302);
            HttpResponse<String> index = client.send(HttpRequest.newBuilder(URI.create(base + "/index.html")).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("<html"));
            assertFalse(index.body().contains("game-shell"));
            assertFalse(index.body().contains("data-requested-scenario"));

            String auth = post(client, base + "/cp/api/v1/auth/verify", "gid=41&t=api-test");
            assertTrue(auth.contains("\"code\":200"));
            assertTrue(auth.contains("\"token\":\"api-test\""));
            assertEquals("5f8142ce67bb887905edb625ebfb90128fa872cc0de8e9af0f8017debc2dc50c", GameRuleCore.RULES_HASH);

            String config = post(client, base + "/cp/api/v1/lucky-panda/config", "gid=41&t=api-test");
            assertTrue(config.contains("\"bsl\":[0.02,0.2]"));
            assertTrue(config.contains("\"bll\":[1,2,3,4,5,6,7,8,9,10]"));
            assertTrue(config.contains("\"cs\":\"R$\""));
            assertTrue(config.contains("\"Pan\""));

            String spin = post(client, base + "/cp/api/v1/lucky-panda/spin",
                    "gid=41&t=api-test&bs=0.02&bl=1", "same-key");
            assertTrue(spin.contains("\"code\":200"));
            assertTrue(spin.contains("\"ba\":0.40") || spin.contains("\"ba\":0.4"));
            assertTrue(spin.contains("\"ss\":1"));
            assertTrue(spin.contains("\"wa\":0.00") || spin.contains("\"wa\":0"));
            assertTrue(spin.contains("redis-db15-complete-round"));
            assertTrue(spin.contains("\"pb\":\"999.60\"") || spin.contains("\"pb\":\"999.6\""));

            String replay = post(client, base + "/cp/api/v1/lucky-panda/spin",
                    "gid=41&t=api-test&bs=0.02&bl=1", "same-key");
            assertEquals(spin, replay);

            String history = post(client, base + "/cp/api/v1/lucky-panda/log-list",
                    "gid=41&t=api-test&page_index=1");
            assertTrue(history.contains("\"lc\":1"));
            Matcher transfer = Pattern.compile("\"tis\":\"([^\"]+)\"").matcher(history);
            assertTrue(transfer.find());
            String detail = post(client, base + "/cp/api/v1/lucky-panda/log-view",
                    "gid=41&t=api-test&transfer_id=" + transfer.group(1));
            assertTrue(detail.contains("\"bsl\""));
            assertFalse(detail.contains("\"fsl\""));

            String ping = post(client, base + "/cp/api/v1/ping", "gid=41&t=api-test");
            assertTrue(ping.contains("\"code\":200"));

            fake.command("LPOP", "BetLog:000000041:000000");
            String empty = post(client, base + "/cp/api/v1/lucky-panda/spin", "gid=41&t=api-test&bs=0.02&bl=1");
            assertTrue(empty.contains("\"code\":503"));
            assertTrue(empty.toLowerCase().contains("empty") || empty.toLowerCase().contains("cache"));
        } finally {
            server.stop(0);
        }
    }

    private static String post(HttpClient client, String url, String form) throws Exception {
        return post(client, url, form, null);
    }

    private static String post(HttpClient client, String url, String form, String idempotency) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "http://127.0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static final class AlwaysLossRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return false; }
        @Override public int nextInt(int bound) { return 0; }
    }
}
