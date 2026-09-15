package com.cpgame.luckydragon.api;

import com.cpgame.luckydragon.core.GameRuleCore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Black-box acceptance over the machine's non-loopback LAN address. */
public final class LanAcceptanceTestMain {
    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern TRANSFER = Pattern.compile("\\\"tis\\\":\\\"([^\\\"]+)\\\"");

    private LanAcceptanceTestMain() { }

    public static void main(String[] args) throws Exception {
        String base = args.length == 0 ? "http://192.168.24.37:50042" : args[0];
        URI baseUri = URI.create(base);
        String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
        String referer = baseUri.resolve("/play/42-Lucky-Dragon/index.html?gid=42&l=en").toString();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        Map<String,Integer> staticPayloads = new LinkedHashMap<>();
        for (String path : List.of(
            "/play/42-Lucky-Dragon/index.html?gid=42&l=en",
            "/play/42-Lucky-Dragon/versionconfig.js",
            "/play/0/versionconfig.js",
            "/play/asset/versionconfig.js",
            "/play/asset/cocos2d-js-min.55e56.js",
            "/play/42-Lucky-Dragon/src/settings.4f29a.js",
            "/play/42-Lucky-Dragon/assets/main/config.d68a4.json")) {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            require(response.statusCode() == 200 && response.body().length > 0, "static asset failed: " + path);
            staticPayloads.put(path, response.body().length);
        }

        HttpResponse<String> status = client.send(HttpRequest.newBuilder(baseUri.resolve("/__controller/status"))
            .header("Referer", referer).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(status.statusCode() == 200 && status.body().contains("\"gameId\":42")
            && status.body().contains(GameRuleCore.RULES_HASH), "managed status mismatch");

        HttpResponse<String> auth = post(client, baseUri, origin, "/cp/api/v1/auth/verify", Map.of("gid", "42"), Map.of());
        require(auth.statusCode() == 200 && auth.body().contains("\"code\":200"), "auth failed");
        require(origin.equals(auth.headers().firstValue("Access-Control-Allow-Origin").orElse(null)), "LAN CORS mismatch");
        String token = match(TOKEN, auth.body(), "auth token");

        HttpResponse<String> config = post(client, baseUri, origin, "/cp/api/v1/lucky-dragon/config",
            Map.of("gid", "42", "t", token), Map.of());
        require(config.body().contains("\"dbs\":0.5") && config.body().contains("\"bsl\":[0.5,5,20]")
            && config.body().contains("\"bll\":[1,2,3,4,5,6,7,8,9,10]"), "min bet/config mismatch: " + config.body());

        Map<String,String> spinForm = Map.of("gid", "42", "t", token, "bs", "0.5", "bl", "1");
        Map<String,String> idempotency = Map.of("Idempotency-Key", "lan-gid42-round-1");
        HttpResponse<String> spin = post(client, baseUri, origin, "/cp/api/v1/lucky-dragon/spin", spinForm, idempotency);
        HttpResponse<String> replay = post(client, baseUri, origin, "/cp/api/v1/lucky-dragon/spin", spinForm, idempotency);
        require(spin.statusCode() == 200 && spin.body().equals(replay.body()) && spin.body().contains("\"ba\":0.5"),
            "spin/idempotency failed");

        HttpResponse<String> history = post(client, baseUri, origin, "/cp/api/v1/lucky-dragon/log-list",
            Map.of("gid", "42", "t", token, "page_index", "1"), Map.of());
        require(history.body().contains("\"lc\":1") && history.body().contains("\"end\":1"), "history list mismatch");
        String transferId = match(TRANSFER, history.body(), "history transfer id");
        HttpResponse<String> detail = post(client, baseUri, origin, "/cp/api/v1/lucky-dragon/log-view",
            Map.of("gid", "42", "t", token, "transfer_id", transferId), Map.of());
        require(detail.body().contains("\"terminal\":true") && detail.body().contains("\"deliveryIndex\":0")
            && detail.body().contains("\"bet_size\":0.5") && detail.body().contains("\"bet_level\":1"),
            "history detail/complete-Round mismatch");

        HttpRequest preflight = HttpRequest.newBuilder(baseUri.resolve("/cp/api/v1/lucky-dragon/spin"))
            .header("Origin", origin).header("Referer", referer).header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> options = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        require(options.statusCode() == 204
            && origin.equals(options.headers().firstValue("Access-Control-Allow-Origin").orElse(null)), "preflight failed");

        for (String language : List.of("bn","en","es","fr","id","ko","pt","th","tr","vi")) {
            HttpResponse<byte[]> localizedEntry = client.send(HttpRequest.newBuilder(baseUri.resolve(
                "/play/42-Lucky-Dragon/index.html?gid=42&l=" + language)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            require(localizedEntry.statusCode() == 200 && localizedEntry.body().length > 0,
                "localized original entry failed: " + language);
        }

        System.out.println("LanAcceptanceTestMain PASS base=" + base + " staticAssets=" + staticPayloads.size()
            + " managedPid=" + jsonNumber(status.body(), "managedProcessPid") + " transferId=" + transferId
            + " idempotent=true history=list->detail->back cors=PASS");
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, URI base, String origin, String path,
                                              Map<String,String> form, Map<String,String> headers) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String,String> entry : form.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).header("Origin", origin)
            .header("Referer", base.resolve("/play/42-Lucky-Dragon/index.html?gid=42&l=en").toString())
            .header("Content-Type", "application/x-www-form-urlencoded");
        headers.forEach(request::header);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String match(Pattern pattern, String value, String name) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) throw new AssertionError("missing " + name + ": " + value);
        return matcher.group(1);
    }

    private static String jsonNumber(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : "UNKNOWN";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
