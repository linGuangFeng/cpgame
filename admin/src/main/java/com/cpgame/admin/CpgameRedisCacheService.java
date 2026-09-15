package com.cpgame.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Redis indexes written by runRedis and groups them by key prefix
 * (PerKeyList_1, MaryKeyList_0, …). The first response is index members only;
 * list lengths are filled later in pipeline batches of {@link #PIPELINE}.
 */
final class CpgameRedisCacheService {
    private static final Pattern SAFE_DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final Pattern LEADING_ID = Pattern.compile("^(\\d+)");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");
    static final int MAX_MULTIPLIER = 100_000;
    static final int PIPELINE = 500;
    static final double MEMORY_SAMPLE_RATE = 0.02;
    static final int MEMORY_SAMPLE_CAP = 8;
    static final int MEMBER_PAGE_MAX = 50;
    static final int MEMBER_CHARS_MAX = 8_192;
    static final int DELETE_SCAN_MAX = 8_000;

    private final Path root;

    CpgameRedisCacheService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    CacheSnapshot inspect(String directoryName) {
        requireSafeDirectory(directoryName);
        RedisTarget target = discover(directoryName);
        if (target == null) {
            return CacheSnapshot.failure(directoryName, 0L, "", 0, 0,
                "未找到 generator/dist 或 server-api/dist 中的 Redis 配置");
        }
        try (CpgameRedisClient redis = connect(target)) {
            Map<String, PrefixAccumulator> groups = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            collectKnownIndexes(redis, target, groups, seen);
            List<PrefixGroup> prefixes = finalizeGroups(groups);
            long totalKeys = prefixes.stream().mapToLong(PrefixGroup::keyCount).sum();
            boolean truncated = groups.values().stream().anyMatch(group -> group.truncated);
            String message = prefixes.isEmpty()
                ? "没有 PerKeyList / MaryKeyList 缓存"
                : "已读取 " + prefixes.size() + " 个 PerKeyList / MaryKeyList，仅列出索引中的倍数；空 key 表示索引有该倍数但结果列表为空，条数每批 " + PIPELINE
                    + "，占用只测有数据的倍数"
                    + (truncated ? "；最大倍数超过 " + MAX_MULTIPLIER + "，超出部分未展示" : "");
            return new CacheSnapshot(true, directoryName, target.gameId, target.host, target.port,
                target.database, message, totalKeys, 0, null, prefixes);
        } catch (IOException error) {
            return CacheSnapshot.failure(directoryName, target.gameId, target.host, target.port,
                target.database, "无法读取 Redis " + target.host + ":" + target.port
                    + "/" + target.database + "：" + concise(error));
        }
    }

    CountBatch counts(String directoryName, Collection<String> requested) {
        RedisTarget target = resolve(directoryName);
        if (target == null) {
            return new CountBatch(false, "未找到 generator/dist 或 server-api/dist 中的 Redis 配置", Map.of());
        }
        List<String> keys = scopedKeys(requested, target);
        try (CpgameRedisClient redis = connect(target)) {
            return new CountBatch(true, "ok", llenAll(redis, keys));
        } catch (IOException error) {
            return new CountBatch(false, "无法读取 Redis " + target.host + ":" + target.port
                + "/" + target.database + "：" + concise(error), Map.of());
        }
    }

    MemoryBatch memory(String directoryName, Collection<String> requested) {
        return memory(directoryName, requested, Map.of());
    }

    MemoryBatch memory(String directoryName, Collection<String> requested, Map<String, Long> knownCounts) {
        RedisTarget target = resolve(directoryName);
        if (target == null) {
            return new MemoryBatch(false, "未找到 generator/dist 或 server-api/dist 中的 Redis 配置",
                Map.of(), false);
        }
        List<String> keys = scopedKeys(requested, target);
        try (CpgameRedisClient redis = connect(target)) {
            Map<String, Long> lengths = lengthsForMemory(redis, keys, knownCounts);
            Map<String, Long> memory = new LinkedHashMap<>();
            for (String key : keys) {
                if (lengths.getOrDefault(key, 0L) <= 0) memory.put(key, 0L);
            }
            List<String> withData = sampleKeysForMemory(keys, lengths);
            // Capability belongs to this connection and request. A different Redis instance
            // (including the localhost fallback) must not disable memory reads globally.
            try {
                if (!withData.isEmpty()) memory.putAll(memoryAll(redis, withData, lengths));
            } catch (IOException error) {
                if (!memoryCommandUnsupported(error)) throw error;
                return new MemoryBatch(true, "当前 Redis 不支持 MEMORY USAGE", memory, false);
            }
            for (String key : keys) memory.putIfAbsent(key, 0L);
            return new MemoryBatch(true, "ok", memory, true);
        } catch (IOException error) {
            return new MemoryBatch(false, "无法读取 Redis " + target.host + ":" + target.port
                + "/" + target.database + "：" + concise(error), Map.of(), true);
        }
    }

    MemberPage members(String directoryName, String listKey, int offset, int limit) {
        RedisTarget target = resolve(directoryName);
        if (target == null) {
            return new MemberPage(false, listKey, 0, 0, 0, List.of(), "未找到 Redis 配置");
        }
        if (listKey == null || listKey.isBlank() || !belongsToGame(listKey, target.gameIds)) {
            return new MemberPage(false, listKey, 0, 0, 0, List.of(), "Redis key 不属于当前游戏");
        }
        int start = Math.max(0, offset);
        int size = Math.min(MEMBER_PAGE_MAX, Math.max(1, limit));
        try (CpgameRedisClient redis = connect(target)) {
            long total = redis.llen(listKey);
            List<String> raw = redis.lrange(listKey, start, (long) start + size - 1);
            List<MemberItem> members = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                String value = raw.get(i) == null ? "" : raw.get(i);
                boolean truncated = value.length() > MEMBER_CHARS_MAX;
                members.add(new MemberItem(start + i, truncated ? value.substring(0, MEMBER_CHARS_MAX) : value, truncated));
            }
            return new MemberPage(true, listKey, total, start, size, members, "ok");
        } catch (IOException error) {
            return new MemberPage(false, listKey, 0, start, size, List.of(),
                "无法读取结果：" + concise(error));
        }
    }

    DeleteResult deleteLists(String directoryName, Collection<String> listKeys) {
        RedisTarget target = resolve(directoryName);
        if (target == null) return new DeleteResult(false, "未找到 Redis 配置", 0, 0);
        List<String> keys = scopedKeys(listKeys, target);
        if (keys.isEmpty()) return new DeleteResult(false, "没有可删除的 Redis key", 0, 0);
        try (CpgameRedisClient redis = connect(target)) {
            int removedMembers = 0;
            for (String listKey : keys) {
                removedMembers += removeIndexMember(redis, listKey);
            }
            long deleted = redis.del(keys);
            return new DeleteResult(true, "已删除 " + deleted + " 个结果列表", (int) deleted, removedMembers);
        } catch (IOException error) {
            return new DeleteResult(false, "删除失败：" + concise(error), 0, 0);
        }
    }

    DeleteResult deletePrefix(String directoryName, String indexKey) {
        RedisTarget target = resolve(directoryName);
        if (target == null) return new DeleteResult(false, "未找到 Redis 配置", 0, 0);
        if (indexKey == null || indexKey.isBlank() || !belongsToGame(indexKey, target.gameIds)) {
            return new DeleteResult(false, "索引 key 不属于当前游戏", 0, 0);
        }
        try (CpgameRedisClient redis = connect(target)) {
            return deleteIndex(redis, indexKey);
        } catch (IOException error) {
            return new DeleteResult(false, "删除失败：" + concise(error), 0, 0);
        }
    }

    DeleteResult deleteAll(String directoryName) {
        return deleteAll(directoryName, List.of(), List.of());
    }

    DeleteResult deleteAll(String directoryName, Collection<String> extraIndexKeys) {
        return deleteAll(directoryName, extraIndexKeys, List.of());
    }

    DeleteResult deleteAll(String directoryName, Collection<String> extraIndexKeys, Collection<String> extraListKeys) {
        RedisTarget target = resolve(directoryName);
        if (target == null) return new DeleteResult(false, "未找到 Redis 配置", 0, 0);
        try (CpgameRedisClient redis = connect(target)) {
            LinkedHashSet<String> indexes = new LinkedHashSet<>(knownIndexKeys(target.gameIds));
            if (extraIndexKeys != null) {
                for (String key : extraIndexKeys) {
                    if (key != null && !key.isBlank() && belongsToGame(key, target.gameIds)) indexes.add(key);
                }
            }
            List<String> lists = new ArrayList<>();
            if (extraListKeys != null) {
                for (String key : extraListKeys) {
                    if (key != null && !key.isBlank() && belongsToGame(key, target.gameIds)) lists.add(key);
                }
            }
            int deletedLists = lists.isEmpty() ? 0 : (int) redis.del(lists);
            if (lists.isEmpty()) {
                for (String indexKey : indexes) {
                    DeleteResult one = deleteIndex(redis, indexKey);
                    deletedLists += one.deletedLists();
                }
            } else if (!indexes.isEmpty()) {
                redis.del(new ArrayList<>(indexes));
            }
            return new DeleteResult(true, "已删除全部索引和结果", deletedLists, indexes.size());
        } catch (IOException error) {
            return new DeleteResult(false, "删除失败：" + concise(error), 0, 0);
        }
    }

    private DeleteResult deleteIndex(CpgameRedisClient redis, String indexKey) throws IOException {
        String type = redis.type(indexKey);
        if (type != null) type = type.toLowerCase(Locale.ROOT);
        if (type == null || "none".equals(type)) return new DeleteResult(true, "ok", 0, 0);
        List<String> members = switch (type) {
            case "zset" -> redis.zmembers(indexKey, MAX_MULTIPLIER + 1);
            case "set" -> redis.sscan(indexKey, MAX_MULTIPLIER + 1);
            default -> List.of();
        };
        List<String> lists = new ArrayList<>();
        for (String member : members) {
            String listKey = listKeyFor(indexKey, displayMultiplier(member, null));
            if (listKey != null && !listKey.isBlank()) lists.add(listKey);
        }
        long deleted = lists.isEmpty() ? 0 : redis.del(lists);
        redis.del(List.of(indexKey));
        return new DeleteResult(true, "ok", (int) deleted, members.size());
    }

    private int removeIndexMember(CpgameRedisClient redis, String listKey) throws IOException {
        String indexKey = indexKeyForList(listKey);
        if (indexKey == null) return 0;
        List<String> members = indexMembersForMultiplier(listKey, displayMultiplier(null, listKey));
        String type = redis.type(indexKey);
        if (type != null) type = type.toLowerCase(Locale.ROOT);
        if ("zset".equals(type)) return (int) redis.zrem(indexKey, members);
        if ("set".equals(type)) return (int) redis.srem(indexKey, members);
        return 0;
    }

    static String indexKeyForList(String listKey) {
        if (listKey == null) return null;
        if (listKey.startsWith("BetLog:")) {
            String rest = listKey.substring("BetLog:".length());
            int colon = rest.lastIndexOf(':');
            if (colon <= 0) return null;
            return "PerKeyList_" + rest.substring(0, colon);
        }
        if (listKey.startsWith("MaryLog:")) {
            String rest = listKey.substring("MaryLog:".length());
            int colon = rest.lastIndexOf(':');
            if (colon <= 0) return null;
            return "MaryKeyList_" + rest.substring(0, colon);
        }
        return null;
    }

    static List<String> indexMembersForMultiplier(String listKey, String multiplier) {
        LinkedHashSet<String> members = new LinkedHashSet<>();
        if (multiplier != null && !multiplier.isBlank()) {
            members.add(multiplier);
            members.add(stripLeadingZeros(multiplier));
            if (multiplier.matches("\\d+")) {
                try {
                    members.add(String.format(Locale.ROOT, "%06d", Long.parseLong(stripLeadingZeros(multiplier))));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (listKey != null) {
            int colon = listKey.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < listKey.length()) {
                String tail = listKey.substring(colon + 1);
                members.add(tail);
                members.add(stripLeadingZeros(tail));
            }
        }
        members.removeIf(value -> value == null || value.isBlank());
        return List.copyOf(members);
    }

    private Map<String, Long> lengthsForMemory(CpgameRedisClient redis, List<String> keys,
                                               Map<String, Long> knownCounts) throws IOException {
        Map<String, Long> lengths = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (knownCounts != null && knownCounts.containsKey(key) && knownCounts.get(key) != null) {
                lengths.put(key, Math.max(0L, knownCounts.get(key)));
            } else {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) lengths.putAll(llenAll(redis, missing));
        return lengths;
    }

    private RedisTarget resolve(String directoryName) {
        requireSafeDirectory(directoryName);
        return discover(directoryName);
    }

    private List<String> scopedKeys(Collection<String> requested, RedisTarget target) {
        List<String> keys = new ArrayList<>();
        if (requested == null) return keys;
        for (String key : requested) {
            if (key == null || key.isBlank()) continue;
            if (!belongsToGame(key, target.gameIds)) continue;
            keys.add(key);
            if (keys.size() >= PIPELINE) break;
        }
        return keys;
    }

    static String groupName(String key, Collection<Long> gameIds) {
        if (key == null || key.isBlank() || !belongsToGame(key, gameIds)) return null;
        String mapped = mapLogFamily(key);
        Matcher match = Pattern.compile("^(PerKeyList|MaryKeyList)_(.)").matcher(mapped);
        if (!match.find()) return null;
        return match.group(1) + "_" + match.group(2);
    }

    static boolean belongsToGame(String key, Collection<Long> gameIds) {
        if (key == null || gameIds == null || gameIds.isEmpty()) return false;
        String mapped = mapLogFamily(key);
        Matcher match = Pattern.compile("^(PerKeyList|MaryKeyList)_(.+)$").matcher(mapped);
        if (!match.find()) return false;
        String idRun = match.group(2).split(":", 2)[0];
        for (long id : gameIds) {
            if (id < 0) continue;
            String pad8 = String.format(Locale.ROOT, "%08d", id);
            String pad9 = String.format(Locale.ROOT, "%09d", id);
            String raw = Long.toString(id);
            if (idRun.equals(pad9) || idRun.equals(pad8) || idRun.equals(raw)) return true;
            if (idRun.length() == pad8.length() + 1 && idRun.substring(1).equals(pad8)) return true;
        }
        return false;
    }

    static String mapLogFamily(String key) {
        if (key.startsWith("BetLog:")) return "PerKeyList_" + key.substring("BetLog:".length());
        if (key.startsWith("MaryLog:")) return "MaryKeyList_" + key.substring("MaryLog:".length());
        return key;
    }

    static String poolGroupName(String key) {
        int jungle = key.indexOf(":jungle-fruit:");
        if (jungle >= 0) {
            String rest = key.substring(jungle + ":jungle-fruit:".length());
            rest = rest.replace("pool-index:", "pool:");
            return rest.replaceFirst(":[0-9]+$", "");
        }
        int marker = key.indexOf(":pool-index:");
        if (marker >= 0) {
            return key.substring(marker + 1).replace("pool-index:", "pool:").replaceFirst(":[0-9]+$", "");
        }
        marker = key.indexOf(":pool:");
        if (marker >= 0) {
            return key.substring(marker + 1).replaceFirst(":[0-9]+$", "");
        }
        return null;
    }

    static String stripGameIdRun(String value, Collection<Long> gameIds) {
        if (value == null || value.isBlank() || gameIds == null || gameIds.isEmpty()) return value;
        for (int width : new int[]{8, 9}) {
            for (long id : gameIds) {
                if (id < 0) continue;
                String token = String.format(Locale.ROOT, "%0" + width + "d", id);
                String replaced = replaceTrailingId(value, token);
                if (replaced != null) return replaced;
            }
        }
        for (long id : gameIds) {
            if (id < 0) continue;
            String raw = Long.toString(id);
            String replaced = replaceTrailingId(value, raw);
            if (replaced != null) return replaced;
        }
        return value;
    }

    static String replaceTrailingId(String value, String token) {
        Matcher matcher = DIGIT_RUN.matcher(value);
        int start = -1;
        int end = -1;
        while (matcher.find()) {
            if (matcher.group().endsWith(token) && matcher.group().length() >= token.length()) {
                start = matcher.start();
                end = matcher.end();
            }
        }
        if (start < 0) return null;
        String run = value.substring(start, end);
        String remainder = run.substring(0, run.length() - token.length());
        if (remainder.isEmpty()) remainder = "0";
        return value.substring(0, start) + remainder + value.substring(end);
    }

    static String extraSuffix(String[] parts) {
        if (parts.length == 2 && !parts[1].matches("\\d{1,8}")) return parts[1];
        if (parts.length >= 3) {
            if (parts[1].matches("\\d{1,8}")) return parts[2];
            return parts[1] + ":" + parts[2];
        }
        return "";
    }

    static String displayMultiplier(String member, String listKey) {
        if (member != null && member.matches("-?\\d+(\\.\\d+)?")) return stripLeadingZeros(member);
        if (listKey != null) {
            int colon = listKey.lastIndexOf(':');
            if (colon >= 0) {
                String tail = listKey.substring(colon + 1);
                if (tail.matches("\\d+")) return stripLeadingZeros(tail);
            }
        }
        return member == null ? "" : member;
    }

    static String stripLeadingZeros(String value) {
        if (value == null || value.isBlank()) return "0";
        if (value.contains(".")) return value;
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') index++;
        return value.substring(index);
    }

    static long parsedMultiplier(String member) {
        String display = displayMultiplier(member, null);
        try {
            long value = Long.parseLong(stripLeadingZeros(display));
            return value < 0 ? 0 : value;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static long multiplierCeiling(String lastMember) {
        long max = parsedMultiplier(lastMember);
        return Math.min(max, MAX_MULTIPLIER);
    }

    static List<String> evenSample(List<String> source, int limit) {
        if (source == null || source.isEmpty() || limit <= 0) return List.of();
        if (source.size() <= limit) return List.copyOf(source);
        List<String> out = new ArrayList<>(limit);
        int last = source.size() - 1;
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * (double) last / (limit - 1));
            out.add(source.get(index));
        }
        return out;
    }

    static int memorySampleCount(long memberCount) {
        if (memberCount <= 0) return 0;
        long samples = (long) Math.ceil(memberCount * MEMORY_SAMPLE_RATE);
        if (samples < 1) samples = 1;
        if (samples > MEMORY_SAMPLE_CAP) samples = MEMORY_SAMPLE_CAP;
        return (int) samples;
    }

    static List<String> sampleKeysForMemory(List<String> keys, Map<String, Long> counts) {
        if (keys == null || keys.isEmpty()) return List.of();
        List<String> withData = new ArrayList<>();
        for (String key : keys) {
            if (counts != null && counts.getOrDefault(key, 0L) > 0) withData.add(key);
        }
        return withData;
    }

    static Long estimateMemoryBytes(Map<String, Long> sampleMemory, Map<String, Long> counts, long totalMembers) {
        if (sampleMemory == null || sampleMemory.isEmpty() || totalMembers <= 0) return null;
        long memory = 0;
        long members = 0;
        for (Map.Entry<String, Long> entry : sampleMemory.entrySet()) {
            if (entry.getValue() == null) continue;
            long count = counts == null ? 0L : counts.getOrDefault(entry.getKey(), 0L);
            if (count <= 0) continue;
            memory += entry.getValue();
            members += count;
        }
        if (members <= 0) return null;
        return Math.round((double) memory / members * totalMembers);
    }

    static List<String> knownIndexKeys(Collection<Long> gameIds) {
        List<String> candidates = new ArrayList<>();
        if (gameIds == null) return candidates;
        for (long id : gameIds) {
            if (id < 0) continue;
            String pad8 = String.format(Locale.ROOT, "%08d", id);
            String pad9 = String.format(Locale.ROOT, "%09d", id);
            candidates.add("PerKeyList_" + pad9);
            candidates.add("MaryKeyList_" + pad9);
            for (int bet = 0; bet <= 3; bet++) {
                candidates.add("PerKeyList_" + bet + pad8);
                candidates.add("MaryKeyList_" + bet + pad8);
            }
        }
        return candidates;
    }

    static List<String> indexScanPatterns() {
        return List.of("PerKeyList_*", "MaryKeyList_*");
    }

    static List<String> scanPatterns(Collection<Long> gameIds) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (gameIds == null) return List.of();
        for (long id : gameIds) {
            if (id < 0) continue;
            String pad8 = String.format(Locale.ROOT, "%08d", id);
            String pad9 = String.format(Locale.ROOT, "%09d", id);
            patterns.add("BetLog:" + pad8 + ":*");
            patterns.add("BetLog:" + pad9 + ":*");
            patterns.add("MaryLog:" + pad8 + ":*");
            patterns.add("MaryLog:" + pad9 + ":*");
            for (int bet = 1; bet <= 3; bet++) {
                patterns.add("BetLog:" + bet + pad8 + ":*");
                patterns.add("MaryLog:" + bet + pad8 + ":*");
            }
        }
        return List.copyOf(patterns);
    }

    private void collectKnownIndexes(CpgameRedisClient redis, RedisTarget target,
                                     Map<String, PrefixAccumulator> groups, Set<String> seen)
        throws IOException {
        ingestKeys(redis, target, knownIndexKeys(target.gameIds), groups, seen);
    }

    private void ingestKeys(CpgameRedisClient redis, RedisTarget target, Collection<String> keys,
                            Map<String, PrefixAccumulator> groups, Set<String> seen) throws IOException {
        List<String> fresh = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank() || !seen.add(key)) continue;
            fresh.add(key);
        }
        if (fresh.isEmpty()) return;
        for (int offset = 0; offset < fresh.size(); offset += PIPELINE) {
            List<String> batch = fresh.subList(offset, Math.min(fresh.size(), offset + PIPELINE));
            List<String[]> commands = new ArrayList<>(batch.size());
            for (String key : batch) commands.add(new String[]{"TYPE", key});
            List<Object> types = redis.pipeline(commands);
            for (int i = 0; i < batch.size(); i++) {
                String key = batch.get(i);
                String type = asText(types.get(i));
                if (type != null) type = type.toLowerCase(Locale.ROOT);
                if (type == null || "none".equals(type)) continue;
                String prefix = groupName(key, target.gameIds);
                if (prefix == null) continue;
                PrefixAccumulator group = groups.computeIfAbsent(prefix, ignored -> new PrefixAccumulator(prefix));
                switch (type) {
                    case "zset" -> {
                        if (group.indexKey == null) {
                            group.indexKey = key;
                            group.indexType = "zset";
                        }
                        if (redis.zcard(key) <= 0) continue;
                        addIndexMembers(group, key, redis.zmembers(key, MAX_MULTIPLIER + 1));
                    }
                    case "set" -> {
                        if (group.indexKey == null) {
                            group.indexKey = key;
                            group.indexType = "set";
                        }
                        addIndexMembers(group, key, redis.sscan(key, MAX_MULTIPLIER + 1));
                    }
                    case "list" -> {
                        String multiplier = displayMultiplier(null, key);
                        addListKey(group, multiplier, multiplier, key);
                    }
                    default -> { }
                }
            }
        }
    }

    private void addIndexMembers(PrefixAccumulator group, String indexKey, List<String> members) {
        if (members == null) return;
        for (String member : members) {
            long value = parsedMultiplier(member);
            if (value > MAX_MULTIPLIER) {
                group.truncated = true;
                continue;
            }
            String multiplier = displayMultiplier(member, null);
            addListKey(group, multiplier, multiplier, listKeyFor(indexKey, multiplier));
        }
    }

    private void addListKey(PrefixAccumulator group, String id, String multiplier, String listKey) {
        if (listKey == null || listKey.isBlank()) return;
        MultiplierAccumulator row = group.rows.computeIfAbsent(id == null ? multiplier : id,
            ignored -> new MultiplierAccumulator(multiplier, listKey));
        if (row.redisKey == null || row.redisKey.isBlank()) row.redisKey = listKey;
        if (row.multiplier == null || row.multiplier.isBlank()) row.multiplier = multiplier;
    }

    private String listKeyFor(String indexKey, String member) {
        String padded = padMultiplier(member);
        if (indexKey.startsWith("PerKeyList_")) {
            return logKey("BetLog:", indexKey.substring("PerKeyList_".length()), padded);
        }
        if (indexKey.startsWith("MaryKeyList_")) {
            return logKey("MaryLog:", indexKey.substring("MaryKeyList_".length()), padded);
        }
        if (indexKey.contains(":pool-index:")) {
            return indexKey.replace(":pool-index:", ":pool:") + ":" + stripLeadingZeros(member);
        }
        return indexKey + ":" + padded;
    }

    private String logKey(String family, String id, String padded) {
        int extra = id.indexOf(':');
        if (extra < 0) return family + id + ":" + padded;
        return family + id.substring(0, extra) + ":" + padded + ":" + id.substring(extra + 1);
    }

    private String padMultiplier(String member) {
        if (member == null) return "000000";
        if (!member.matches("-?\\d+")) return member;
        try {
            long value = Long.parseLong(member);
            if (value < 0) return member;
            return String.format(Locale.ROOT, "%06d", value);
        } catch (NumberFormatException ignored) {
            return member;
        }
    }

    private List<PrefixGroup> finalizeGroups(Map<String, PrefixAccumulator> groups) {
        List<PrefixGroup> result = new ArrayList<>();
        for (PrefixAccumulator group : groups.values()) {
            if (group.rows.isEmpty() && group.indexKey == null) continue;
            List<MultiplierRow> rows = new ArrayList<>();
            long keyCount = group.indexKey == null ? 0 : 1;
            for (MultiplierAccumulator row : group.rows.values()) {
                keyCount++;
                rows.add(new MultiplierRow(row.multiplier, null, row.redisKey, null));
            }
            rows.sort(Comparator
                .comparingLong((MultiplierRow row) -> parseMultiplier(row.multiplier()))
                .thenComparing(MultiplierRow::multiplier, Comparator.nullsLast(String::compareTo)));
            result.add(new PrefixGroup(group.prefix, group.indexKey, group.indexType == null ? "list" : group.indexType,
                rows.size(), 0, keyCount, null, rows));
        }
        result.sort(Comparator.comparingInt((PrefixGroup group) -> prefixRank(group.prefix()))
            .thenComparing(PrefixGroup::prefix, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private Map<String, Long> llenAll(CpgameRedisClient redis, List<String> keys) throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int offset = 0; offset < keys.size(); offset += PIPELINE) {
            List<String> batch = keys.subList(offset, Math.min(keys.size(), offset + PIPELINE));
            List<String[]> commands = new ArrayList<>(batch.size());
            for (String key : batch) commands.add(new String[]{"LLEN", key});
            List<Object> values = redis.pipeline(commands);
            for (int i = 0; i < batch.size(); i++) {
                Object value = values.get(i);
                result.put(batch.get(i), value instanceof Long number ? number : 0L);
            }
        }
        return result;
    }

    private Map<String, Long> memoryAll(CpgameRedisClient redis, List<String> keys, Map<String, Long> counts)
        throws IOException {
        if (keys == null || keys.isEmpty()) return Map.of();
        List<String> sample = sampleKeysForMemory(keys, counts);
        if (sample.isEmpty()) return Map.of();
        List<Integer> samples = new ArrayList<>(sample.size());
        for (String key : sample) samples.add(memorySampleCount(counts.getOrDefault(key, 0L)));
        try {
            return redis.memoryUsage(sample, samples);
        } catch (RuntimeException error) {
            throw new IOException(error);
        }
    }

    static boolean memoryCommandUnsupported(Throwable error) {
        String text = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return text.contains("unknown command") || text.contains("unknown subcommand");
    }

    private int prefixRank(String prefix) {
        if (prefix.startsWith("PerKeyList_")) return 0;
        if (prefix.startsWith("MaryKeyList_")) return 1;
        if (prefix.startsWith("pool:")) return 2;
        return 3;
    }

    private long parseMultiplier(String value) {
        try { return Long.parseLong(stripLeadingZeros(value)); }
        catch (RuntimeException ignored) { return Long.MAX_VALUE; }
    }

    private String asText(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return value == null ? null : value.toString();
    }

    private CpgameRedisClient connect(RedisTarget target) throws IOException {
        return new CpgameRedisClient(target.host, target.port, target.password, target.database, 5000, 30000);
    }

    private RedisTarget discover(String directoryName) {
        Properties properties = firstProperties(List.of(
            root.resolve("generator").resolve(directoryName).resolve("dist").resolve("generator.properties"),
            root.resolve("generator").resolve(directoryName).resolve("dist").resolve("loader.properties"),
            root.resolve("server-api").resolve(directoryName).resolve("dist").resolve("controller.properties"),
            root.resolve("server-api").resolve(directoryName).resolve("dist").resolve("server.properties"),
            root.resolve("server-api").resolve(directoryName).resolve("dist").resolve("application.properties")
        ));
        if (properties == null) return null;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        addLong(ids, properties.getProperty("redis.game-id"));
        if (ids.isEmpty()) {
            addLong(ids, properties.getProperty("game.raw-id"));
            addLong(ids, properties.getProperty("game.id"));
            Matcher matcher = LEADING_ID.matcher(directoryName);
            if (matcher.find()) addLong(ids, matcher.group(1));
        }
        long gameId = ids.isEmpty() ? 0L : ids.iterator().next();
        String host = text(properties, "redis.host", "127.0.0.1");
        int port = integer(properties, "redis.port", 6379);
        int database = integer(properties, "redis.database", 15);
        String password = text(properties, "redis.password", "");
        String namespace = text(properties, "redis.namespace", "");
        return new RedisTarget(directoryName, gameId, List.copyOf(ids), host, port, database, password, namespace);
    }

    private Properties firstProperties(List<Path> files) {
        for (Path file : files) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
                return properties;
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private void addLong(Set<Long> ids, String value) {
        if (value == null || value.isBlank()) return;
        try { ids.add(Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { }
    }

    private String text(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private int integer(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void requireSafeDirectory(String value) {
        if (value == null || !SAFE_DIRECTORY.matcher(value).matches()
            || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("游戏目录名无效");
        }
    }

    private String concise(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record RedisTarget(String directoryName, long gameId, List<Long> gameIds, String host, int port,
                               int database, String password, String namespace) { }

    private static final class PrefixAccumulator {
        final String prefix;
        final Map<String, MultiplierAccumulator> rows = new LinkedHashMap<>();
        String indexKey;
        String indexType;
        boolean truncated;

        PrefixAccumulator(String prefix) { this.prefix = prefix; }
    }

    private static final class MultiplierAccumulator {
        String multiplier;
        String redisKey;

        MultiplierAccumulator(String multiplier, String redisKey) {
            this.multiplier = multiplier;
            this.redisKey = redisKey;
        }
    }

    record MultiplierRow(String multiplier, Long count, String redisKey, Long memoryBytes) { }

    record CountBatch(boolean ok, String message, Map<String, Long> counts) { }

    record MemoryBatch(boolean ok, String message, Map<String, Long> memory, boolean memorySupported) { }

    record MemberItem(int index, String value, boolean truncated) { }

    record MemberPage(boolean ok, String key, long total, int offset, int limit, List<MemberItem> members, String message) { }

    record DeleteResult(boolean ok, String message, int deletedLists, int removedIndexMembers) { }

    record PrefixGroup(String prefix, String indexKey, String indexType, int multiplierCount,
                       long memberCount, long keyCount, Long memoryBytes, List<MultiplierRow> multipliers) { }

    record CacheSnapshot(boolean ok, String directoryName, long gameId, String redisHost, int redisPort,
                         int redisDatabase, String message, long totalKeys, long totalMembers,
                         Long memoryBytes, List<PrefixGroup> prefixes) {
        static CacheSnapshot failure(String directory, long gameId, String host, int port, int database, String message) {
            return new CacheSnapshot(false, directory, gameId, host, port, database, message, 0, 0, null, List.of());
        }
    }
}
