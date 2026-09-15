package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Offline CLI that writes complete-round board facts into project-compatible Redis LIST/ZSET keys. */
public final class RedisPackCli {
    private static final long SOURCE_GAME_ID = 1809L;

    private RedisPackCli() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Files.createDirectories(config.output());
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder jsonl = new StringBuilder();
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        Stats normal = new Stats(), special = new Stats();
        Random random = new SecureRandom();

        generateNaturally(config, factory, codec, mapper, jsonl, resp, normal, special, random);

        byte[] jsonBytes = jsonl.toString().getBytes(StandardCharsets.UTF_8);
        byte[] respBytes = resp.toByteArray();
        Files.write(config.output().resolve("result-pack.jsonl"), jsonBytes);
        Files.write(config.output().resolve("redis-import.resp"), respBytes);
        ObjectNode manifest = manifest(config, normal, special, jsonBytes, respBytes, mapper);
        Files.writeString(config.output().resolve("manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8);
        Files.writeString(config.output().resolve("README.md"), readme(config), StandardCharsets.UTF_8);
        System.out.printf("REDIS_PACK_OK sourceGameId=%d redisGameId=%d normal=%d special=%d maxConsecutiveWins=%d%n",
                SOURCE_GAME_ID, config.redisGameId(), normal.count, special.count,
                Math.max(normal.maxConsecutiveWins, special.maxConsecutiveWins));
    }

    private static void generateNaturally(Config config, CompleteRoundFactory factory,
                                          CompleteRoundCodec codec, ObjectMapper mapper,
                                          StringBuilder jsonl, ByteArrayOutputStream resp,
                                          Stats normal, Stats special, Random random) throws IOException {
        while (normal.count < config.normalCount() || special.count < config.specialCount()) {
            while (true) {
                CompleteRoundFactory.GeneratedRound generated;
                try {
                    generated = factory.generate(random, false, config.maxConsecutiveWins(), config.maxMarySpins());
                } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                    normal.skippedOverlongRounds++;
                    continue;
                }
                boolean specialMode = RedisDirectLoader.isNaturalSpecial(generated.fact());
                Stats stats = specialMode ? special : normal;
                int target = specialMode ? config.specialCount() : config.normalCount();
                if (stats.count >= target) continue;
                int ratio = exactRatio(generated.multiplier());
                if (ratio < 0) throw new IllegalStateException("negative multiplier");
                int maxWinMultiplier = specialMode ? config.maryMaxWinMultiplier() : config.normalMaxWinMultiplier();
                if (ratio > maxWinMultiplier) continue;
                if (stats.ratios.getOrDefault(ratio, 0) >= config.maxMembersPerMultiplier()) continue;
                String payload = codec.encode(generated.fact());
                RoundVerification verified = codec.verify(payload, config.maxConsecutiveWins(), false,
                        config.maxMarySpins());
                if (verified.multiplier().compareTo(generated.multiplier()) != 0) {
                    throw new IllegalStateException("codec recomputation mismatch");
                }
                String indexKey = specialMode ? maryIndex(config.redisGameId()) : normalIndex(config.redisGameId());
                String listKey = specialMode ? maryList(config.redisGameId(), ratio) : normalList(config.redisGameId(), ratio);
                writeResp(resp, "ZADD", indexKey, Integer.toString(ratio), Integer.toString(ratio));
                writeResp(resp, "RPUSH", listKey, payload);
                writeResp(resp, "LTRIM", listKey, "-" + config.maxMembersPerMultiplier(), "-1");
                ObjectNode row = mapper.createObjectNode();
                row.put("mode", specialMode ? "SPECIAL" : "NORMAL");
                row.put("ratio", ratio);
                row.put("key", listKey);
                row.put("member", payload);
                jsonl.append(mapper.writeValueAsString(row)).append('\n');
                stats.accept(ratio, verified);
                break;
            }
        }
    }

    private static int exactRatio(BigDecimal multiplier) {
        try { return multiplier.stripTrailingZeros().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalStateException("non-integer multiplier: " + multiplier, ex); }
    }

