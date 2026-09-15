package com.cpgame.clubgoddess.api.state;

import com.cpgame.clubgoddess.core.GameModels.GameResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SessionState {
    public String token;
    public String nickname = "Club Goddess Player";
    public String currency = "BRL";
    public String currencySymbol = "R$";
    public BigDecimal balance;
    public GameResult initialSnapshot;
    public GameResult lastResult;
    public String lastRoundKey;
    public int deliveryIndex;
    public String pendingMember;
    public BigDecimal pendingStartBalance;
    public BigDecimal pendingBet;
    public int pendingLevel;
    public long createdAt;
    public long updatedAt;
    public List<HistoryOrder> history = new ArrayList<>();
    public Map<String, IdempotencyRecord> idempotency = new LinkedHashMap<>();

    public SessionState() {}

    public static class HistoryOrder {
        public String roundKey;
        public int deliveryIndex;
        public long createdAt;
        public GameResult result;
        public List<GameResult> results = new ArrayList<>();
        public HistoryOrder() {}
        public HistoryOrder(String roundKey, int deliveryIndex, long createdAt, GameResult result) {
            this.roundKey=roundKey; this.deliveryIndex=deliveryIndex; this.createdAt=createdAt; this.result=result;
            this.results.add(result);
        }
    }

    public static class IdempotencyRecord {
        public String roundKey;
        public int deliveryIndex;
        public GameResult result;
        public IdempotencyRecord() {}
        public IdempotencyRecord(String roundKey, int deliveryIndex, GameResult result) {
            this.roundKey=roundKey; this.deliveryIndex=deliveryIndex; this.result=result;
        }
    }
}
