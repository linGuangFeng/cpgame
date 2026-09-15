package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoardGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class LoaderConfigurationTest {
    @TempDir Path temporary;

    private RedisDirectLoader.LoaderConfig config(String extra) throws Exception {
        Path file = temporary.resolve("generator.properties");
        Files.writeString(file, "redis.host=127.0.0.1\n" + extra);
        return RedisDirectLoader.LoaderConfig.load(file);
    }

    private String onlyH7AndRpx(int selectedRpx) {
        StringBuilder properties = new StringBuilder();
        for (String symbol : CrazyGemsBoard.SYMBOLS) {
            properties.append("generation.symbol.").append(symbol).append(".normal-weight=")
                    .append(symbol.equals("H7") ? 1 : 0).append('\n');
        }
        for (int rpx : CrazyGemsBoard.RPX_VALUES) {
            properties.append("generation.rpx.").append(rpx).append(".weight=")
                    .append(rpx == selectedRpx ? 1 : 0).append('\n');
        }
        return properties.toString();
    }

    @Test
    void configuredWeightsDriveBothEntriesAndAllSixRpxCodes() throws Exception {
        char[] codes = {'1', '2', '3', '5', 'A', 'F'};
        for (int i = 0; i < CrazyGemsBoard.RPX_VALUES.length; i++) {
            int rpx = CrazyGemsBoard.RPX_VALUES[i];
            var config = config(onlyH7AndRpx(rpx));
            var factory = new CompleteRoundFactory(config.symbolWeights(), config.rpxWeights());
            for (boolean specialEntry : new boolean[]{false, true}) {
                var round = factory.generate(new Random(58), specialEntry);
                assertEquals("777777777" + codes[i], round.member());
                assertEquals(rpx, new CompleteRoundCodec().decode(round.member()).rpx());
                assertEquals(20 * rpx, round.multiplierDeci()); // Five H7 lines pay 2x before rpx.
                assertEquals(rpx > 1, round.special());
            }
        }
    }

    @Test
    void partialConfigurationRetainsDefaultWeights() throws Exception {
        var config = config("generation.symbol.H3.normal-weight=0\ngeneration.rpx.10.weight=7\n");
        int[] symbols = CrazyGemsBoardGenerator.defaultSymbolWeights();
        int[] rpx = CrazyGemsBoardGenerator.defaultRpxWeights();
        symbols[3] = 0;
        rpx[4] = 7;
        assertArrayEquals(symbols, config.symbolWeights());
        assertArrayEquals(rpx, config.rpxWeights());
    }

    @Test
    void sharedRangeIncludesBoundariesAndLegacyCountsBecomeOneTarget() throws Exception {
        var config = config("generation.min-win-multiplier=4\ngeneration.max-win-multiplier=500\n"
                + "generation.normal-count=2\ngeneration.special-count=3\n");
        assertFalse(config.acceptsMultiplier(3));
        assertTrue(config.acceptsMultiplier(4));
        assertTrue(config.acceptsMultiplier(500));
        assertFalse(config.acceptsMultiplier(501));
        assertEquals(5, config.totalMembers());
        var explicit = config("generation.normal-count=2\ngeneration.special-count=3\n"
                + "generation.total-members=7\n");
        assertEquals(7, explicit.totalMembers());
    }

    @Test
    void sameFinalPayoutSharesCapacityAcrossRpxValues() throws Exception {
        var config = config("generation.max-members-per-multiplier=2\n");
        var counts = new HashMap<Integer, Integer>();
        var seen = new HashSet<String>();
        // Five H1 lines at rpx=1 and five H7 lines at rpx=10 both pay 20x.
        assertTrue(RedisDirectLoader.tryReserveUnique(counts, 200, config.maxMembersPerMultiplier(), seen, "1111111111"));
        assertTrue(RedisDirectLoader.tryReserveUnique(counts, 200, config.maxMembersPerMultiplier(), seen, "777777777A"));
        assertFalse(RedisDirectLoader.tryReserveUnique(counts, 200, config.maxMembersPerMultiplier(), seen, "77777777WA"));
        assertEquals(2, counts.get(200));
    }

    @Test
    void invalidWeightsCapsAndRangesFailBeforeConnecting() {
        for (String invalid : List.of(
                "generation.symbol.H1.normal-weight=-1\n",
                "generation.rpx.15.weight=-1\n",
                "generation.rpx.15.weight=2147483647\n",
                onlyH7AndRpx(0),
                "generation.max-members-per-multiplier=0\n",
                "generation.total-members=0\n",
                "generation.min-win-multiplier=10\ngeneration.max-win-multiplier=9\n")) {
            assertThrows(RuntimeException.class, () -> config(invalid));
        }
    }

    @Test
    void loaderWritesConfiguredWeightsRangeAndTrimToRedis() throws Exception {
        // A loopback RESP probe checks the real loader's wire commands; never use a configured Redis.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var received = CompletableFuture.supplyAsync(() -> receiveCommands(server));
            config("redis.port=" + server.getLocalPort() + "\n"
                    + "redis.database=0\ngeneration.clear-existing=false\n"
                    + "generation.total-members=1\n"
                    + "generation.batch-size=2\ngeneration.max-members-per-multiplier=3\n"
                    + "generation.min-win-multiplier=300\ngeneration.max-win-multiplier=300\n"
                    + onlyH7AndRpx(15));
            var summary = RedisDirectLoader.run(temporary.resolve("generator.properties"));
            assertEquals(1, summary.specialMembers());
            assertEquals(0, summary.normalMembers());
            assertEquals(1, summary.batches());
            List<List<String>> commands = received.get(5, TimeUnit.SECONDS);
            assertEquals(1, commands.stream().filter(c -> c.equals(List.of(
                    "RPUSH", "BetLog:000000058:000300", "777777777F"))).count());
            assertEquals(1, commands.stream().filter(c -> c.equals(List.of(
                    "LTRIM", "BetLog:000000058:000300", "-3", "-1"))).count());
            assertEquals(1, commands.stream().filter(c -> c.equals(List.of(
                    "ZADD", "PerKeyList_000000058", "300", "300"))).count());
            assertTrue(commands.stream().flatMap(List::stream).noneMatch(s -> s.startsWith("Mary")));
        }
    }

    @Test
    void duplicateDoesNotConsumeQuotaButAnotherRpxRemainsDistinct() {
        var counts = new HashMap<Integer, Integer>();
        var seen = new HashSet<String>();
        assertTrue(RedisDirectLoader.tryReserveUnique(counts, 200, 2, seen, "777777777A"));
        assertFalse(RedisDirectLoader.tryReserveUnique(counts, 200, 2, seen, "777777777A"));
        assertEquals(1, counts.get(200));
        assertTrue(RedisDirectLoader.tryReserveUnique(counts, 300, 2, seen, "777777777F"));
        assertTrue(RedisDirectLoader.tryReserveUnique(counts, 200, 2, seen, "77777777WA"));
        assertFalse(RedisDirectLoader.tryReserveUnique(counts, 200, 2, seen, "7777777W7A"));
        assertFalse(seen.contains("7777777W7A"), "capped candidates must not occupy memory");
        // The same set outlives every batch; flushing does not admit a previous member again.
        assertFalse(RedisDirectLoader.tryReserveUnique(counts, 300, 2, seen, "777777777F"));
        assertEquals(3, seen.size());
    }

    @Test
    void allSixRpxResultsAreUniqueAndWrittenToOneIndexAcrossBatches() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var received = CompletableFuture.supplyAsync(() -> receiveCommands(server));
            StringBuilder weights = new StringBuilder(onlyH7AndRpx(15));
            for (int rpx : CrazyGemsBoard.RPX_VALUES) weights.append("generation.rpx.").append(rpx).append(".weight=1\n");
            config("redis.port=" + server.getLocalPort() + "\nredis.database=0\n"
                    + "generation.clear-existing=false\ngeneration.total-members=6\n"
                    + "generation.batch-size=2\ngeneration.max-members-per-multiplier=3\n"
                    + "generation.min-win-multiplier=1\ngeneration.max-win-multiplier=300\n" + weights);
            var summary = RedisDirectLoader.run(temporary.resolve("generator.properties"));
            assertEquals(1, summary.normalMembers());
            assertEquals(5, summary.specialMembers());
            assertEquals(3, summary.batches());
            var commands = received.get(5, TimeUnit.SECONDS);
            var writes = commands.stream().filter(c -> c.get(0).equals("RPUSH")).toList();
            assertEquals(6, writes.size());
            assertEquals(6, writes.stream().map(c -> c.get(2)).distinct().count());
            assertTrue(writes.stream().allMatch(c -> c.get(1).startsWith("BetLog:000000058:")));
            assertTrue(commands.stream().filter(c -> c.get(0).equals("ZADD"))
                    .allMatch(c -> c.get(1).equals("PerKeyList_000000058")));
            assertTrue(commands.stream().flatMap(List::stream).noneMatch(s -> s.startsWith("Mary")));
        }
    }

    @Test
    void appendRunLoadsAllExistingResultsIntoMemory() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Map<String, List<String>> rows = Map.of(
                    "PerKeyList_000000058", List.of("20", "300"),
                    "BetLog:000000058:000020", List.of("7777777771", "7777777771"),
                    "BetLog:000000058:000300", List.of("777777777F"));
            var received = CompletableFuture.supplyAsync(() -> receiveCommands(server, rows));
            var seen = new HashSet<String>();
            try (var redis = RedisConnection.connect("127.0.0.1", server.getLocalPort(), "", "",
                    0, false, 5000, 5000)) {
                RedisDirectLoader.loadExistingMembers(redis, 58, seen);
            }
            assertEquals(2, seen.size());
            assertFalse(RedisDirectLoader.tryReserveUnique(new HashMap<>(), 300, 100, seen, "777777777F"));
            var commands = received.get(5, TimeUnit.SECONDS);
            assertTrue(commands.contains(List.of("LRANGE", "BetLog:000000058:000020", "0", "-1")));
            assertTrue(commands.contains(List.of("LRANGE", "BetLog:000000058:000300", "0", "-1")));
        }
    }

    @Test
    void clearModeCleansOldPoolsButWritesOnlyTheUnifiedPool() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var rows = Map.of("PerKeyList_000000058", List.of("20"),
                    "PerKeyList_100000058", List.of("300"), "MaryKeyList_000000058", List.of("40"));
            var received = CompletableFuture.supplyAsync(() -> receiveCommands(server, rows));
            config("redis.port=" + server.getLocalPort() + "\nredis.database=0\n"
                    + "generation.clear-existing=true\ngeneration.total-members=1\n" + onlyH7AndRpx(15));
            RedisDirectLoader.run(temporary.resolve("generator.properties"));
            var commands = received.get(5, TimeUnit.SECONDS);
            var deleted = commands.stream().filter(c -> c.get(0).equals("DEL")).findFirst().orElseThrow();
            assertTrue(deleted.containsAll(List.of("PerKeyList_000000058", "PerKeyList_100000058",
                    "MaryKeyList_000000058", "BetLog:000000058:000020", "BetLog:100000058:000300",
                    "MaryLog:000000058:000040")));
            assertTrue(commands.stream().filter(c -> c.get(0).equals("RPUSH"))
                    .allMatch(c -> c.get(1).startsWith("BetLog:000000058:")));
        }
    }

    private static List<List<String>> receiveCommands(ServerSocket server) {
        return receiveCommands(server, Map.of());
    }

    private static List<List<String>> receiveCommands(ServerSocket server, Map<String, List<String>> rows) {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var output = socket.getOutputStream();
            List<List<String>> commands = new ArrayList<>();
            boolean transaction = false;
            int queued = 0;
            String header;
            while ((header = input.readLine()) != null) {
                int count = Integer.parseInt(header.substring(1));
                List<String> command = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    input.readLine(); // Bulk length; all test commands are ASCII without newlines.
                    command.add(input.readLine());
                }
                commands.add(command);
                String response;
                if (command.get(0).equals("MULTI")) {
                    transaction = true;
                    queued = 0;
                    response = "+OK\r\n";
                } else if (command.get(0).equals("EXEC")) {
                    response = "*" + queued + "\r\n" + ":1\r\n".repeat(queued);
                    transaction = false;
                } else if (transaction) {
                    queued++;
                    response = "+QUEUED\r\n";
                } else if (command.get(0).equals("PING")) {
                    response = "+PONG\r\n";
                } else if (command.get(0).equals("DEL")) {
                    response = ":" + (command.size() - 1) + "\r\n";
                } else if (command.get(0).equals("ZRANGE") || command.get(0).equals("LRANGE")) {
                    List<String> values = rows.getOrDefault(command.get(1), List.of());
                    var encoded = new StringBuilder("*" + values.size() + "\r\n");
                    for (String value : values) encoded.append('$').append(value.length()).append("\r\n")
                            .append(value).append("\r\n");
                    response = encoded.toString();
                } else {
                    throw new IllegalStateException("Unexpected Redis command: " + command);
                }
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
            return commands;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
