package com.cpgame.coinmastergo.service;

import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.model.HistoryRecord;
import com.cpgame.coinmastergo.model.PlayerSession;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class GameSessionService {
    private final StateStore store;
    private final GameRuleCore core;
    private final GameProperties properties;

    public GameSessionService(StateStore store, GameRuleCore core, GameProperties properties) {
        this.store = store;
        this.core = core;
        this.properties = properties;
    }

    public PlayerSession verify(String launchToken, String gid) {
        requireGid(gid);
        if (launchToken == null || launchToken.isBlank()) throw new GameException(400, "missing launch token");
        return store.transaction(state -> {
            String existing = state.authTokenByLaunchToken.get(launchToken);
            if (existing != null) return state.sessionsByToken.get(existing);
            PlayerSession session = new PlayerSession();
            session.launchToken = launchToken;
            session.token = UUID.randomUUID().toString();
            session.playerId = Integer.toUnsignedLong(launchToken.hashCode()) + 10_000_000L;
            session.balance = properties.getInitialBalance().setScale(2);
            session.lastStep = core.neutralSnapshot(session.balance);
            state.sessionsByToken.put(session.token, session);
            state.authTokenByLaunchToken.put(launchToken, session.token);
            return session;
        });
    }

    public PlayerSession session(String token, String gid) {
        requireGid(gid);
        return store.read(state -> {
            PlayerSession session = state.sessionsByToken.get(token);
            if (session == null) throw new GameException(401, "invalid session token");
            return session;
        });
    }

    public SpinStep spin(String token, String gid, int betLevel, BigDecimal betSize, String idempotencyKey) {
        requireGid(gid);
        if (!GameRules.BET_LEVELS.contains(betLevel) || GameRules.BET_SIZES.stream().noneMatch(v -> v.compareTo(betSize) == 0)) {
            throw new GameException(400, "invalid bl or bs");
        }
        return store.transaction(state -> {
            PlayerSession session = state.sessionsByToken.get(token);
            if (session == null) throw new GameException(401, "invalid session token");
            String cacheKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
            if (cacheKey != null && session.idempotentSpinResponses.containsKey(cacheKey)) {
                return session.idempotentSpinResponses.get(cacheKey);
            }
            if (session.activeRound == null) startRound(session, betLevel, betSize);
            else if (session.activeRound.betLevel != betLevel || session.activeRound.betSize.compareTo(betSize) != 0) {
                throw new GameException(409, "active Round must resume with its original bl and bs");
            }
            SpinStep response = projectNextStep(session);
            session.lastStep = response;
            if (cacheKey != null) {
                session.idempotentSpinResponses.put(cacheKey, response);
                while (session.idempotentSpinResponses.size() > 256) {
                    String oldest = session.idempotentSpinResponses.keySet().iterator().next();
                    session.idempotentSpinResponses.remove(oldest);
                }
            }
            return response;
        });
    }

    private void startRound(PlayerSession session, int betLevel, BigDecimal betSize) {
        BigDecimal betAmount = GameRules.betAmount(betLevel, betSize);
        if (session.balance.compareTo(betAmount) < 0) throw new GameException(402, "insufficient balance");
        session.balance = session.balance.subtract(betAmount).setScale(2);
        long sequence = session.paidRoundSequence++;
        String roundKey = UUID.randomUUID().toString();
        String transferId = Long.toString(Instant.now().getEpochSecond() * 1_000_000L + Math.floorMod(sequence, 1_000_000));
        RoundPlan round = core.generateRuntimeRound(roundKey, transferId, betLevel, betSize,
                session.balance, Instant.now().getEpochSecond());
        if (!session.claimedRoundKeys.add(roundKey)) throw new IllegalStateException("Round already claimed: " + roundKey);
        round.claimed = true;
        session.activeRound = round;
    }

    private SpinStep projectNextStep(PlayerSession session) {
        RoundPlan round = session.activeRound;
        if (round == null || round.exhausted()) throw new IllegalStateException("no active Round projection");
        RoundDelivery delivery = round.deliveries.get(round.deliveryIndex);
        SpinStep result = delivery.steps.get(round.stepIndex);
        round.stepIndex++;
        if (round.stepIndex >= delivery.steps.size()) {
            round.deliveryIndex++;
            round.stepIndex = 0;
        }
        if (round.exhausted()) {
            session.balance = round.postDebitBalance.add(round.totalWin).setScale(2);
            session.history.add(0, new HistoryRecord(round, Instant.now().getEpochSecond()));
            session.activeRound = null;
        }
        return result;
    }

    public SpinStep lastOrNeutral(PlayerSession session) {
        return session.lastStep == null ? core.neutralSnapshot(session.balance) : session.lastStep;
    }

    private void requireGid(String gid) {
        if (!Integer.toString(GameRules.GAME_PROTOCOL_ID).equals(gid)) throw new GameException(400, "gid must be 55");
    }
}