    private static ObjectNode manifest(Config c, Stats normal, Stats special, byte[] json, byte[] resp,
                                       ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", CompleteRoundFact.VERSION);
        root.put("sourceGameId", SOURCE_GAME_ID);
        root.put("redisGameId", c.redisGameId());
        root.put("cacheUnit", "ONE_COMPLETE_ROUND_PER_PAID_BET");
        root.put("memberFormat", "ASCII: independent no-win Spin = #; otherwise 68 chars per page, pages concatenated, spins separated by |");
        root.put("normalIndexKey", normalIndex(c.redisGameId()));
        root.put("specialIndexKey", maryIndex(c.redisGameId()));
        root.put("generationPolicy", "NATURAL_RANDOM; zero and positive multipliers stored by actual ratio; independent no-win Spin encoded as #; newest members retained");
        root.put("maxMembersPerMultiplier", c.maxMembersPerMultiplier());
        root.put("maxConsecutiveWinsConfigured", c.maxConsecutiveWins());
        root.put("maxMarySpinsConfigured", c.maxMarySpins());
        root.put("normalMaxWinMultiplier", c.normalMaxWinMultiplier());
        root.put("maryMaxWinMultiplier", c.maryMaxWinMultiplier());
        root.put("maxConsecutiveWinsObserved", Math.max(normal.maxConsecutiveWins, special.maxConsecutiveWins));
        root.put("normalMembers", normal.count).put("specialMembers", special.count)
                .put("ordinaryLossMembers", normal.ratios.getOrDefault(0, 0));
        root.put("normalActualMinMul", normal.min()).put("normalActualMaxMul", normal.max());
        root.put("specialActualMinMul", special.min()).put("specialActualMaxMul", special.max());
        root.put("normalSkippedZeroRounds", 0);
        root.put("specialSkippedZeroRounds", 0);
        root.put("normalSkippedOverlongRounds", normal.skippedOverlongRounds);
        root.put("specialSkippedOverlongRounds", special.skippedOverlongRounds);
        root.put("totalSpins", normal.spins + special.spins).put("totalPages", normal.pages + special.pages);
        root.put("randomSource", "SecureRandom").put("codecVerified", true)
                .put("terminalNoWinVerified", true).put("cascadeContinuityVerified", true);
        root.set("normalMultiplierDistribution", mapper.valueToTree(normal.ratios));
        root.set("specialMultiplierDistribution", mapper.valueToTree(special.ratios));
        root.put("jsonlSha256", sha256(json)).put("respSha256", sha256(resp));
        return root;
    }

    private static String readme(Config c) {
        return "# Freedom Day 1809 Redis Pack\n\n"
                + "每个 Redis LIST member 是一局从付费开始到全部连消/免费局结束的完整事实牌面；中奖结果由 `CompleteRoundCodec` 使用 `FreedomDayResultUtil` 反推。\n\n"
                + "生成策略：使用 SecureRandom 自然随机生成完整局；零倍及正倍数按 Util 反推的实际倍率写入对应 Key；仅无上下牌面关联的独立无奖 Spin 编为 #，连消结束盘和免费触发盘保留，不筛选或追逐指定倍率。每个倍率本次最多生成 `" + c.maxMembersPerMultiplier() + "` 局，导入后通过 LTRIM 只保留该 Key 最新的相同数量。最大连续中奖 `" + c.maxConsecutiveWins() + "`，单个完整局最大玛丽免费 Spin `" + c.maxMarySpins() + "`，超限整局丢弃。\n\n"
                + "```powershell\n"
                + "mvn.cmd -q test\n"
                + "mvn.cmd -q exec:java -Dexec.mainClass=com.cpgame.replica.freedomday.RedisPackCli -Dexec.args=\"--output redis-pack/1809-mac-Freedom-Day"
                + " --normal-count " + c.normalCount() + " --special-count " + c.specialCount() + " --redis-game-id " + c.redisGameId()
                + " --max-consecutive-wins " + c.maxConsecutiveWins()
                + " --max-mary-spins " + c.maxMarySpins()
                + " --normal-max-win-multiplier " + c.normalMaxWinMultiplier()
                + " --mary-max-win-multiplier " + c.maryMaxWinMultiplier()
                + " --max-members-per-multiplier " + c.maxMembersPerMultiplier() + "\"\n"
                + "Get-Content redis-pack/1809-mac-Freedom-Day/redis-import.resp -Raw | redis-cli --pipe\n"
                + "```\n\n"
                + "普通索引：`" + normalIndex(c.redisGameId()) + "`；特殊索引：`" + maryIndex(c.redisGameId()) + "`。\n";
    }

