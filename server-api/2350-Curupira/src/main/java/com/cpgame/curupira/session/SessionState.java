package com.cpgame.curupira.session;

import com.cpgame.curupira.api.DemoSelectionPolicy;
import com.cpgame.curupira.api.RoundSource;
import com.cpgame.curupira.api.SpinProjector;
import com.cpgame.curupira.api.UnsupportedBehaviorException;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationScene;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.FeatureStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Session accounting and wire projection. Every visible board/state fact comes from Redis. */
public final class SessionState {
    private enum Phase { IDLE, SELECTING, FEATURE }

    private final String token;
    private final long userId;
    private final String opaqueToken;
    private final int historyLimit;
    private final DemoSelectionPolicy selectionPolicy;
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1_000_000L + 2350);
    private BigDecimal balance;
    private Map<String, Object> lastData;
    private final List<HistoryRound> history = new ArrayList<>();
    private final Map<String, Map<String, Object>> idempotentResults = new HashMap<>();
    private Phase phase = Phase.IDLE;
    private CompleteRoundFact activeMary;
    private int featureIndex;
    private BigDecimal featureTwa = BigDecimal.ZERO.setScale(2);
    private BigDecimal activeLineBet = GameRules.MINIMUM_LINE_BET;
    private int activeLevel = 1;
    private HistoryRound openHistory;

    SessionState(String token, long userId, String opaqueToken, BigDecimal balance, int historyLimit,
                 DemoSelectionPolicy selectionPolicy) {
        this.token = token;
        this.userId = userId;
        this.opaqueToken = opaqueToken;
        this.balance = GameRuleCore.money(balance);
        this.historyLimit = historyLimit;
        this.selectionPolicy = selectionPolicy;
    }

    public synchronized Map<String, Object> play(int type, int gameType, BigDecimal lineBet, int level,
                                                 String idempotencyKey, RoundSource source) {
        Map<String, Object> existing = idempotentResults.get(idempotencyKey);
        if (existing != null) return existing;
        if (lineBet == null || lineBet.signum() <= 0 || level < 1) {
            throw new IllegalArgumentException("Invalid Curupira bet or level");
        }
        Map<String, Object> data = switch (type) {
            case 1 -> {
                if (gameType != 1) throw new UnsupportedBehaviorException(type, gameType);
                yield paid(lineBet, level, source);
            }
            case 2 -> featureContinue(gameType, source);
            default -> throw new UnsupportedBehaviorException(type, gameType);
        };
        lastData = data;
        idempotentResults.put(idempotencyKey, data);
        return data;
    }

    public synchronized Map<String, Object> roomProjection(RoundSource source) {
        if (lastData == null) {
            CompleteRoundFact idle = requirePaidStart(source.peekLoss());
            FeatureStep step = idle.steps().get(0);
            lastData = SpinProjector.data(step, 0L, GameRules.MINIMUM_LINE_BET, 1,
                    BigDecimal.ZERO.setScale(2), balance, BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), balance, userId, opaqueToken, 1,
                    BigDecimal.ZERO.setScale(2), true);
        }
        return lastData;
    }

    private Map<String, Object> paid(BigDecimal lineBet, int level, RoundSource source) {
        if (phase != Phase.IDLE) throw new UnsupportedBehaviorException(1, 1);
        BigDecimal bet = SpinProjector.totalBet(lineBet, level);
        if (balance.compareTo(bet) < 0) throw new GameRuleCore.InsufficientBalanceException();
        int targetMultiplier = selectionPolicy.chooseTargetMultiplier(GenerationScene.NORMAL_PAID);
        CompleteRoundFact fact = requirePaidStart(source.claimPaidAtOrBelow(targetMultiplier));
        FeatureStep step = fact.steps().get(0);
        BigDecimal tw = SpinProjector.stepWin(step, lineBet, level);
        BigDecimal start = balance;
        BigDecimal change = GameRuleCore.money(tw.subtract(bet));
        balance = GameRuleCore.money(start.add(change));
        activeLineBet = lineBet;
        activeLevel = level;
        featureTwa = BigDecimal.ZERO.setScale(2);
        openHistory = new HistoryRound(bet, Instant.now().getEpochSecond());
        phase = fact.kind() == Kind.TRIGGER ? Phase.SELECTING : Phase.IDLE;
        long rid = ids.incrementAndGet();
        Map<String, Object> data = SpinProjector.data(step, rid, lineBet, level, bet, start, tw, change, balance,
                userId, opaqueToken, 1, featureTwa, false);
        appendHistory(data, change, tw);
        if (phase == Phase.IDLE) finishHistory();
        return data;
    }

    private Map<String, Object> featureContinue(int gameType, RoundSource source) {
        Kind kind;
        GenerationScene scene;
        if (gameType == 2) {
            kind = Kind.FREE_EW;
            scene = GenerationScene.FREE_EXPANDING_WILD;
        } else if (gameType == 3) {
            kind = Kind.HOLD;
            scene = GenerationScene.HOLD_AND_SPINS;
        } else {
            throw new UnsupportedBehaviorException(2, gameType);
        }
        if (phase == Phase.SELECTING) {
            int target = selectionPolicy.chooseTargetMultiplier(scene);
            activeMary = requireMary(source.claimMaryAtOrBelow(kind, target), kind);
            featureIndex = 0;
            phase = Phase.FEATURE;
        } else if (phase != Phase.FEATURE || activeMary == null || activeMary.kind() != kind) {
            throw new UnsupportedBehaviorException(2, gameType);
        }
        return emitFeatureStep();
    }

    private Map<String, Object> emitFeatureStep() {
        FeatureStep step = activeMary.steps().get(featureIndex);
        BigDecimal tw = SpinProjector.stepWin(step, activeLineBet, activeLevel);
        featureTwa = GameRuleCore.money(featureTwa.add(tw));
        BigDecimal start = balance;
        BigDecimal change = tw;
        balance = GameRuleCore.money(start.add(change));
        long rid = ids.incrementAndGet();
        Map<String, Object> data = SpinProjector.data(step, rid, activeLineBet, activeLevel,
                BigDecimal.ZERO.setScale(2), start, tw, change, balance, userId, opaqueToken,
                2, featureTwa, false);
        appendHistory(data, change, tw);
        featureIndex++;
        if (featureIndex >= activeMary.steps().size() || step.st() == 0) {
            phase = Phase.IDLE;
            activeMary = null;
            finishHistory();
        }
        return data;
    }

    private static CompleteRoundFact requirePaidStart(CompleteRoundFact fact) {
        if (fact == null || fact.entry() != CompleteRoundFact.EntryKind.PAID || fact.steps().size() != 1
                || !(fact.kind().ordinary() || fact.kind() == Kind.TRIGGER)) {
            throw new IllegalStateException("Redis member is not a complete paid-start fact");
        }
        return fact;
    }

    private static CompleteRoundFact requireMary(CompleteRoundFact fact, Kind expected) {
        if (fact == null || fact.kind() != expected || fact.entry() != CompleteRoundFact.EntryKind.PAID) {
            throw new IllegalStateException("Redis member is not the selected Mary fact");
        }
        return fact;
    }

    private void appendHistory(Map<String, Object> step, BigDecimal change, BigDecimal tw) {
        if (openHistory == null) throw new IllegalStateException("Feature history has no paid start");
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
