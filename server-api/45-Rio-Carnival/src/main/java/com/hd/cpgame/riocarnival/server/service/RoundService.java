package com.hd.cpgame.riocarnival.server.service;

import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.RoundVerifier;
import com.hd.cpgame.riocarnival.core.SpinStep;
import com.hd.cpgame.riocarnival.server.state.DeliveryReceipt;
import com.hd.cpgame.riocarnival.server.state.GameSession;
import com.hd.cpgame.riocarnival.server.state.HistoryRecord;
import com.hd.cpgame.riocarnival.server.state.SessionStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class RoundService {
    private final RedisRoundPool pool;
    private final SessionStore sessions;
    public RoundService(RedisRoundPool pool, SessionStore sessions) { this.pool=pool; this.sessions=sessions; }

    public Map<String,Object> spin(GameSession session, BigDecimal bs, int bl, String idempotencyKey) {
        synchronized (session) {
            String cacheKey = normalizeKey(idempotencyKey);
            if (cacheKey != null && session.idempotency.containsKey(cacheKey))
                return project(session, session.idempotency.get(cacheKey));

            if (session.activeRound == null) {
                BigDecimal totalBet = bs.multiply(BigDecimal.valueOf(bl)).multiply(BigDecimal.valueOf(25));
                if (session.balance.compareTo(totalBet) < 0) throw new SessionStore.SessionException("C10002","insufficient funds");
                GeneratedRound generated = pool.claim(bs,bl); // Claim exactly one pre-generated member; all later deliveries use this round
                RoundVerifier.verify(generated);                // API 层再次独立复核
                session.balance=money(session.balance.subtract(totalBet));
                session.activeBalanceAfterBet=session.balance;
                session.activeRound=generated; session.deliveryIndex=0; session.lastDelivered=null;
                sessions.save(); // 在首个 Delivery 前保存 roundKey/deliveryIndex，防止重复领取完整 Round
            } else if (session.activeRound.betSize.compareTo(bs)!=0 || session.activeRound.betLevel!=bl) {
                throw new SessionStore.SessionException("C10003","active round bet mismatch");
            }

            GeneratedRound round=session.activeRound;
            int stepIndex=session.deliveryIndex;
            if (stepIndex<0 || stepIndex>=round.steps.size()) throw new IllegalStateException("deliveryIndex 越界");
            SpinStep step=round.steps.get(stepIndex);
            session.deliveryIndex=stepIndex+1; session.lastDelivered=step;
            if (step.terminal()) {
                session.balance=money(session.activeBalanceAfterBet.add(step.rwa));
                session.history.add(new HistoryRecord(round,session.activeBalanceAfterBet,System.currentTimeMillis()/1000L));
                session.activeRound=null; session.deliveryIndex=0; session.lastDelivered=null; session.activeBalanceAfterBet=null;
            }
            DeliveryReceipt receipt=new DeliveryReceipt(round.roundKey,stepIndex,step,session.balance);
            if (cacheKey!=null) { session.idempotency.put(cacheKey,receipt); trimCache(session); }
            sessions.save();
            return project(session,receipt);
        }
    }

    public Map<String,Object> project(GameSession session, DeliveryReceipt receipt) {
        Map<String,Object> data=new LinkedHashMap<String,Object>(); SpinStep s=receipt.step;
        data.put("ba",s.ba); data.put("fsn",s.fsn); data.put("gt",s.gt); data.put("nfsc",s.nfsc);
        Map<String,Object> player=new LinkedHashMap<String,Object>(); player.put("balance",format(receipt.playerBalance)); player.put("id",session.playerId);
        data.put("pl",player); data.put("rpx",s.rpx); data.put("rskl",s.rskl); data.put("rwa",s.rwa);
        data.put("small_game_type",s.small_game_type); data.put("ss",s.ss); data.put("wa",s.wa); data.put("wmkl",s.wmkl);
        return data;
    }

    private static String normalizeKey(String key) {
        if (key==null || key.trim().isEmpty()) return null;
        String clean=key.trim(); if(clean.length()>128) throw new IllegalArgumentException("幂等键过长");
        return "spin:"+clean;
    }
    private static void trimCache(GameSession s) {
        while(s.idempotency.size()>100) s.idempotency.remove(s.idempotency.keySet().iterator().next());
    }
    public static BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}
    public static String format(BigDecimal value){return money(value).toPlainString();}
}