    static String normalIndex(long id) { return String.format("PerKeyList_%09d", id); }
    static String maryIndex(long id) { return String.format("MaryKeyList_%09d", id); }
    static String normalList(long id, int ratio) { return String.format("BetLog:0%08d:%06d", id, ratio); }
    static String maryList(long id, int ratio) { return String.format("MaryLog:%09d:%06d", id, ratio); }

    private static void writeResp(ByteArrayOutputStream out, String... args) throws IOException {
        out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes); out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static final class Stats {
        int count, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, maxConsecutiveWins, spins, pages,
                skippedOverlongRounds;
        final Map<Integer, Integer> ratios = new TreeMap<>();
        void accept(int ratio, RoundVerification verification) {
            count++; min = Math.min(min, ratio); max = Math.max(max, ratio);
            maxConsecutiveWins = Math.max(maxConsecutiveWins, verification.maxConsecutiveWins());
            spins += verification.spins(); pages += verification.pages(); ratios.merge(ratio, 1, Integer::sum);
        }
        int min() { return count == 0 ? 0 : min; }
        int max() { return count == 0 ? 0 : max; }
    }

    record Config(Path output, int normalCount, int specialCount, long redisGameId,
                  int maxConsecutiveWins, int maxMarySpins, int maxMembersPerMultiplier,
                  int normalMaxWinMultiplier, int maryMaxWinMultiplier) {
        static Config parse(String[] args) {
            Map<String, String> options = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length) throw usage();
                options.put(args[i].substring(2), args[i + 1]);
            }
            require(options, "output", "normal-count", "special-count");
            Config c = new Config(Path.of(options.get("output")).toAbsolutePath().normalize(),
                    Integer.parseInt(options.get("normal-count")), Integer.parseInt(options.get("special-count")),
                    Long.parseLong(options.getOrDefault("redis-game-id", Long.toString(SOURCE_GAME_ID))),
                    Integer.parseInt(options.getOrDefault("max-consecutive-wins", "10")),
                    Integer.parseInt(options.getOrDefault("max-mary-spins", "30")),
                    Integer.parseInt(options.getOrDefault("max-members-per-multiplier", "300")),
                    Integer.parseInt(options.getOrDefault("normal-max-win-multiplier", "20000")),
                    Integer.parseInt(options.getOrDefault("mary-max-win-multiplier", "20000")));
            if (c.normalCount < 0 || c.specialCount < 0 || c.normalCount + c.specialCount == 0) throw usage();
            if (c.redisGameId <= 0 || c.maxConsecutiveWins < 1 || c.maxMarySpins < 1
                    || c.normalMaxWinMultiplier < 1 || c.maryMaxWinMultiplier < 1
                    || c.maxMembersPerMultiplier < 1) throw usage();
            return c;
        }
        private static void require(Map<String, String> values, String... names) {
            for (String name : names) if (!values.containsKey(name)) throw usage();
        }
        private static IllegalArgumentException usage() {
            return new IllegalArgumentException("usage: --output DIR --normal-count N --special-count N "
                    + "[--redis-game-id ID] [--max-consecutive-wins N] [--max-mary-spins N] "
                    + "[--normal-max-win-multiplier N] [--mary-max-win-multiplier N] "
                    + "[--max-members-per-multiplier N]");
        }
    }
}
