package com.cpgame.admin;

import com.sun.net.httpserver.HttpExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Shared-JVM compatibility mount for Freedom Day's pre-controller delivery.
 *
 * <p>The old delivery had a Python HTTP adapter but already contained the authoritative Java
 * rule core. This mount calls that core in-process and exposes the original HTTP contract through
 * the one shared listener. It never starts Python, another JVM, or another port.</p>
 */
final class CpgameFreedomDayControllerMount implements AutoCloseable {
    static final String DIRECTORY = "1809-Freedom-Day";
    private static final String GENERATOR_JAR = "generator/1809-Freedom-Day/dist/freedom-day-redis-loader.jar";
    private static final String FACTORY_CLASS = "com/cpgame/replica/freedomday/CompleteRoundFactory.class";
    private static final String PROTOCOL_CLASS = "com/cpgame/replica/freedomday/FreedomDayRoundProtocolCli.class";
    private static final List<String> REQUIRED_FIXTURES = List.of(
        "mock-init.json", "mock-config.json", "mock-user.json", "replay-results.json");

    private final Path fixtures;
    private final URLClassLoader loader;
    private final Object factory;
    private final Method generate;
    private final Method fact;
    private final Method protocol;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom seeds = new SecureRandom();
    private final Deque<ObjectNode> pending = new ArrayDeque<>();
    private final List<ObjectNode> runtimeHistory = new ArrayList<>();
    private BigDecimal balance;

