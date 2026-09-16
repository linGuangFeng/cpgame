package com.cpgame.coinmastergo.api;

import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.model.HistoryRecord;
import com.cpgame.coinmastergo.model.PlayerSession;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;
import com.cpgame.coinmastergo.service.GameSessionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProtocolCodec {
    private final GameSessionService sessions;

    public ProtocolCodec(GameSessionService sessions) { this.sessions = sessions; }

    public Map<String, Object> auth(PlayerSession session) {
        Map<String, Object> player = linked("balance", session.balance.setScale(2).toPlainString(), "id", session.playerId);
        return linked("player", player, "token", session.token,
                "ping", linked("enable", 0, "seconds", 0),
                "rc", linked("on", 0, "v", 2), "gc", linked("os", 1, "om", 1));
    }

    public Map<String, Object> config(PlayerSession session) {
        SpinStep last = sessions.lastOrNeutral(session);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("auto", List.of(10, 30, 50, 100, 500));
        config.put("bll", GameRules.BET_LEVELS);
        config.put("bsl", GameRules.BET_SIZES);
        config.put("cc", "BRL");
        config.put("cs", "R$");
        config.put("dbl", new BigDecimal("0.5"));
        config.put("dbs", new BigDecimal("0.02"));
        config.put("last", configLast(session, last));
        config.put("ls", lastSummary(session, last));
        config.put("spl", paytable());
        config.put("ts", Instant.now().getEpochSecond());
        return config;
    }

    /** Config restores a historical step: unlike Spin, its wmkl entries are objects. */
    private Map<String, Object> configLast(PlayerSession session, SpinStep last) {
        RoundPlan round = session.activeRound != null ? session.activeRound
                : session.history.isEmpty() ? null : session.history.getFirst().round;
        int betLevel = round == null ? 1 : round.betLevel;
        BigDecimal betSize = round == null ? new BigDecimal("0.02") : round.betSize;
        List<Map<String, Object>> matches = new ArrayList<>();
        for (WinMatch match : CoinMasterResultUtil.evaluate(last.rskl, betLevel, betSize, last.rpx).matches()) {
            matches.add(linked("sk", match.symbol, "wa", match.win.setScale(2).toPlainString(),
                    "wmk", match.coordinates));
        }
        return linked("ba", last.ba, "bl", betLevel, "bs", betSize,
                "frwa", last.frwa, "fsn", last.fsn, "gfl", last.gfl, "gt", last.gt,
                "nfsc", last.nfsc, "pb", last.pb, "rpx", last.rpx, "rskl", last.rskl,
                "rwa", last.rwa, "small_game_type", last.small_game_type, "ss", last.ss,
                "wa", last.wa, "wmkl", matches, "wskl", last.wskl);
    }

    private Map<String, Object> lastSummary(PlayerSession session, SpinStep last) {
        List<String> expandedSymbols = last.rskl.stream().map(this::expandedSymbol).toList();
        return linked("balance_after", last.pb, "bet_amount", last.ba, "bet_level", 1,
                "bet_size", new BigDecimal("0.02"), "cc", "BRL", "chessboard_key", "CB0001",
                "created_at", Instant.now().getEpochSecond(), "cs", "R$", "game_type", last.gt,
                "rand_symbol_key_list", expandedSymbols, "win_payline_list", List.of(),
                "win_symbol_key_list", last.wskl);
    }

    private String expandedSymbol(String symbol) {
        if (symbol.startsWith("H")) return "S" + String.format("%05d", Integer.parseInt(symbol.substring(1)));
        if (symbol.equals("WILD")) return "WILD";
        return "SC";
    }

    private Map<String, Object> paytable() {
        Map<String, Object> result = new LinkedHashMap<>();
        GameRules.PAYING_SYMBOLS.forEach(symbol -> result.put(symbol, GameRules.PAYTABLE.get(symbol)));
        result.put("SC", Map.of("3", 0, "4", 0, "5", 0));
        result.put("WILD", Map.of("3", 0, "4", 0, "5", 0));
        return result;
    }

    public Map<String, Object> historyList(PlayerSession session, int pageIndex, long beginAt, long endAt) {
        List<HistoryRecord> filtered = session.history.stream()
                .filter(record -> (beginAt <= 0 || record.round.createdAt >= beginAt)
                        && (endAt <= 0 || record.round.createdAt <= endAt))
                .sorted(Comparator.comparingLong((HistoryRecord r) -> r.round.createdAt).reversed())
                .toList();
        int pageSize = 10;
        int from = Math.min(Math.max(pageIndex - 1, 0) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HistoryRecord record : filtered.subList(from, to)) {
            RoundPlan r = record.round;
            rows.add(linked("ba", plain(r.betAmount), "baf", plain(r.postDebitBalance), "bid", r.paidBid,
                    "ca", r.createdAt, "fe", 0, "gm", null, "gt", GameRules.GAME_PROTOCOL_ID,
                    "tis", r.transferId, "wa", plain(r.totalWin)));
        }
        BigDecimal totalBet = filtered.stream().map(record -> record.round.betAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWin = filtered.stream().map(record -> record.round.totalWin).reduce(BigDecimal.ZERO, BigDecimal::add);
        return linked("ba", plain(totalBet), "end", to >= filtered.size() ? 1 : 0,
                "lc", filtered.size(), "ll", rows, "wa", plain(totalWin));
    }

    public Map<String, Object> historyDetail(PlayerSession session, String transferId) {
        HistoryRecord record = session.history.stream().filter(item -> item.round.transferId.equals(transferId))
                .findFirst().orElseThrow(() -> new com.cpgame.coinmastergo.service.GameException(404, "history transfer_id not found"));
        RoundPlan round = record.round;
        List<Map<String, Object>> base = new ArrayList<>();
        List<Map<String, Object>> free = new ArrayList<>();
        int ordinal = 0;
        for (RoundDelivery delivery : round.deliveries) {
            for (SpinStep step : delivery.steps) {
                Map<String, Object> encoded = historyStep(round, step, ordinal++);
                if (step.gt == 2) free.add(encoded); else base.add(encoded);
            }
        }
        Map<String, Object> detail = linked("baf", round.postDebitBalance, "bid", round.paidBid, "bsl", base);
        if (!free.isEmpty()) detail.put("fsl", free);
        return detail;
    }

    private Map<String, Object> historyStep(RoundPlan round, SpinStep step, int ordinal) {
        List<Map<String, Object>> matchObjects = new ArrayList<>();
        CoinMasterResultUtil.Evaluation evaluated = CoinMasterResultUtil.evaluate(
                step.rskl, round.betLevel, round.betSize, step.rpx);
        for (WinMatch match : evaluated.matches()) {
            matchObjects.add(linked("sk", match.symbol, "wa", match.win.setScale(2).toPlainString(), "wmk", match.coordinates));
        }
        String bid = ordinal == 0 ? round.paidBid : round.paidBid + "-" + ordinal;
        return linked("ba", step.ba, "bid", bid, "bl", round.betLevel, "bs", round.betSize,
                "ca", round.createdAt, "frwa", step.frwa, "fsn", step.fsn, "gfl", step.gfl,
                "gt", step.gt, "nfsc", step.nfsc, "pb", step.pb, "rpx", step.rpx,
                "rskl", step.rskl, "rwa", step.rwa, "small_game_type", step.small_game_type,
                "ss", step.ss, "wa", step.wa, "wmkl", matchObjects, "wskl", step.wskl);
    }

    private String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private Map<String, Object> linked(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }
}
