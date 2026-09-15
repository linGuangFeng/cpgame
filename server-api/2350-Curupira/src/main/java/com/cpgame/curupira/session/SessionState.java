package com.cpgame.curupira.session;

import com.cpgame.curupira.api.DemoCatalog;
import com.cpgame.curupira.api.RoundSource;
import com.cpgame.curupira.api.SpinProjector;
import com.cpgame.curupira.api.UnsupportedBehaviorException;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.RoundResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionState {
    private enum Phase { IDLE, SELECTING, FEATURE }

    private final String token;
    private final long userId;
    private final String opaqueToken;
    private final int historyLimit;
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1_000_000L + 2350);
    private BigDecimal balance;
    private Map<String, Object> lastData;
    private final List<HistoryRound> history = new ArrayList<>();
    private final Map<String, Map<String, Object>> idempotentResults = new HashMap<>();
    private int paidStarts;
    private Phase phase = Phase.IDLE;
    private CompleteRoundFact active;
    private int featureIndex;
    private BigDecimal featureTwa = BigDecimal.ZERO.setScale(2);
    private BigDecimal activeLineBet = GameRules.MINIMUM_LINE_BET;
    private int activeLevel = 1;
    private HistoryRound openHistory;

    SessionState(String token, long userId, String opaqueToken, BigDecimal balance, int historyLimit) {
        this.token = token;
        this.userId = userId;
        this.opaqueToken = opaqueToken;
        this.balance = GameRuleCore.money(balance);
        this.historyLimit = historyLimit;
    }

    public synchronized Map<String, Object> play(int type, int gameType, BigDecimal lineBet, int level,
                                                 String idempotencyKey, RoundSource source, GameRuleCore core) {
        Map<String, Object> existing = idempotentResults.get(idempotencyKey);
        if (existing != null) return existing;
        if (lineBet == null || lineBet.compareTo(GameRules.MINIMUM_LINE_BET) < 0 || level < 1) {
            throw new IllegalArgumentException("Invalid Curupira bet or level");
        }
        Map<String, Object> data = switch (type) {
            case 1 -> paid(lineBet, level, source, core);
            case 2 -> featureContinue(gameType, source);
            case 3 -> buy(gameType, lineBet, level, source);
            default -> throw new UnsupportedBehaviorException(type, gameType);
        };
        lastData = data;
        idempotentResults.put(idempotencyKey, data);
        return data;
    }

    public synchronized Map<String, Object> roomProjection(RoundSource source) {
        if (lastData == null) {
            CompleteRoundFact idle = source.peekLoss();
            FeatureStep step = idle.steps().get(0);
            lastData = SpinProjector.data(step, 0L, GameRules.MINIMUM_LINE_BET, 1,
                    BigDecimal.ZERO.setScale(2), balance, BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), balance, userId, opaqueToken, 1,
                    BigDecimal.ZERO.setScale(2), true);
        }
        return lastData;
    }

    public synchronized RoundResult spin(String idempotencyKey, BigDecimal lineBet, int level, GameRuleCore core) {
        throw new IllegalStateException("Demo spins must claim Redis members");
    }

    public synchronized RoundResult roomProjection(GameRuleCore core) {
        throw new IllegalStateException("Demo init must peek Redis members");
    }

    private Map<String, Object> paid(BigDecimal lineBet, int level, RoundSource source, GameRuleCore core) {
        if (phase != Phase.IDLE) throw new UnsupportedBehaviorException(1, 1);
        DemoCatalog.PaidSlot slot = DemoCatalog.slot(paidStarts++);
        Kind kind = slot.kind();
        CompleteRoundFact fact = kind == Kind.TRIGGER
                ? core.generateFact(Kind.TRIGGER)
                : source.claim(kind, slot.minMultiplier(), slot.maxMultiplier());
        BigDecimal bet = SpinProjector.totalBet(lineBet, level);
        if (balance.compareTo(bet) < 0) throw new GameRuleCore.InsufficientBalanceException();
        activeLineBet = lineBet;
        activeLevel = level;
        FeatureStep step = fact.steps().get(0);
        BigDecimal tw = SpinProjector.stepWin(step, lineBet, level);
        BigDecimal start = balance;
        BigDecimal change = GameRuleCore.money(tw.subtract(bet));
        balance = GameRuleCore.money(start.add(change));
        long rid = ids.incrementAndGet();
        if (kind == Kind.TRIGGER) {
            phase = Phase.SELECTING;
            featureTwa = BigDecimal.ZERO.setScale(2);
            openHistory = new HistoryRound(bet, Instant.now().getEpochSecond());
        } else {
            phase = Phase.IDLE;
            openHistory = new HistoryRound(bet, Instant.now().getEpochSecond());
        }
        Map<String, Object> data = SpinProjector.data(step, rid, lineBet, level, bet, start, tw, change, balance,
                userId, opaqueToken, 1, featureTwa, false);
        appendHistory(data, bet, change, tw);
        if (kind != Kind.TRIGGER) finishHistory();
        return data;
    }

    private Map<String, Object> featureContinue(int gameType, RoundSource source) {
        if (phase == Phase.SELECTING) {
            if (gameType != 2 && gameType != 3) throw new UnsupportedBehaviorException(2, gameType);
            active = source.claim(gameType == 2 ? Kind.FREE_EW : Kind.HOLD);
            featureIndex = 0;
            phase = Phase.FEATURE;
            featureTwa = BigDecimal.ZERO.setScale(2);
            return emitFeatureStep(2);
        }
        if (phase != Phase.FEATURE || active == null) throw new UnsupportedBehaviorException(2, gameType);
        return emitFeatureStep(2);
    }

    private Map<String, Object> buy(int gameType, BigDecimal lineBet, int level, RoundSource source) {
        if (phase != Phase.IDLE) throw new UnsupportedBehaviorException(3, gameType);
        if (gameType != 2 && gameType != 3) throw new UnsupportedBehaviorException(3, gameType);
        CompleteRoundFact fact = source.claimBuy(gameType);
        BigDecimal bet = SpinProjector.totalBet(lineBet, level);
        BigDecimal charge = GameRuleCore.money(bet.multiply(BigDecimal.valueOf(GameRules.BUY_FREE_MULTIPLE)));
        if (balance.compareTo(charge) < 0) throw new GameRuleCore.InsufficientBalanceException();
        active = fact;
        featureIndex = 0;
        phase = Phase.FEATURE;
        activeLineBet = lineBet;
        activeLevel = level;
        featureTwa = BigDecimal.ZERO.setScale(2);
        openHistory = new HistoryRound(charge, Instant.now().getEpochSecond());
        FeatureStep step = fact.steps().get(0);
        BigDecimal tw = SpinProjector.stepWin(step, lineBet, level);
        featureTwa = tw;
        BigDecimal start = balance;
        BigDecimal change = GameRuleCore.money(tw.subtract(charge));
        balance = GameRuleCore.money(start.add(change));
        long rid = ids.incrementAndGet();
        featureIndex = 1;
        Map<String, Object> data = SpinProjector.data(step, rid, lineBet, level, charge, start, tw, change, balance,
                userId, opaqueToken, 3, featureTwa, false);
        appendHistory(data, charge, change, tw);
        if (featureIndex >= active.steps().size()) {
            phase = Phase.IDLE;
            active = null;
            finishHistory();
        }
        return data;
    }

    private Map<String, Object> emitFeatureStep(int wireType) {
        FeatureStep step = active.steps().get(featureIndex);
        BigDecimal tw = SpinProjector.stepWin(step, activeLineBet, activeLevel);
        featureTwa = GameRuleCore.money(featureTwa.add(tw));
        BigDecimal start = balance;
        BigDecimal change = tw;
        balance = GameRuleCore.money(start.add(change));
        long rid = ids.incrementAndGet();
        Map<String, Object> data = SpinProjector.data(step, rid, activeLineBet, activeLevel,
                BigDecimal.ZERO.setScale(2), start, tw, change, balance, userId, opaqueToken,
                wireType, featureTwa, false);
        appendHistory(data, BigDecimal.ZERO.setScale(2), change, tw);
        featureIndex++;
        if (featureIndex >= active.steps().size() || step.st() == 0) {
            phase = Phase.IDLE;
            active = null;
            finishHistory();
        }
        return data;
    }

    private void appendHistory(Map<String, Object> step, BigDecimal bet, BigDecimal change, BigDecimal tw) {
        if (openHistory == null) openHistory = new HistoryRound(bet, Instant.now().getEpochSecond());
        openHistory.steps.add(step);
        openHistory.change = openHistory.change.add(change);
        openHistory.tw = openHistory.tw.add(tw);
    }

    private void finishHistory() {
        if (openHistory == null) return;
        history.add(0, openHistory);
        if (history.size() > historyLimit) history.remove(history.size() - 1);
        openHistory = null;
    }

    public synchronized BigDecimal balance() { return balance; }
    public synchronized List<HistoryRound> historyRounds() { return List.copyOf(history); }
    public synchronized List<RoundResult> history() { return List.of(); }
    public synchronized Optional<RoundResult> idempotentResult(String key) { return Optional.empty(); }
    public String token() { return token; }
    public long userId() { return userId; }

    public static final class HistoryRound {
        public final List<Map<String, Object>> steps = new ArrayList<>();
        public final BigDecimal bet;
        public final long createdAt;
        public BigDecimal change = BigDecimal.ZERO.setScale(2);
        public BigDecimal tw = BigDecimal.ZERO.setScale(2);
        HistoryRound(BigDecimal bet, long createdAt) {
            this.bet = bet;
            this.createdAt = createdAt;
        }
    }
}