    static boolean supports(Path root, String directory) {
        if (!DIRECTORY.equals(directory)) return false;
        Path jar = root.resolve(GENERATOR_JAR);
        Path fixtureRoot = root.resolve("fixtures").resolve(directory);
        if (!Files.isRegularFile(jar) || !Files.isDirectory(fixtureRoot)) return false;
        if (REQUIRED_FIXTURES.stream().anyMatch(name -> !Files.isRegularFile(fixtureRoot.resolve(name)))) {
            return false;
        }
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.getEntry(FACTORY_CLASS) != null && archive.getEntry(PROTOCOL_CLASS) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    static CpgameFreedomDayControllerMount start(Path root, String directory) throws Exception {
        if (!supports(root, directory)) {
            throw new IllegalArgumentException("Freedom Day 共享试玩依赖的 Java Core 或协议 fixtures 不完整");
        }
        return new CpgameFreedomDayControllerMount(root);
    }

    private CpgameFreedomDayControllerMount(Path root) throws Exception {
        fixtures = root.resolve("fixtures").resolve(DIRECTORY);
        Path jar = root.resolve(GENERATOR_JAR);
        loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Class<?> factoryType = Class.forName(
            "com.cpgame.replica.freedomday.CompleteRoundFactory", true, loader);
        Class<?> factType = Class.forName(
            "com.cpgame.replica.freedomday.CompleteRoundFact", true, loader);
        Class<?> cliType = Class.forName(
            "com.cpgame.replica.freedomday.FreedomDayRoundProtocolCli", true, loader);
        factory = factoryType.getConstructor().newInstance();
        generate = factoryType.getMethod("generate", Random.class, boolean.class, int.class, int.class);
        fact = generate.getReturnType().getMethod("fact");
        protocol = cliType.getDeclaredMethod("protocol", factType, BigDecimal.class, int.class, long.class);
        protocol.setAccessible(true);
        balance = fixture("mock-user.json").path("data").path("gold").decimalValue();
    }

    void dispatch(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            respond(exchange, 204, new byte[0]);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "只允许 POST"));
            return;
        }
        Map<String, String> form = parameters(exchange);
        String endpoint = exchange.getRequestURI().getPath();
        try {
            ObjectNode response;
            synchronized (this) {
                response = switch (endpoint) {
                    case "/cp/single_game.Game/initRoom" -> initRoom();
                    case "/cp/config/initialData" -> initialData(form);
                    case "/cp/account/getUserInfo" -> userInfo();
                    case "/cp/activity/getActivity" -> activity();
                    case "/cp/goldgame/single_game_user_gold_history" -> history(form, false);
                    case "/cp/goldgame/single_game_user_history" -> history(form, true);
                    case "/cp/single_game.Game/gameResult" -> gameResult(form);
                    default -> error(404, "Unknown local endpoint: " + endpoint);
                };
            }
            sendJson(exchange, response.path("code").asInt() == 404 ? 404 : 200, response);
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, error(400, "Invalid demo request: " + concise(error)));
        } catch (Exception error) {
            sendJson(exchange, 500, error(500, "Freedom Day Java Core failed: " + concise(error)));
        }
    }

    private ObjectNode initRoom() throws IOException {
        pending.clear();
        ObjectNode response = fixture("mock-init.json");
        object(response, "data").put("end_gold", money(balance));
        return response;
    }

    private ObjectNode initialData(Map<String, String> form) throws IOException {
        ObjectNode response = fixture("mock-config.json");
        String language = form.get("language");
        if (language != null && !language.isBlank()) {
            object(response, "data").put("language", "pt-br".equalsIgnoreCase(language) ? "pt-pt" : language);
        }
        return response;
    }

    private ObjectNode userInfo() throws IOException {
        ObjectNode response = fixture("mock-user.json");
        object(response, "data").put("gold", money(balance));
        return response;
    }

    private ObjectNode activity() {
        ObjectNode response = mapper.createObjectNode().put("code", 0);
        ObjectNode free = mapper.createObjectNode().put("invite_act_have", 0).put("invite_end_time", 0);
        free.putArray("act_list");
        response.putObject("data").set("free", free);
        return response;
    }

    private ObjectNode gameResult(Map<String, String> form) throws Exception {
        BigDecimal betSize = decimal(form.getOrDefault("bet_gold", "0.01"));
        int level = integer(form.getOrDefault("level", "1"));
        boolean featureBuy = "3".equals(form.get("bet_type"));
        if (betSize.signum() <= 0 || level <= 0) throw new IllegalArgumentException("bet_gold 和 level 必须大于 0");
        ObjectNode frame = nextFrame(betSize, level, featureBuy);
        ObjectNode settled = settle(frame);
        runtimeHistory.add(settled.deepCopy());
        ObjectNode response = mapper.createObjectNode().put("code", 0);
        response.set("data", settled);
        return response;
    }

    private ObjectNode nextFrame(BigDecimal betSize, int level, boolean featureBuy) throws Exception {
        if (!featureBuy && !pending.isEmpty()) return pending.removeFirst();
        pending.clear();
        long seed = seeds.nextLong();
        Object generated;
        for (;;) {
            try {
                generated = generate.invoke(factory, new Random(seed), featureBuy, 10, 30);
                break;
            } catch (InvocationTargetException error) {
                Throwable cause = error.getTargetException();
                if (cause.getClass().getSimpleName().equals("RoundRejectedException")) {
                    seed = seeds.nextLong();
                    continue;
                }
                throw error;
            }
        }
        Object completeRound = fact.invoke(generated);
        BigDecimal unitBet = betSize.multiply(BigDecimal.valueOf(level));
        String json = (String) protocol.invoke(null, completeRound, unitBet, level, seed);
        JsonNode frames = mapper.readTree(json);
        if (!(frames instanceof ArrayNode array) || array.isEmpty()) {
            throw new IllegalStateException("Java Core 返回了空的完整 Round");
        }
        for (JsonNode item : array) pending.addLast(((ObjectNode) item).deepCopy());
        return pending.removeFirst();
    }

    private ObjectNode settle(ObjectNode source) {
        ObjectNode result = source.deepCopy();
        BigDecimal unitBet = result.remove("_unit_bet").decimalValue();
        boolean featureBuy = result.remove("_feature_buy").asBoolean();
        int freeIndex = result.remove("_free_index").asInt();
        int freeTotal = result.remove("_free_total").asInt();
        BigDecimal cumulative = result.remove("_cumulative_free_win").decimalValue();
        int endingMultiplier = result.remove("_ending_multiplier").asInt();
        result.remove("_awarded_free_spins");
        JsonNode seed = result.remove("_seed");
        String rulesVersion = result.remove("_rulesVersion").asText();
        String rulesHash = result.remove("_rulesHash").asText();

        BigDecimal charged = freeIndex == 0
            ? unitBet.multiply(BigDecimal.valueOf(20L * (featureBuy ? 75L : 1L)))
            : BigDecimal.ZERO;
        BigDecimal spinWin = result.path("total_win").decimalValue();
        BigDecimal start = balance;
        balance = money(balance.subtract(charged).add(spinWin));

        result.put("order_id", result.path("oid").asText());
        result.put("bet_gold", money(charged));
        result.put("change_gold", money(spinWin.subtract(charged)));
        result.put("start_gold", money(start));
        result.put("end_gold", balance);
        result.put("odds", charged.signum() == 0 ? BigDecimal.ZERO
            : spinWin.divide(charged, 6, RoundingMode.HALF_UP));
        result.putObject("extend")
            .put("act_bet_gold", 0)
            .put("act_id", "0")
            .put("bet_type", featureBuy ? 3 : 0);
        result.putObject("frees")
            .put("tt", freeTotal)
            .put("st", freeTotal == 0 ? 0 : Math.max(0, freeTotal - freeIndex))
            .put("twa", cumulative)
            .put("lwa", freeIndex == 0 ? BigDecimal.ZERO : spinWin)
            .put("m", endingMultiplier)
            .put("ba", money(unitBet.multiply(BigDecimal.valueOf(20L * (featureBuy ? 75L : 1L)))))
            .put("bet", unitBet);
        result.put("_source", "shared-java-core");
        result.put("_rulesVersion", rulesVersion);
        result.put("_rulesHash", rulesHash);
        if (seed != null) result.set("_seed", seed);
        return result;
    }

    private ObjectNode history(Map<String, String> form, boolean detail) throws IOException {
        int page = Math.max(1, integer(form.getOrDefault("page", "1")));
        int pageSize = Math.max(1, integer(form.getOrDefault("page_size", "30")));
        List<ObjectNode> source = new ArrayList<>();
        if (runtimeHistory.isEmpty()) {
            JsonNode replay = fixtureNode("replay-results.json");
            if (replay instanceof ArrayNode array) {
                for (JsonNode item : array) if (item instanceof ObjectNode object) source.add(object);
            }
        } else {
            source.addAll(runtimeHistory);
        }
        java.util.Collections.reverse(source);
        BigDecimal totalBet = source.stream().map(item -> item.path("bet_gold").decimalValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalChange = source.stream().map(item -> item.path("change_gold").decimalValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        ObjectNode response = mapper.createObjectNode().put("code", 0);
        ObjectNode data = response.putObject("data");
        ArrayNode rows = data.putArray("list");
        if (!source.isEmpty()) {
            long day = source.getFirst().path("time").asLong(System.currentTimeMillis() / 1000L);
            day = day / 86400L * 86400L;
            if (!detail) {
                if (page == 1) rows.addObject().put("day", day)
                    .put("bet_gold", money(totalBet)).put("change_gold", money(totalChange));
            } else {
                int start = Math.min(source.size(), (page - 1) * pageSize);
                int end = Math.min(source.size(), start + pageSize);
                for (ObjectNode item : source.subList(start, end)) {
                    ObjectNode row = rows.addObject();
                    row.put("order_id", item.path("order_id").asText(item.path("oid").asText()));
                    row.put("time", item.path("time").asLong(day));
                    row.put("bet", item.path("bet_gold").decimalValue());
                    row.put("bet_gold", item.path("bet_gold").decimalValue());
                    row.put("change_gold", item.path("change_gold").decimalValue());
                    row.put("type", 1);
                    row.putObject("extend");
                    ObjectNode spin = row.putArray("results").addObject();
                    spin.put("time", item.path("time").asLong(day));
                    spin.put("end_gold", item.path("end_gold").decimalValue());
                    spin.put("level", item.path("level").asInt(1));
                    spin.put("change_gold", item.path("change_gold").decimalValue());
                    spin.put("bet_gold", item.path("bet_gold").decimalValue());
                    spin.set("result", item.path("props").deepCopy());
                }
            }
        }
        data.putObject("statistics")
            .put("total_bet_gold", money(totalBet))
            .put("total_change_gold", money(totalChange));
        return response;
    }

    private ObjectNode fixture(String name) throws IOException {
        JsonNode value = fixtureNode(name);
        if (!(value instanceof ObjectNode object)) throw new IOException("试玩 fixture 不是 JSON 对象：" + name);
        return object.deepCopy();
    }

    private JsonNode fixtureNode(String name) throws IOException {
        return mapper.readTree(Files.readString(fixtures.resolve(name), StandardCharsets.UTF_8));
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value instanceof ObjectNode object) return object;
        return parent.putObject(field);
    }

    private static Map<String, String> parameters(HttpExchange exchange) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        decode(exchange.getRequestURI().getRawQuery(), result);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (contentType == null || contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
            decode(new String(body, StandardCharsets.UTF_8), result);
        }
        return result;
    }

    private static void decode(String raw, Map<String, String> target) {
        if (raw == null || raw.isBlank()) return;
        for (String item : raw.split("&")) {
            int equals = item.indexOf('=');
            String name = equals < 0 ? item : item.substring(0, equals);
            String value = equals < 0 ? "" : item.substring(equals + 1);
            target.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
    }

    private ObjectNode error(int code, String message) {
        return mapper.createObjectNode().put("code", code).put("msg", message);
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode value) throws IOException {
        respond(exchange, status, mapper.writeValueAsBytes(value));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length == 0) {
            exchange.close();
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }

    private static BigDecimal decimal(String value) {
        try { return new BigDecimal(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("数字格式无效：" + value, error); }
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("整数格式无效：" + value, error); }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String concise(Throwable error) {
        Throwable value = error;
        while (value instanceof InvocationTargetException && value.getCause() != null) value = value.getCause();
        String message = value.getMessage();
        return message == null || message.isBlank() ? value.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        pending.clear();
        runtimeHistory.clear();
        try { loader.close(); }
        catch (IOException ignored) { }
    }
}
