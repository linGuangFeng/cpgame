package com.cpgame.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform play-through probe. GET homepage is not a played round.
 * Each confirmed mode must complete at least twice without hanging the original page.
 */
public final class CpgameOriginalPagePlaythrough {
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final int SCATTER_ID = 11;
    private static final int PAID_SCATTER_TRIGGER = 3;
    private static final int MAX_FREE_STEPS = 32;
    private static final int MAX_NATURAL_PAID_ATTEMPTS = 512;
    static final int REQUIRED_COMPLETIONS_PER_MODE = 2;

    private CpgameOriginalPagePlaythrough() { }

    public static List<String> probe(String demoUrl) {
        return probe(demoUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), null);
    }

    public static List<String> probe(String demoUrl, Path capabilitiesFile) {
        return probe(demoUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), capabilitiesFile);
    }

    static List<String> probe(String demoUrl, HttpClient http, ObjectMapper mapper) {
        return probe(demoUrl, http, mapper, null);
    }

    static List<String> probe(String demoUrl, HttpClient http, ObjectMapper mapper, Path capabilitiesFile) {
        return probe(demoUrl, http, mapper, capabilitiesFile, MAX_NATURAL_PAID_ATTEMPTS);
    }

    static List<String> probe(String demoUrl, HttpClient http, ObjectMapper mapper, Path capabilitiesFile,
            int maxNaturalPaidAttempts) {
        List<String> problems = new ArrayList<>();
        if (demoUrl == null || demoUrl.isBlank()) {
            problems.add("试玩地址为空，无法在原页面打一局");
            return problems;
        }
        URI demo;
        try {
            demo = URI.create(demoUrl);
        } catch (RuntimeException error) {
            problems.add("试玩地址无法解析：" + demoUrl);
            return problems;
        }
        String directory = directoryFromPlayPath(demo.getRawPath());
        if (directory == null) return problems;
        String origin = origin(demo);
        String gid = gidFrom(demo, directory);
        PlaythroughPlan plan = playthroughPlan(directory, capabilitiesFile, mapper);
        List<String> required = plan.requiredModes();
        try {
            Posted probeInit = firstCp(http, List.of(origin + "/play/" + encode(directory), origin),
                    "/cp/single_game.Game/initRoom",
                    "gid=" + encode(gid) + "&token=" + encode("playthrough-" + UUID.randomUUID()), demoUrl);
            if (probeInit == null) return problems;
            if (probeInit.status() < 200 || probeInit.status() >= 300) {
                problems.add("原页面 Init 返回 HTTP " + probeInit.status() + "，不能只凭首页 200 当试玩通过");
                return problems;
            }
            String apiBase = probeInit.base();
            Map<String, Integer> completions = new LinkedHashMap<>();
            required.forEach(mode -> completions.put(mode, 0));
            int paidAttempts = 0;
            int attemptLimit = Math.max(REQUIRED_COMPLETIONS_PER_MODE, maxNaturalPaidAttempts);
            while (!allModesComplete(completions) && paidAttempts < attemptLimit) {
                paidAttempts++;
                String token = "playthrough-" + UUID.randomUUID();
                String attemptLabel = "自然付费局第 " + paidAttempts + " 次";
                Posted init = post(http, apiBase + "/cp/single_game.Game/initRoom",
                        "gid=" + encode(gid) + "&token=" + encode(token), demoUrl);
                if (init.status() < 200 || init.status() >= 300) {
                    problems.add(attemptLabel + " Init 返回 HTTP " + init.status() + "。defectArea=原页面试玩卡死");
                    return problems;
                }
                String form = "token=" + encode(token) + "&bet_gold=0.02&level=1&gid=" + encode(gid);
                Posted first = post(http, apiBase + "/cp/single_game.Game/gameResult", form, demoUrl);
                if (first.status() < 200 || first.status() >= 300) {
                    problems.add(attemptLabel + " Spin 返回 HTTP " + first.status()
                            + "，不能正常加载过去。defectArea=原页面试玩卡死");
                    return problems;
                }
                JsonNode current = parseSpin(mapper, first.body(), problems, attemptLabel);
                if (current == null) return problems;
                String classified = classifyPaid(current, plan.featureModes());
                String label = completions.containsKey(classified)
                        ? "模式 " + classified + " 第 "
                            + (Math.min(completions.get(classified), REQUIRED_COMPLETIONS_PER_MODE) + 1) + " 次"
                        : attemptLabel + "（" + classified + "）";
                String hang = cascadeHangReason(current);
                if (hang != null) {
                    problems.add(label + " 会卡死：" + hang + "。defectArea=原页面试玩卡死");
                    return problems;
                }
                int steps = 0;
                while (freeRemaining(current) > 0 && steps < MAX_FREE_STEPS) {
                    String continuationForm = "token=" + encode(token)
                            + "&bet_gold=0.02&level=1&gid=" + encode(gid);
                    if (usesFeatureContinuation(current)) {
                        continuationForm += "&type=" + plan.continuationRequestType();
                    }
                    Posted next = post(http, apiBase + "/cp/single_game.Game/gameResult",
                            continuationForm, demoUrl);
                    if (next.status() < 200 || next.status() >= 300) {
                        problems.add(label + " 免费局第 " + (steps + 1) + " 步返回 HTTP " + next.status()
                                + "。必须把 frees.st 走到 0。defectArea=原页面试玩卡死");
                        return problems;
                    }
                    current = parseSpin(mapper, next.body(), problems, label + " 免费局");
                    if (current == null) return problems;
                    hang = cascadeHangReason(current);
                    if (hang != null) {
                        problems.add(label + " 免费局会卡死：" + hang + "。defectArea=原页面试玩卡死");
                        return problems;
                    }
                    steps++;
                }
                if (freeRemaining(current) > 0) {
                    problems.add(label + " 免费局走不完，frees.st 仍为 " + freeRemaining(current)
                            + "。defectArea=原页面试玩卡死");
                    return problems;
                }
                if (completions.containsKey(classified)
                        && completions.get(classified) < REQUIRED_COMPLETIONS_PER_MODE) {
                    completions.compute(classified, (ignored, count) -> count + 1);
                }
            }
            for (Map.Entry<String, Integer> entry : completions.entrySet()) {
                if (entry.getValue() >= REQUIRED_COMPLETIONS_PER_MODE) continue;
                problems.add("模式 " + entry.getKey() + " 在 " + paidAttempts + " 个自然付费局中仅完整触发 "
                        + entry.getValue() + "/" + REQUIRED_COMPLETIONS_PER_MODE
                        + " 次；平台不会用隐藏参数强制指定结果。defectArea=原页面试玩卡死");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            problems.add("原页面试玩探测被中断");
        } catch (IOException | RuntimeException error) {
            problems.add("原页面试玩探测失败：" + concise(error));
        }
        return problems;
    }

    private static boolean allModesComplete(Map<String, Integer> completions) {
        return completions.values().stream().allMatch(count -> count >= REQUIRED_COMPLETIONS_PER_MODE);
    }

    static List<String> requiredModes(String directory, Path capabilitiesFile, ObjectMapper mapper) {
        return playthroughPlan(directory, capabilitiesFile, mapper).requiredModes();
    }

    private static PlaythroughPlan playthroughPlan(String directory, Path capabilitiesFile, ObjectMapper mapper) {
        LinkedHashSet<String> modes = new LinkedHashSet<>();
        Map<Integer, String> featureModes = new LinkedHashMap<>();
        int continuationRequestType = 2;
        modes.add("ORDINARY_LOSS");
        modes.add("ORDINARY_WIN");
        if (capabilitiesFile != null) {
            try {
                JsonNode cap = mapper.readTree(Files.readAllBytes(capabilitiesFile));
                JsonNode special = cap.get("specialModes");
                if (special != null && special.isArray()) {
                    for (JsonNode mode : special) {
                        if (!mode.path("applicable").asBoolean(false)
                                && !mode.path("confirmed").asBoolean(false)) continue;
                        String id = mode.path("id").asText("");
                        if (!id.isBlank()) {
                            modes.add(id);
                            JsonNode featureType = mode.get("providerFeatureType");
                            if (featureType != null && featureType.canConvertToInt()) {
                                featureModes.put(featureType.asInt(), id);
                            }
                        }
                    }
                }
                JsonNode continuationType = cap.path("roundModel").get("continuationRequestType");
                if (continuationType != null && continuationType.canConvertToInt()) {
                    continuationRequestType = continuationType.asInt();
                }
                if (cap.path("buyMode").path("applicable").asBoolean(false)
                        && cap.path("buyMode").path("independentMutuallyExclusive").asBoolean(false)) {
                    modes.add("BUY");
                }
                return new PlaythroughPlan(List.copyOf(modes), Map.copyOf(featureModes),
                        continuationRequestType);
            } catch (IOException ignored) {
            }
        }
        if (directory != null && directory.startsWith("1830")) modes.add("SCATTER_FREE_SPINS");
        return new PlaythroughPlan(List.copyOf(modes), Map.copyOf(featureModes),
                continuationRequestType);
    }

    static String classifyPaid(JsonNode data) {
        return classifyPaid(data, Map.of());
    }

    private static String classifyPaid(JsonNode data, Map<Integer, String> featureModes) {
        if (data == null || data.isMissingNode() || data.isNull()) return "UNKNOWN";
        String kind = data.path("kind").asText("");
        if (!kind.isBlank()) return kind;
        JsonNode feature = data.get("f");
        if (feature != null && feature.isObject()) {
            JsonNode featureType = feature.get("t");
            if (featureType != null && featureType.canConvertToInt()) {
                String mode = featureModes.get(featureType.asInt());
                if (mode != null && !mode.isBlank()) return mode;
            }
        }
        if (freeRemaining(data) > 0) return "SCATTER_FREE_SPINS";
        if (positiveNumber(data.get("total_win")) || positiveNumber(data.get("tw"))
                || positiveNumber(data.get("o")) || positiveNumber(data.path("res").get("tws"))
                || positiveWinArray(data.path("res").get("wa"))) return "ORDINARY_WIN";
        return "ORDINARY_LOSS";
    }

    private static boolean positiveNumber(JsonNode value) {
        return value != null && value.isNumber() && value.asDouble() > 0;
    }

    private static boolean positiveWinArray(JsonNode wins) {
        if (wins == null || !wins.isArray()) return false;
        for (JsonNode win : wins) {
            if (win != null && (positiveNumber(win.get("o")) || positiveNumber(win.get("win"))
                    || positiveNumber(win.get("amount")))) return true;
        }
        return false;
    }

    static String cascadeHangReason(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) return null;
        for (String field : new String[]{"change_gold", "bet_gold", "total_win", "start_gold", "end_gold"}) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode() && !value.isNumber()) {
                return field + " 不是 JSON 数字，原站 RecBetResult 会在 toFixed 处抛错，开始按钮保持置灰";
            }
        }
        JsonNode frees = data.get("frees");
        JsonNode stNode = frees == null || frees.isNull() || frees.isMissingNode() ? null : frees.get("st");
        if (stNode != null && !stNode.isNull() && !stNode.isMissingNode() && !stNode.isNumber()) {
            return "frees.st 不是 JSON 数字，GetFreeTimesView OnShow 会抛错，开始按钮保持置灰";
        }
        JsonNode props = data.get("props");
        if (props == null || !props.isArray() || props.isEmpty()) return null;
        JsonNode first = props.get(0);
        if (first == null || first.get("prop") == null || !first.get("prop").isArray()) return null;
        if (first.get("win_arr") == null) return null;
        int lastScatter = 0;
        for (int pageIndex = 0; pageIndex < props.size(); pageIndex++) {
            JsonNode page = props.get(pageIndex);
            JsonNode prop = page.get("prop");
            JsonNode winArr = page.get("win_arr");
            if (prop == null || !prop.isArray() || winArr == null || !winArr.isArray()) continue;
            boolean last = pageIndex == props.size() - 1;
            int eliminated = 0;
            for (JsonNode win : winArr) {
                if (win == null || win.get("p") == null || !win.get("p").isNumber()) continue;
                int symbol = win.get("p").asInt();
                for (JsonNode cell : prop) {
                    if (cell != null && cell.isNumber() && cell.asInt() == symbol) eliminated++;
                }
            }
            int scatter = 0;
            for (JsonNode cell : prop) {
                if (cell != null && cell.isNumber() && cell.asInt() == SCATTER_ID) scatter++;
            }
            lastScatter = scatter;
            if (!last && eliminated == 0) {
                return "第 " + pageIndex + " 页不是末页且 win_arr 无法在盘上消去任何格子；"
                        + "原站 SCATTERMOVE/NEXTPAGE_ELIMINATE 没有空位回调，会一直停在 TOTAL WIN";
            }
        }
        int type = data.path("type").asInt(1);
        int remaining = frees == null || frees.isMissingNode() || frees.isNull()
                ? 0 : frees.path("st").asInt(0);
        if (type == 1 && lastScatter >= PAID_SCATTER_TRIGGER && remaining <= 0) {
            return "末页有 " + lastScatter + " 个 Scatter 但 frees.st==0；"
                    + "GetFreeTimesView 不会弹出，GAME_ENDED 后开始按钮保持置灰";
        }
        return null;
    }

    private static JsonNode parseSpin(ObjectMapper mapper, String raw, List<String> problems, String label) {
        try {
            JsonNode body = mapper.readTree(raw == null ? "{}" : raw);
            if (body.path("code").isNumber() && body.path("code").asInt() != 0) {
                problems.add("原页面" + label + " Spin code=" + body.path("code").asInt()
                        + " msg=" + body.path("msg").asText(""));
                return null;
            }
            return body.path("data").isMissingNode() || body.path("data").isNull() ? body : body.path("data");
        } catch (RuntimeException error) {
            problems.add("原页面" + label + " 应答不是 JSON：" + concise(error));
            return null;
        }
    }

    private static int freeRemaining(JsonNode data) {
        if (data == null) return 0;
        JsonNode st = data.path("frees").path("st");
        if (st.isNumber()) return st.asInt();
        JsonNode cf = data.path("f").path("cf");
        return cf.isNumber() ? cf.asInt() : 0;
    }

    private static boolean usesFeatureContinuation(JsonNode data) {
        return data != null && data.path("f").isObject() && data.path("f").path("cf").isNumber();
    }

    private static Posted firstCp(HttpClient http, List<String> bases, String path, String body, String referer)
            throws IOException, InterruptedException {
        for (String base : bases) {
            Posted posted = post(http, base + path, body, referer);
            if (posted.status() != 404 && posted.status() != 405) return posted;
        }
        return null;
    }

    private static Posted post(HttpClient http, String url, String body, String referer)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (referer != null && !referer.isBlank()) builder.header("Referer", referer);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String base = url.contains("/cp/") ? url.substring(0, url.indexOf("/cp/")) : url;
        return new Posted(base, response.statusCode(), response.body());
    }

    private static String directoryFromPlayPath(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/play/")) return null;
        String remainder = rawPath.substring("/play/".length());
        int separator = remainder.indexOf('/');
        if (separator <= 0) return null;
        String directory = URLDecoder.decode(remainder.substring(0, separator), StandardCharsets.UTF_8);
        return directory.isBlank() ? null : directory;
    }

    private static String gidFrom(URI demo, String directory) {
        String query = demo.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
                if ("gid".equalsIgnoreCase(name) && equals >= 0) {
                    String value = URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
                    if (!value.isBlank()) return value;
                }
            }
        }
        if (directory != null) {
            int digits = 0;
            while (digits < directory.length() && Character.isDigit(directory.charAt(digits))) digits++;
            if (digits > 0) return directory.substring(0, digits);
        }
        return "0";
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Posted(String base, int status, String body) { }

    private record PlaythroughPlan(List<String> requiredModes, Map<Integer, String> featureModes,
                                   int continuationRequestType) { }
}
