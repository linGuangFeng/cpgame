package com.cpgame.luckydragon.api;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.RoundRequest;
import com.cpgame.luckydragon.core.SpinResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 会话、余额、幂等键、History 与完整 Round 均只属于当前 Controller 类加载器。 */
final class LuckyDragonService {
    private final GameRuleCore rules = new GameRuleCore();
    private final IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
    private final RoundProvider roundProvider;
    private final Map<String,SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String,SessionState> launchAliases = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() << 20);
    private final Path stateDirectory;
    private final BigDecimal initialBalance;

    LuckyDragonService(Path stateDirectory, BigDecimal initialBalance, Properties config) throws IOException {
        this(stateDirectory, initialBalance, RedisRoundStore.connect(config));
    }

    LuckyDragonService(Path stateDirectory, BigDecimal initialBalance, RoundProvider roundProvider) throws IOException {
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.initialBalance = money(initialBalance);
        this.roundProvider = java.util.Objects.requireNonNull(roundProvider, "roundProvider");
        Files.createDirectories(this.stateDirectory);
    }

    Map<String,Object> auth(Map<String,String> form) { return auth(form, null); }

    synchronized Map<String,Object> auth(Map<String,String> form, String launchAlias) {
        requireGame(form);
        String alias = first(form.get("t"), form.get("token"), launchAlias);
        String aliasHash = alias == null ? null : sha256(alias);
        SessionState existing = aliasHash == null ? null : launchAliases.get(aliasHash);
        if (existing == null && aliasHash != null) existing = restore(aliasHash);
        if (existing != null) return authEnvelope(existing);
        String token = UUID.randomUUID().toString();
        long playerId = ids.incrementAndGet();
        SessionState state = new SessionState(token, playerId, initialBalance,
            aliasHash == null ? "player-" + playerId : "alias-" + aliasHash);
        sessions.put(token, state);
        if (aliasHash != null) {
            launchAliases.put(aliasHash, state);
            persistUnchecked(state);
        }
        return authEnvelope(state);
    }

    private Map<String,Object> authEnvelope(SessionState state) {
        Map<String,Object> player = map("id", state.playerId, "balance", state.balance.toPlainString());
        return ok(map("player", player, "token", state.token, "ping", 30, "rc", List.of(), "gc", List.of()));
    }

    synchronized Map<String,Object> config(Map<String,String> form) {
        SessionState state = session(form);
        Map<String,Object> data = map(
            "auto", GameRuleCore.AUTO_SPINS,
            "bll", GameRuleCore.BET_LEVELS,
            "bsl", GameRuleCore.BET_SIZES,
            "cc", "BRL", "cs", "R$", "dbl", 20, "dbs", new BigDecimal("0.5"),
            "last", state.rounds.isEmpty() ? defaultLast(state) : detail(state.rounds.get(state.rounds.size() - 1)),
            "spl", GameRuleCore.SYMBOL_PAY_MULTIPLIERS,
            "ts", Instant.now().getEpochSecond());
        return ok(data);
    }

    synchronized Map<String,Object> spin(Map<String,String> form, String idempotencyHeader) throws IOException {
        requireGame(form);
        SessionState state = session(form);
        String idempotencyRaw = first(form.get("idempotency_key"), form.get("request_id"), idempotencyHeader);
        String idempotency = idempotencyRaw == null ? null : sha256(idempotencyRaw);
        if (idempotency != null && state.idempotency.containsKey(idempotency)) {
            return ok(spinData(state.idempotency.get(idempotency)));
        }
        BigDecimal betSize = decimal(form.get("bs"), "bs");
        int betLevel = integer(form.get("bl"), "bl");
        if (!GameRuleCore.BET_SIZES.contains(betSize.stripTrailingZeros())) throw new ApiException(400, "unsupported bs");
        if (!GameRuleCore.BET_LEVELS.contains(betLevel)) throw new ApiException(400, "unsupported bl");
        RoundRequest request = new RoundRequest(betSize, betLevel);
        if (state.balance.compareTo(request.paidBet()) < 0) throw new ApiException(409, "insufficient balance");
        ClaimedRound claimed;
        try { claimed = roundProvider.claim(request); }
        catch (Exception error) { throw new ApiException(503, "Redis complete-Round cache unavailable: " + error.getMessage()); }
        SpinResult candidate = claimed.result();
        verifier.verify(request, candidate);
        long now = Instant.now().getEpochSecond();
        String transferId = Long.toUnsignedString(ids.incrementAndGet());
        BigDecimal balanceAfter = money(state.balance.subtract(request.paidBet()).add(candidate.payout()));
        RoundState round = new RoundState(claimed.roundKey(), transferId, request, candidate,
            balanceAfter, now, 0, true, claimed.member());
        state.balance = balanceAfter;
        state.rounds.add(round);
        if (idempotency != null) state.idempotency.put(idempotency, round);
        persist(state);
        return ok(spinData(round));
    }

    synchronized Map<String,Object> historyList(Map<String,String> form) {
        requireGame(form);
        SessionState state = session(form);
        int page = form.get("page_index") == null ? 1 : integer(form.get("page_index"), "page_index");
        if (page < 1) throw new ApiException(400, "page_index must be positive");
        long beginAt = optionalEpoch(form.get("begin_at"), Long.MIN_VALUE, "begin_at");
        long endAt = optionalEpoch(form.get("end_at"), Long.MAX_VALUE, "end_at");
        if (beginAt > endAt) throw new ApiException(400, "begin_at must not exceed end_at");
        List<RoundState> ordered = state.rounds.stream()
            .filter(value -> value.createdAt >= beginAt && value.createdAt <= endAt)
            .sorted(Comparator.comparingLong((RoundState value) -> value.createdAt).reversed()
                .thenComparing(value -> value.transferId, Comparator.reverseOrder()))
            .toList();
        int from = Math.min((page - 1) * 20, ordered.size());
        int to = Math.min(from + 20, ordered.size());
        List<Map<String,Object>> rows = ordered.subList(from, to).stream().map(this::listRow).toList();
        BigDecimal totalBet = ordered.stream().map(value -> value.request.paidBet()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWin = ordered.stream().map(value -> value.result.payout()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return ok(map("ba", totalBet.toPlainString(), "wa", totalWin.toPlainString(), "lc", ordered.size(), "ll", rows,
            "end", to >= ordered.size() ? 1 : 0));
    }

    synchronized Map<String,Object> historyDetail(Map<String,String> form) {
        requireGame(form);
        SessionState state = session(form);
        String transferId = form.get("transfer_id");
        RoundState round = state.rounds.stream().filter(value -> value.transferId.equals(transferId)).findFirst()
            .orElseThrow(() -> new ApiException(404, "history round not found"));
        return ok(detail(round));
    }

    synchronized Map<String,Object> balance(Map<String,String> form) {
        SessionState state = session(form);
        return ok(map("balance", state.balance.toPlainString(), "cc", "BRL", "cs", "R$"));
    }

    Map<String,Object> ping(Map<String,String> form) {
        session(form);
        return ok(map("ts", Instant.now().getEpochSecond()));
    }

    Map<String,Object> status() {
        return map("status", "UP", "gameId", 42, "rulesHash", GameRuleCore.RULES_HASH,
            "roundSource", "redis-db15-complete-round", "runtimeDeal", false,
            "managedProcessPid", ProcessHandle.current().pid(),
            "controllerClassLoaderIdentity", Integer.toHexString(System.identityHashCode(getClass().getClassLoader())),
            "sessionCount", sessions.size());
    }

    private Map<String,Object> spinData(RoundState round) {
        return map("ba", round.request.paidBet(), "bl", round.request.betLevel(), "bs", round.request.betSize(),
            "ca", round.createdAt, "gt", 1, "pb", round.balanceAfter.toPlainString(),
            "rpx", round.result.reelMultiplier(), "rskl", round.result.symbols(),
            "wa", round.result.payout(), "wsk", round.result.winningSymbol(),
            "roundKey", round.roundKey, "deliveryIndex", round.deliveryIndex,
            "terminal", round.terminal, "_source", "redis-db15-complete-round");
    }

    private Map<String,Object> listRow(RoundState round) {
        return map("ba", round.request.paidBet().toPlainString(), "baf", round.balanceAfter.toPlainString(),
            "bid", round.roundKey, "ca", round.createdAt, "fe", 0, "gm", null, "gt", 42,
            "tis", round.transferId, "wa", round.result.payout().toPlainString());
    }

    private Map<String,Object> detail(RoundState round) {
        Map<String,Object> data = new LinkedHashMap<>(spinData(round));
        data.put("baf", round.balanceAfter.toPlainString());
        data.put("balance_after", round.balanceAfter.toPlainString());
        data.put("bet_level", round.request.betLevel());
        data.put("bet_size", round.request.betSize());
        data.put("bid", round.roundKey);
        data.put("cc", "BRL");
        data.put("created_at", round.createdAt);
        data.put("cs", "R$");
        data.put("deliveryIndex", round.deliveryIndex);
        data.put("terminal", round.terminal);
        return data;
    }

    private Map<String,Object> defaultLast(SessionState state) {
        return map("ba", new BigDecimal("0.5"), "bl", 1, "bs", new BigDecimal("0.5"),
            "ca", Instant.now().getEpochSecond(), "gt", 1, "pb", state.balance.toPlainString(),
            "rpx", 0, "rskl", List.of("H0","H2","H3"), "wa", 0, "wsk", "");
    }

    private SessionState session(Map<String,String> form) {
        String token = form.get("t");
        SessionState state = token == null ? null : sessions.get(token);
        if (state == null) throw new ApiException(401, "invalid session token");
        return state;
    }

    private void requireGame(Map<String,String> form) {
        if (!"42".equals(form.get("gid"))) throw new ApiException(400, "gid must be 42");
    }

    private void persist(SessionState state) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# gid42 Controller state; rulesHash=" + GameRuleCore.RULES_HASH);
        lines.add("player\t" + state.playerId);
        lines.add("balance\t" + state.balance.toPlainString());
        for (RoundState round : state.rounds) {
            lines.add(String.join("\t", "round", round.roundKey, round.transferId,
                round.request.betSize().toPlainString(), Integer.toString(round.request.betLevel()),
                String.join(",", round.result.symbols()), round.result.winningSymbol(),
                Integer.toString(round.result.reelMultiplier()), round.result.payout().toPlainString(),
                round.balanceAfter.toPlainString(), Long.toString(round.createdAt),
                Integer.toString(round.deliveryIndex), Boolean.toString(round.terminal),
                Integer.toHexString(round.member.hashCode())));
        }
        for (Map.Entry<String,RoundState> entry : state.idempotency.entrySet()) {
            lines.add(String.join("\t", "idem", entry.getKey(), entry.getValue().transferId));
        }
        Path target = stateFile(state.persistenceKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private SessionState restore(String aliasHash) {
        Path source = stateFile("alias-" + aliasHash);
        if (!Files.isRegularFile(source)) return null;
        try {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            long playerId = 0;
            BigDecimal balance = null;
            List<RoundState> rounds = new ArrayList<>();
            Map<String,String> idempotencyTransfers = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                switch (fields[0]) {
                    case "player" -> playerId = Long.parseLong(fields[1]);
                    case "balance" -> balance = money(new BigDecimal(fields[1]));
                    case "round" -> {
                        if (fields.length != 14) throw new IOException("invalid persisted Round field count");
                        RoundRequest request = new RoundRequest(new BigDecimal(fields[3]), Integer.parseInt(fields[4]));
                        SpinResult result = rules.evaluate(request, List.of(fields[5].split(",", -1)), Integer.parseInt(fields[7]));
                        if (!result.winningSymbol().equals(fields[6])
                            || result.payout().compareTo(new BigDecimal(fields[8])) != 0) {
                            throw new IOException("persisted Round rule mismatch");
                        }
                        RoundState round = new RoundState(fields[1], fields[2], request, result,
                            money(new BigDecimal(fields[9])), Long.parseLong(fields[10]),
                            Integer.parseInt(fields[11]), Boolean.parseBoolean(fields[12]), "restored:" + fields[13]);
                        verifier.verify(request, result);
                        rounds.add(round);
                    }
                    case "idem" -> idempotencyTransfers.put(fields[1], fields[2]);
                    default -> throw new IOException("unknown persisted state record");
                }
            }
            if (playerId == 0 || balance == null) throw new IOException("incomplete persisted session");
            SessionState restored = new SessionState(UUID.randomUUID().toString(), playerId, balance, "alias-" + aliasHash);
            restored.rounds.addAll(rounds);
            for (Map.Entry<String,String> entry : idempotencyTransfers.entrySet()) {
                RoundState round = rounds.stream().filter(value -> value.transferId.equals(entry.getValue())).findFirst()
                    .orElseThrow(() -> new IOException("idempotency target Round missing"));
                restored.idempotency.put(entry.getKey(), round);
            }
            sessions.put(restored.token, restored);
            launchAliases.put(aliasHash, restored);
            ids.accumulateAndGet(playerId, Math::max);
            return restored;
        } catch (Exception error) {
            throw new ApiException(500, "persisted session recovery failed");
        }
    }

    private Path stateFile(String persistenceKey) {
        return stateDirectory.resolve("session-" + persistenceKey + ".tsv");
    }

    private void persistUnchecked(SessionState state) {
        try { persist(state); }
        catch (IOException error) { throw new ApiException(500, "session persistence failed"); }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BigDecimal decimal(String value, String name) {
        try { return new BigDecimal(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid " + name); }
    }

    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid " + name); }
    }

    private static long optionalEpoch(String value, long defaultValue, String name) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Long.parseLong(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid " + name); }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    private static Map<String,Object> ok(Object data) { return map("code", 200, "data", data, "info", "ok"); }

    private static Map<String,Object> map(Object... fields) {
        Map<String,Object> values = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) values.put((String) fields[index], fields[index + 1]);
        return values;
    }

    private static final class SessionState {
        final String token;
        final long playerId;
        final List<RoundState> rounds = new ArrayList<>();
        final Map<String,RoundState> idempotency = new LinkedHashMap<>();
        final String persistenceKey;
        BigDecimal balance;
        SessionState(String token, long playerId, BigDecimal balance, String persistenceKey) {
            this.token = token; this.playerId = playerId; this.balance = balance; this.persistenceKey = persistenceKey;
        }
    }

    interface RoundProvider {
        ClaimedRound claim(RoundRequest request) throws Exception;
    }

    record ClaimedRound(String roundKey, SpinResult result, String member) { }

    private record RoundState(String roundKey, String transferId, RoundRequest request, SpinResult result,
                              BigDecimal balanceAfter, long createdAt, int deliveryIndex, boolean terminal,
                              String member) { }
}
