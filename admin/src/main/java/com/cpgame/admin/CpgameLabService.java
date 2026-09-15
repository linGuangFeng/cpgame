package com.cpgame.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

public class CpgameLabService {
    private static final Pattern GAME_DIRECTORY = Pattern.compile("^([A-Za-z0-9]+)-(.+)$");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private final Path root;
    private final ObjectMapper mapper;
    private final Predicate<URI> demoReachability;
    private CpgameDemoRuntimeService demoRuntime;

    public CpgameLabService(AdminSettings settings, ObjectMapper mapper) {
        this(settings, mapper, CpgameLabService::reachableDemo);
    }

    CpgameLabService(AdminSettings settings, ObjectMapper mapper, Predicate<URI> demoReachability) {
        this.root = settings.getRoot();
        this.mapper = mapper;
        this.demoReachability = demoReachability;
    }

    public Path root() { return root; }

    public void setDemoRuntime(CpgameDemoRuntimeService demoRuntime) {
        this.demoRuntime = demoRuntime;
    }

    public List<GameCard> listGames() {
        Path publishRoot = root.resolve("publish");
        if (!Files.isDirectory(publishRoot, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<GameCard> result = new ArrayList<>();
        try (var paths = Files.list(publishRoot)) {
            paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .map(this::readGame).flatMap(Optional::stream)
                .forEach(result::add);
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
        result.sort(gameIdOrder());
        return List.copyOf(result);
    }

    static Comparator<GameCard> gameIdOrder() {
        return Comparator
            .comparingLong((GameCard game) -> numericGameId(game.gameId()))
            .thenComparing(GameCard::gameId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(GameCard::directoryName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    static long numericGameId(String gameId) {
        if (gameId == null || gameId.isBlank()) return Long.MAX_VALUE;
        try {
            return Long.parseLong(gameId.strip());
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public Optional<CoverImage> cover(String directoryName) {
        if (!safeDirectoryName(directoryName)) return Optional.empty();
        Path screenshotBase = root.resolve("screenshots").normalize();
        Path screenshotRoot = screenshotBase.resolve(directoryName).normalize();
        if (!screenshotRoot.startsWith(screenshotBase)
            || !Files.isDirectory(screenshotRoot, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var files = Files.walk(screenshotRoot)) {
            Optional<Path> image = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> IMAGE_EXTENSIONS.contains(extension(path)))
                .filter(path -> fileSize(path) <= 10L * 1024 * 1024)
                .sorted(Comparator.comparingInt(this::coverPriority)
                    .thenComparing(Comparator.comparingLong(this::fileSize).reversed())
                    .thenComparing(Path::toString))
                .findFirst();
            if (image.isEmpty()) return Optional.empty();
            Path path = image.get();
            return Optional.of(new CoverImage(Files.readAllBytes(path), mediaType(path)));
        } catch (IOException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private Optional<GameCard> readGame(Path publishDirectory) {
        String directoryName = publishDirectory.getFileName().toString();
        Matcher matcher = GAME_DIRECTORY.matcher(directoryName);
        if (!matcher.matches() || !safeDirectoryName(directoryName)) return Optional.empty();
        boolean publishReady = Files.isRegularFile(publishDirectory.resolve("index.html"), LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(publishDirectory.resolve("publish-manifest.json"), LinkOption.NOFOLLOW_LINKS)
            && !Files.exists(publishDirectory.resolve("_hosts"), LinkOption.NOFOLLOW_LINKS);
        JsonNode manifest = readJson(publishDirectory.resolve("publish-manifest.json"));
        JsonNode status = readJson(root.resolve("reports").resolve(directoryName).resolve("current-status.json"));
        JsonNode capabilities = readJson(root.resolve("protocol").resolve(directoryName).resolve("game-capabilities.json"));
        JsonNode scenarios = readJson(root.resolve("reports").resolve(directoryName).resolve("scenario-coverage.json"));
        JsonNode labMetadata = readJson(root.resolve("reports").resolve(directoryName).resolve("lab-metadata.json"));
        JsonNode inventory = readJson(root.resolve("reports").resolve(directoryName).resolve("language-inventory.json"));
        String gameId = text(manifest, "gameId", matcher.group(1));
        String name = displayName(text(manifest, "name", matcher.group(2)));
        String demoUrl = safeHttpUrl(firstNonBlankLine(readText(root.resolve("server-api").resolve(directoryName).resolve("demo-url.txt"))));
        List<String> languages = firstNonEmpty(
            languageCodes(status.path("testedLanguages")),
            languageCodes(status.path("coverage").path("languages")),
            languageCodes(status.path("languages")),
            languageCodes(capabilities.path("supportedLanguages").path("codes")),
            languageCodes(capabilities.path("languages")),
            languageCodes(manifest.path("languages")),
            languageCodes(inventory.path("sourceOfTruth").path("actualGameCodes")),
            languageCodes(inventory.path("supportedLanguages").path("codes")),
            languageCodes(inventory.path("testedLanguages")),
            languageCodes(inventory.path("languages")));
        List<String> modes = specialModes(status, capabilities);
        PaylineInfo paylines = paylines(labMetadata, capabilities);
        ReferenceLinks references = new ReferenceLinks(
            safeHttpUrl(labMetadata.path("reference1Url").asString("")),
            safeHttpUrl(labMetadata.path("reference2Url").asString("")));
        List<String> gaps = new ArrayList<>(gaps(status, capabilities, scenarios));
        List<String> description = description(gameId, name, languages, modes, capabilities, status);
        String rawStatus = text(status, "status", text(status, "overall", publishReady ? "publish-ready" : "incomplete"));
        boolean demoReachable = demoUrl != null && isDemoReachable(demoUrl);
        if (demoUrl != null && !demoReachable) gaps.add("试玩服务未启动或无法连接");
        boolean playable = publishReady && demoReachable;
        boolean launchable = demoRuntime == null
            || demoRuntime.launchCapability(directoryName).launchable();
        AcceptanceInfo acceptance = acceptanceInfo(rawStatus, playable, launchable, labMetadata);
        return Optional.of(new GameCard(directoryName, gameId, name, acceptance.label(),
            acceptance.workflowAccepted(), publishReady, playable, demoUrl, languages, modes,
            paylines, references, gaps, description, updatedAt(status, publishDirectory)));
    }

    private JsonNode readJson(Path path) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ? mapper.readTree(Files.readString(path)) : mapper.createObjectNode();
        } catch (IOException | RuntimeException ignored) {
            return mapper.createObjectNode();
        }
    }

    private List<String> specialModes(JsonNode status, JsonNode capabilities) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        JsonNode declared = capabilities.path("specialModes");
        if (declared.isObject()) declared.properties().forEach(entry -> {
            JsonNode mode = entry.getValue();
            if (mode.isObject() && mode.path("supported").asBoolean(false)) {
                result.add(modeLabel(entry.getKey()));
                if (mode.path("retriggerSupported").asBoolean(false)) {
                    result.add(modeLabel(entry.getKey() + "Retrigger"));
                }
            }
        });
        else if (declared.isArray()) strings(declared).forEach(mode -> result.add(modeLabel(mode)));

        strings(status.path("capturedModes")).stream().filter(this::isSpecialMode)
            .map(this::modeLabel).forEach(result::add);
        JsonNode coverage = status.path("coverage");
        if (coverage.isObject()) coverage.properties().forEach(entry -> {
            if (isSpecialMode(entry.getKey())
                && entry.getValue().asString("").toLowerCase(Locale.ROOT).contains("covered")) {
                result.add(modeLabel(entry.getKey()));
            }
        });
        return List.copyOf(result);
    }

    private boolean isSpecialMode(String value) {
        String normalized = value == null ? "" : value.replace("_", "-").toLowerCase(Locale.ROOT);
        if (normalized.contains("history") || normalized.contains("ordinary")
            || normalized.contains("first-load") || normalized.contains("rules")
            || normalized.contains("paytable")) return false;
        return normalized.contains("free") || normalized.contains("buy")
            || normalized.contains("purchase") || normalized.contains("bonus")
            || normalized.contains("cascade") || normalized.contains("retrigger")
            || normalized.contains("respin") || normalized.contains("golden")
            || normalized.contains("transform") || normalized.contains("jackpot")
            || normalized.contains("hold") || normalized.contains("wheel")
            || normalized.contains("mary") || normalized.contains("mali");
    }

    private String modeLabel(String key) {
        return switch (key) {
            case "ordinaryLoss" -> "普通局";
            case "ordinaryWin" -> "中奖局";
            case "featureBuy" -> "购买模式";
            case "freeSpin" -> "免费旋转";
            case "history" -> "历史记录";
            case "cascade", "base-cascade" -> "连消";
            case "goldenTransformingWild", "golden-symbol-transform" -> "黄金符号变百搭";
            case "freeSpins", "free-spins" -> "免费旋转";
            case "freeSpinRetrigger", "freeSpinsRetrigger", "free-spin-retrigger" -> "免费旋转重触发";
            case "purchaseEntry", "purchase-entry" -> "购买模式";
            case "separateRespinPurchase", "separate-respin-purchase", "respin" -> "重转";
            default -> chineseModeLabel(key);
        };
    }

    private String chineseModeLabel(String key) {
        String normalized = key == null ? "" : key.replace('_', '-').toLowerCase(Locale.ROOT);
        if (normalized.contains("free") && normalized.contains("retrigger")) return "免费旋转重触发";
        if (normalized.contains("free")) return "免费旋转";
        if (normalized.contains("buy") || normalized.contains("purchase")) return "购买模式";
        if (normalized.contains("cascade")) return "连消";
        if (normalized.contains("respin")) return "重转";
        if (normalized.contains("golden") || normalized.contains("transform")) return "符号变换模式";
        if (normalized.contains("jackpot")) return "累积大奖模式";
        if (normalized.contains("hold")) return "锁定模式";
        if (normalized.contains("wheel")) return "转盘模式";
        if (normalized.matches(".*[a-z].*")) return "其他特殊模式";
        return displayName(key);
    }

    private PaylineInfo paylines(JsonNode metadata, JsonNode capabilities) {
        PaylineInfo capabilityValue = capabilityPaylines(capabilities);
        String manualStatus = metadata.path("paylineStatus").asString("").toUpperCase(Locale.ROOT);
        if ("KNOWN".equals(manualStatus) && metadata.path("paylineCount").canConvertToInt()) {
            int count = metadata.path("paylineCount").asInt();
            if (count > 0) return new PaylineInfo(paylineLabel(count), count, "KNOWN", true);
        }
        if ("NOT_APPLICABLE".equals(manualStatus)) {
            return new PaylineInfo("中奖线不适用", null, "NOT_APPLICABLE", true);
        }
        if ("UNKNOWN".equals(manualStatus)) return new PaylineInfo("中奖线未知", null, "UNKNOWN", true);

        if (!"UNKNOWN".equals(capabilityValue.status())) return capabilityValue;
        return new PaylineInfo("中奖线未知", null, "UNKNOWN", false);
    }

    private PaylineInfo capabilityPaylines(JsonNode capabilities) {
        JsonNode winModel = capabilities.path("winModel");
        JsonNode winModelDetails = capabilities.path("winModelDetails");
        for (String key : List.of("paylineCount", "lineCount", "fixedPaylines", "numberOfPaylines")) {
            JsonNode value = winModel.path(key);
            if (value.canConvertToInt() && value.asInt() > 0) {
                int count = value.asInt();
                return new PaylineInfo(paylineLabel(count), count, "KNOWN", false);
            }
            JsonNode detailValue = winModelDetails.path(key);
            if (detailValue.canConvertToInt() && detailValue.asInt() > 0) {
                int count = detailValue.asInt();
                return new PaylineInfo(paylineLabel(count), count, "KNOWN", false);
            }
        }
        JsonNode declared = winModel.path("paylines");
        if (declared.isMissingNode()) declared = winModelDetails.path("paylines");
        if (declared.canConvertToInt() && declared.asInt() > 0) {
            int count = declared.asInt();
            return new PaylineInfo(paylineLabel(count), count, "KNOWN", false);
        }
        if (declared.isBoolean() && !declared.asBoolean()) {
            return new PaylineInfo("中奖线不适用", null, "NOT_APPLICABLE", false);
        }
        String type = winModel.isTextual() ? winModel.asString("")
            : winModel.path("type").asString(winModelDetails.path("type").asString(""));
        String normalizedType = type.toUpperCase(Locale.ROOT);
        if (normalizedType.contains("WAYS") || normalizedType.contains("CLUSTER")) {
            return new PaylineInfo("中奖线不适用", null, "NOT_APPLICABLE", false);
        }
        return new PaylineInfo("中奖线未知", null, "UNKNOWN", false);
    }

    public synchronized PaylineInfo updatePaylines(String directoryName, String rawValue) {
        if (!safeDirectoryName(directoryName)
            || !Files.isDirectory(root.resolve("publish").resolve(directoryName), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("游戏目录不存在");
        }
        String value = rawValue == null ? "" : rawValue.strip();
        ObjectNode metadata = editableMetadata(directoryName);
        metadata.put("schemaVersion", 1);
        metadata.put("directoryName", directoryName);
        PaylineInfo result;
        if (value.isEmpty() || "unknown".equalsIgnoreCase(value)) {
            metadata.put("paylineStatus", "UNKNOWN");
            result = new PaylineInfo("中奖线未知", null, "UNKNOWN", true);
        } else {
            int count;
            try { count = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("中奖线数必须是整数"); }
            if (count < 0 || count > 100_000) throw new IllegalArgumentException("中奖线数必须在0至100000之间");
            if (count == 0) {
                metadata.put("paylineStatus", "NOT_APPLICABLE");
                result = new PaylineInfo("中奖线不适用", null, "NOT_APPLICABLE", true);
            } else {
                metadata.put("paylineStatus", "KNOWN");
                metadata.put("paylineCount", count);
                result = new PaylineInfo(paylineLabel(count), count, "KNOWN", true);
            }
        }
        saveMetadata(directoryName, metadata, "无法保存中奖线数");
        return result;
    }

    public synchronized AcceptanceUpdate updateAcceptance(String directoryName, String rawStatus) {
        requireExistingGame(directoryName);
        String status = rawStatus == null ? "" : rawStatus.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "PASSED", "FAILED").contains(status)) {
            throw new IllegalArgumentException("人工验收状态无效");
        }
        ObjectNode metadata = editableMetadata(directoryName);
        metadata.put("schemaVersion", 1);
        metadata.put("directoryName", directoryName);
        if ("AUTO".equals(status)) metadata.remove("manualAcceptanceStatus");
        else metadata.put("manualAcceptanceStatus", status);
        saveMetadata(directoryName, metadata, "无法保存人工验收状态");
        return new AcceptanceUpdate(status, switch (status) {
            case "PASSED" -> "人工验收通过";
            case "FAILED" -> "人工验收未通过";
            default -> "已恢复自动判断";
        });
    }

    public synchronized ReferenceLinks updateReferenceLink(String directoryName, int slot,
                                                            String rawValue) {
        requireExistingGame(directoryName);
        if (slot != 1 && slot != 2) throw new IllegalArgumentException("参考页面编号只能是1或2");
        String value = rawValue == null ? "" : rawValue.strip();
        if (value.length() > 4096) throw new IllegalArgumentException("参考页面链接不能超过4096个字符");
        String normalized = value.isEmpty() ? null : safeHttpUrl(value);
        if (!value.isEmpty() && normalized == null) {
            throw new IllegalArgumentException("参考页面链接必须是有效的 http 或 https 地址");
        }
        ObjectNode metadata = editableMetadata(directoryName);
        metadata.put("schemaVersion", 1);
        metadata.put("directoryName", directoryName);
        String key = slot == 1 ? "reference1Url" : "reference2Url";
        if (normalized == null) metadata.remove(key); else metadata.put(key, normalized);
        saveMetadata(directoryName, metadata, "无法保存参考页面链接");
        return new ReferenceLinks(
            safeHttpUrl(metadata.path("reference1Url").asString("")),
            safeHttpUrl(metadata.path("reference2Url").asString("")));
    }

    private ObjectNode editableMetadata(String directoryName) {
        requireExistingGame(directoryName);
        JsonNode existing = readJson(root.resolve("reports").resolve(directoryName)
            .resolve("lab-metadata.json"));
        ObjectNode result = mapper.createObjectNode();
        if (existing.isObject()) existing.properties()
            .forEach(entry -> result.set(entry.getKey(), entry.getValue()));
        return result;
    }

    private void requireExistingGame(String directoryName) {
        if (!safeDirectoryName(directoryName)
            || !Files.isDirectory(root.resolve("publish").resolve(directoryName), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("游戏目录不存在");
        }
    }

    private void saveMetadata(String directoryName, ObjectNode metadata, String failureMessage) {
        metadata.put("updatedAt", Instant.now().toString());
        Path reports = root.resolve("reports").resolve(directoryName).normalize();
        Path destination = reports.resolve("lab-metadata.json");
        Path temporary = reports.resolve("lab-metadata.json.tmp");
        try {
            Files.createDirectories(reports);
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException(failureMessage, error);
        }
    }

    private List<String> gaps(JsonNode status, JsonNode capabilities, JsonNode scenarios) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        strings(status.path("missingModes")).forEach(value -> result.add("未获取特殊模式：" + chineseGapTerm(value)));
        // Older completed-game reports used knownGaps for architecture notes and future
        // platform migration work. Those are not missing game features and must not make a
        // verified game look incomplete. Explicit missing modes/protocol/scenario evidence
        // below remains visible regardless of the overall status.
        if (!completedWithoutDeclaredGaps(status)) {
            strings(status.path("knownGaps")).forEach(value -> result.add(chineseGapExplanation(value)));
        }
        strings(capabilities.path("unconfirmedOrAbsent")).forEach(value -> result.add("未确认：" + chineseGapTerm(value)));
        addScenarioGaps(result, scenarios.path("missingRare"), "未获取特殊场景");
        addScenarioGaps(result, scenarios.path("notDiscovered"), "未发现/不支持");
        return List.copyOf(result);
    }

    private boolean completedWithoutDeclaredGaps(JsonNode status) {
        String raw = text(status, "status", text(status, "overall", "")).toLowerCase(Locale.ROOT);
        if (raw.contains("incomplete") || raw.contains("failed") || raw.contains("blocked")
            || raw.contains("partial") || raw.contains("with-known-gap") || raw.contains("with-gap")) {
            return false;
        }
        return raw.contains("demo-verified") || raw.contains("complete")
            || raw.contains("passed") || raw.contains("ready");
    }

    private void addScenarioGaps(Set<String> result, JsonNode entries, String prefix) {
        if (!entries.isArray()) return;
        entries.forEach(item -> {
            if (item.isTextual()) result.add(prefix + "：" + chineseGapTerm(item.asString()));
            else if (item.isObject()) {
                String scenario = text(item, "scenario", text(item, "mode", "未知场景"));
                String reason = text(item, "reason", "");
                result.add(prefix + "：" + chineseGapTerm(scenario)
                    + (reason.isEmpty() ? "" : "，原因：" + chineseGapExplanation(reason)));
            }
        });
    }

    private String paylineLabel(int count) {
        return count + "条中奖线";
    }

    private String chineseGapTerm(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
            .replace('_', '-').replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "未知项目";
        if (normalized.contains("bonus-buy") || normalized.contains("feature-buy")
            || normalized.contains("purchase")) return "购买功能接口响应";
        if (normalized.contains("free-spin") || normalized.contains("freespin")) return "免费旋转场景";
        if (normalized.contains("retrigger")) return "免费旋转重触发场景";
        if (normalized.contains("history")) return "历史记录接口";
        if (normalized.contains("paytable")) return "赔付表接口";
        if (normalized.contains("rule")) return "游戏规则接口";
        if (normalized.contains("spin")) return "旋转接口响应";
        if (normalized.matches(".*[a-z].*")) return "一项协议或场景（具体内容请查看抓取报告）";
        return value.strip();
    }

    private String chineseGapExplanation(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return "具体原因尚未记录。";
        if (text.matches(".*[a-zA-Z].*")) return chineseGapTerm(text);
        return text;
    }

    private List<String> description(String gameId, String name, List<String> languages,
                                     List<String> modes, JsonNode capabilities, JsonNode status) {
        List<String> result = new ArrayList<>();
        result.add("游戏：" + name + "（游戏编号 " + gameId + "）");
        JsonNode winModel = capabilities.path("winModel");
        String winType = winModel.path("type").asString("");
        if (!winType.isBlank()) {
            if (winType.toUpperCase(Locale.ROOT).contains("WAYS") && winModel.path("fixedWays").asInt(0) > 0) {
                result.add("中奖方式：" + winModel.path("fixedWays").asInt() + " Ways");
            } else result.add("中奖方式：" + winType.replace('_', ' '));
        }
        JsonNode board = capabilities.path("boardModel");
        int reels = board.path("visibleReels").asInt(0);
        int rows = board.path("visibleRowsPerReel").asInt(0);
        if (reels > 0 && rows > 0) result.add("牌面：" + reels + "轴 × " + rows + "行");
        JsonNode minimumBet = capabilities.path("betModel").path("minimumBet");
        if (minimumBet.isObject() && minimumBet.path("ba").isValueNode()) {
            result.add("最小押注：" + minimumBet.path("ba").asString());
        }
        double rtp = capabilities.path("theoreticalRtp").path("percent").asDouble(0);
        if (rtp > 0) result.add("理论 RTP：" + rtp + "%");
        if (!modes.isEmpty()) result.add("特殊模式：" + String.join("、", modes));
        if (!languages.isEmpty()) result.add("已记录语言：" + languages.size() + " 种");
        String rulesVersion = status.path("rulesCore").path("rulesVersion").asString("");
        if (!rulesVersion.isBlank()) result.add("规则版本：" + rulesVersion);
        return List.copyOf(result);
    }

    private Instant updatedAt(JsonNode status, Path publishDirectory) {
        for (String key : List.of("updatedAt", "generatedAt")) {
            String value = status.path(key).asString("").strip();
            if (!value.isEmpty()) try { return Instant.parse(value); } catch (RuntimeException ignored) { }
        }
        try { return Files.getLastModifiedTime(publishDirectory).toInstant(); }
        catch (IOException ignored) { return null; }
    }

    private AcceptanceInfo acceptanceInfo(String rawStatus, boolean playable,
                                          boolean launchable, JsonNode metadata) {
        String manualStatus = metadata.path("manualAcceptanceStatus").asString("")
            .strip().toUpperCase(Locale.ROOT);
        String playableSuffix = playable ? " · 可试玩" : " · 入口未启动";
        if ("PASSED".equals(manualStatus)) {
            return new AcceptanceInfo("人工验收通过" + playableSuffix, true);
        }
        if ("FAILED".equals(manualStatus)) {
            return new AcceptanceInfo("人工验收未通过" + (playable ? " · 可试玩" : ""), false);
        }
        if (!launchable && playable) {
            return new AcceptanceInfo("产物已完成 · 试玩模块不可启动", false);
        }

        String normalized = rawStatus == null ? "" : rawStatus.toLowerCase(Locale.ROOT);
        if (normalized.contains("incomplete") || normalized.contains("failed")
            || normalized.contains("blocked")) return new AcceptanceInfo(
                playable ? "验收未完成 · 可试玩" : "未完成", false);
        if (normalized.contains("demo-verified")) return new AcceptanceInfo(
            playable ? "试玩已验证 · 未终验" : "静态资源已完成 · 未终验", false);
        if (normalized.contains("complete") || normalized.contains("pass") || normalized.contains("ready")) {
            return new AcceptanceInfo(
                playable ? "产物已完成 · 未终验" : "静态资源已完成 · 未终验", false);
        }
        return new AcceptanceInfo(playable ? "仅可试玩 · 未终验" : "未完成", false);
    }

    static String firstNonBlankLine(String value) {
        if (value == null || value.isBlank()) return null;
        for (String line : value.split("\\R", -1)) {
            String candidate = line.strip();
            if (!candidate.isEmpty()) return candidate;
        }
        return null;
    }

    private String safeHttpUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.strip());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null ? uri.toString() : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private boolean isDemoReachable(String value) {
        try {
            return demoReachability.test(URI.create(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Probes only loopback demo ports; arbitrary artifact URLs must never become an SSRF probe. */
    private static boolean reachableDemo(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
            || "::1".equals(host);
        if (!loopback) return true;
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 150);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private String readText(Path path) {
        try { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.readString(path).strip() : null; }
        catch (IOException ignored) { return null; }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asString("").strip();
        return value.isEmpty() ? fallback : value;
    }

    private String displayName(String name) {
        return name.replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    @SafeVarargs
    private final List<String> firstNonEmpty(List<String>... choices) {
        for (List<String> choice : choices) if (choice != null && !choice.isEmpty()) return choice;
        return List.of();
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        node.forEach(item -> { if (!item.asString("").isBlank()) values.add(item.asString().strip()); });
        return List.copyOf(values);
    }

    private List<String> languageCodes(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        node.forEach(item -> {
            String text = item.asString("").strip();
            if (text.isEmpty()) {
                text = item.path("code").asString(item.path("language").asString("")).strip();
            }
            if (!text.isBlank()) values.add(text);
        });
        return List.copyOf(values);
    }

    private boolean safeDirectoryName(String value) {
        return value != null && GAME_DIRECTORY.matcher(value).matches()
            && !value.contains("..") && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private long fileSize(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return Long.MAX_VALUE; }
    }

    private int coverPriority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (namedCover(name)) return -1;
        if (placeholderCoverName(name)) return 6;
        if (name.contains("reels") || name.contains("board") || name.contains("ingame")
            || name.contains("gameplay")) return 0;
        if (name.contains("main") || name.contains("loaded") || name.contains("spin")) return 1;
        if (name.contains("language") || name.contains("control")) return 4;
        return 2;
    }

    private boolean namedCover(String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return "cover".equals(stem) || stem.startsWith("cover-") || stem.startsWith("cover_");
    }

    private boolean placeholderCoverName(String name) {
        return name.contains("first-paint")
            || name.contains("login")
            || name.contains("catalog")
            || name.contains("entry-")
            || name.contains("paytable")
            || name.contains("loading")
            || name.contains("get-started");
    }

    private String mediaType(Path path) {
        return switch (extension(path)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }

    private record AcceptanceInfo(String label, boolean workflowAccepted) { }
    public record GameCard(String directoryName, String gameId, String name, String statusLabel,
                           boolean workflowAccepted, boolean publishReady, boolean playable,
                           String demoUrl,
                           List<String> languages, List<String> modes, PaylineInfo paylines,
                           ReferenceLinks references, List<String> gaps, List<String> description,
                           Instant updatedAt) {
        public String acceptanceCategory() {
            if (workflowAccepted) return "ACCEPTED";
            return statusLabel != null && statusLabel.contains("未通过") ? "REJECTED" : "PENDING";
        }

        public String manualAcceptanceStatus() {
            if (statusLabel != null && statusLabel.startsWith("人工验收通过")) return "PASSED";
            if (statusLabel != null && statusLabel.startsWith("人工验收未通过")) return "FAILED";
            return "AUTO";
        }
    }
    public record PaylineInfo(String label, Integer count, String status, boolean manual) { }
    public record AcceptanceUpdate(String status, String label) { }
    public record ReferenceLinks(String reference1Url, String reference2Url) { }
    public record CoverImage(byte[] bytes, String mediaType) { }
}
