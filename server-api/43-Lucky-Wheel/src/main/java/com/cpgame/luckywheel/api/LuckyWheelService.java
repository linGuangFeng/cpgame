package com.cpgame.luckywheel.api;

import com.cpgame.luckywheel.core.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

public final class LuckyWheelService {
    private final PersistentSessionRepository repository;
    private final RedisRoundStore roundStore;
    private final RoundDeliveryService deliveries = new RoundDeliveryService();
    private final SecureRandom tokenRandom = new SecureRandom();

    public LuckyWheelService(PersistentSessionRepository repository, RedisRoundStore roundStore) {
        this.repository = repository;
        this.roundStore = roundStore;
    }

    public String authenticate(Map<String, String> form) throws Exception {
        requireGameId(form);
        String launchKey = sha256(form.getOrDefault("t", "local-demo"));
        SessionState session = repository.byLaunchKey(launchKey);
        if (session == null) {
            session = new SessionState(launchKey, newToken());
            repository.add(session);
        }
        synchronized (session) {
            Map<String,Object> player = new LinkedHashMap<>();
            player.put("id", 43);
            player.put("balance", money(session.balance()));
            player.put("fbt", 0);
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("player", player);
            data.put("token", session.token());
            return ProtocolCodec.success(data);
        }
    }

    public String config(Map<String, String> form) {
        requireSession(form);
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("bll", List.of(1, 5, 10, 50, 100));
        data.put("bsl", List.of(1));
        data.put("dbl", 1);
        data.put("dbs", 1);
        data.put("cs", "R$");
        data.put("cc", "BRL");
        data.put("auto", List.of(10, 30, 50, 80, 1000));
        data.put("spl", Map.of());
        data.put("as", 1);
        return ProtocolCodec.success(data);
    }

    public String spin(Map<String, String> form, String idempotencyKey) throws Exception {
        SessionState session = requireSession(form);
        int bl = parseInt(form.get("bl"), "bl");
        int bs = parseInt(form.get("bs"), "bs");
        if (!List.of(1, 5, 10, 50, 100).contains(bl) || bs != 1) {
            return ProtocolCodec.error(422, "bl/bs不在已确认配置范围");
        }
        synchronized (session) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                String previous = session.idempotentResponses().get(idempotencyKey);
                if (previous != null) return previous;
            }
            BigDecimal bet = BigDecimal.valueOf((long) bl * bs);
            if (session.balance().compareTo(bet) < 0) return ProtocolCodec.error(402, "Insufficient balance");
            GameRound round;
            try { round = roundStore.claim(new RoundRequest(bl, bs, session.balance())); }
            catch (IOException error) { throw new IllegalStateException("Redis完整局缓存不可用: " + error.getMessage(), error); }
            RoundDelivery delivery = deliveries.claimNext(session, round);
            SpinResult result = delivery.result();
            session.balance(new BigDecimal(result.pb()));
            String transferId = transferId(session);
            HistoryRecord history = new HistoryRecord(transferId, "43-" + transferId, round.roundKey(), delivery.deliveryIndex(), result);
            session.history().add(history);
            String response = ProtocolCodec.success(result.toProtocolData());
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                session.idempotentResponses().put(idempotencyKey, response);
            }
            repository.save();
            return response;
        }
    }

    public void close() throws IOException { roundStore.close(); }

    public String historyList(Map<String, String> form) {
        SessionState session = requireSession(form);
        int page = Math.max(1, parseInt(form.getOrDefault("page_index", "1"), "page_index"));
        long begin = parseLong(form.getOrDefault("begin_at", "0"), 0);
        long endAt = parseLong(form.getOrDefault("end_at", Long.toString(Long.MAX_VALUE)), Long.MAX_VALUE);
        synchronized (session) {
            List<HistoryRecord> filtered = session.history().stream()
                    .filter(row -> row.result().ca() >= begin && row.result().ca() <= endAt)
                    .sorted(Comparator.comparingLong((HistoryRecord row) -> row.result().ca()).reversed())
                    .toList();
            int from = Math.min((page - 1) * 10, filtered.size());
            int to = Math.min(from + 10, filtered.size());
            List<Map<String,Object>> rows = new ArrayList<>();
            for (HistoryRecord row : filtered.subList(from, to)) rows.add(historyListRow(row));
            BigDecimal totalBet = filtered.stream().map(row -> row.result().ba()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalAward = filtered.stream().map(row -> row.result().wa()).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("ba", plain(totalBet));
            data.put("end", to >= filtered.size() ? 1 : 0);
            data.put("lc", filtered.size());
            data.put("ll", rows);
            data.put("wa", plain(totalAward));
            return ProtocolCodec.success(data);
        }
    }

    public String historyView(Map<String, String> form) {
        SessionState session = requireSession(form);
        String transferId = form.getOrDefault("transfer_id", "");
        synchronized (session) {
            HistoryRecord row = session.history().stream().filter(value -> value.transferId().equals(transferId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("history transfer_id不存在"));
            Map<String,Object> data = new LinkedHashMap<>(row.result().toProtocolData());
            data.put("baf", row.result().pb());
            data.put("balance_after", row.result().pb());
            data.put("bet_level", row.result().bl());
            data.put("bet_size", row.result().bs());
            data.put("bid", row.bid());
            data.put("cc", "BRL");
            data.put("created_at", row.result().ca());
            data.put("cs", "R$");
            return ProtocolCodec.success(data);
        }
    }

    public String balance(Map<String, String> form) {
        SessionState session = requireSession(form);
        synchronized (session) {
            return ProtocolCodec.success(Map.of("balance", money(session.balance()), "cc", "BRL", "cs", "R$"));
        }
    }

    public String ping(Map<String, String> form) {
        requireSession(form);
        return ProtocolCodec.success(Map.of());
    }

    private SessionState requireSession(Map<String, String> form) {
        requireGameId(form);
        String token = form.getOrDefault("t", "");
        SessionState session = repository.byToken(token);
        if (session == null) throw new SecurityException("无效或过期的游戏会话");
        return session;
    }

    private static void requireGameId(Map<String, String> form) {
        if (!"43".equals(form.getOrDefault("gid", "43"))) throw new IllegalArgumentException("gid必须为43");
    }
    private int parseInt(String value, String name) {
        try { return Integer.parseInt(value); } catch (Exception e) { throw new IllegalArgumentException(name + "格式错误"); }
    }
    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
    private Map<String,Object> historyListRow(HistoryRecord row) {
        SpinResult r = row.result();
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("ba", plain(r.ba())); data.put("baf", r.pb()); data.put("bid", row.bid());
        data.put("ca", r.ca()); data.put("fe", 0); data.put("gm", null); data.put("gt", 43);
        data.put("tis", row.transferId()); data.put("wa", plain(r.wa()));
        return data;
    }
    private String transferId(SessionState session) {
        return Long.toString(Instant.now().toEpochMilli()) + String.format("%04d", session.nextTransferSequence() % 10000);
    }
    private String newToken() {
        byte[] bytes = new byte[24]; tokenRandom.nextBytes(bytes);
        return "lw43-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String sha256(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String plain(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 0 ? normalized.toBigIntegerExact().toString() : normalized.toPlainString();
    }
}
