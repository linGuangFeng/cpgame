package com.cpgame.clubgoddess.api.service;

import com.cpgame.clubgoddess.api.state.SessionRepository;
import com.cpgame.clubgoddess.api.state.SessionState;
import com.cpgame.clubgoddess.api.state.SessionState.HistoryOrder;
import com.cpgame.clubgoddess.api.state.SessionState.IdempotencyRecord;
import com.cpgame.clubgoddess.core.GameModels.GameResult;
import com.cpgame.clubgoddess.core.GameModels.RoundBundle;
import com.cpgame.clubgoddess.codec.MinimalRoundFactCodec;
import com.cpgame.clubgoddess.core.GameRuleCore;
import com.cpgame.clubgoddess.core.ResultUtil;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GameService {
    private final SessionRepository repository;
    private final RedisRoundPool pool;
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    private final SecureRandom secureRandom = new SecureRandom();
    @Value("${game.initial-balance:1000}") private BigDecimal initialBalance;

    public GameService(SessionRepository repository,RedisRoundPool pool) { this.repository = repository;this.pool=pool; }

    public SessionState bootstrap(String launchToken) {
        if (launchToken == null || launchToken.isBlank()) throw new IllegalArgumentException("launch token is required");
        SessionState direct = repository.find(launchToken);
        if (direct != null) return direct;
        String launchHash = sha256(launchToken);
        String existing = repository.findByLaunchHash(launchHash);
        if (existing != null) return repository.get(existing);
        SessionState state = new SessionState();
        byte[] tokenBytes = new byte[24]; secureRandom.nextBytes(tokenBytes);
        state.token = "cg2060_" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        state.balance = initialBalance; state.createdAt = state.updatedAt = Instant.now().getEpochSecond();
        repository.put(launchHash, state);
        return state;
    }

    public SessionState session(String token) { return repository.get(token); }

    public GameResult snapshot(String token) {
        SessionState state = repository.get(token);
        synchronized (state) {
            if (state.lastResult != null) return state.lastResult;
            if (state.initialSnapshot == null) {
                BigDecimal stake = new BigDecimal("3");
                // Board transcribed from the archived init-room response; no runtime fixture read.
                state.initialSnapshot = GameRuleCore.rebuildBaseRound("0",List.of(1,9,4,3,2,2,10,8,6,7,7,5,6,2,5),new BigDecimal("0.01"),10,state.balance.add(stake)).deliveries().get(0).result();
                repository.save();
            }
            return state.initialSnapshot;
        }
    }

    public SpinOutcome spin(String token, BigDecimal bet, int level, String actId, String idempotencyKey) {
        return spin(token,bet,level,actId,idempotencyKey,null);
    }
    public SpinOutcome spin(String token, BigDecimal bet, int level, String actId, String idempotencyKey,String requestedKind) {
        if (!"0".equals(actId)) throw new IllegalArgumentException("Buy Feature and special activities are unsupported");
        SessionState state = repository.get(token);
        synchronized (state) {
            if (idempotencyKey != null && state.idempotency.containsKey(idempotencyKey)) {
                IdempotencyRecord old = state.idempotency.get(idempotencyKey);
                return new SpinOutcome(old.result, old.roundKey, old.deliveryIndex, true);
            }
            RoundBundle round;int index;
            if(state.pendingMember!=null){
                round=GameRuleCore.rebuildRound(codec.decode(state.pendingMember).roundKey(),codec.decode(state.pendingMember).boards(),state.pendingBet,state.pendingLevel,state.pendingStartBalance);
                index=state.deliveryIndex+1;
                if(index>=round.deliveries().size())throw new IllegalStateException("pending round state is broken");
            }else{
                BigDecimal stake=bet.multiply(BigDecimal.valueOf(level*30L));if(state.balance.compareTo(stake)<0)throw new IllegalArgumentException("insufficient balance");
                RedisRoundPool.Claimed claimed;try{claimed=pool.claim(requestedKind);}catch(Exception e){throw new IllegalStateException("Redis round claim failed",e);}
                var fact=codec.decode(claimed.member());round=GameRuleCore.rebuildRound(fact.roundKey(),fact.boards(),bet,level,state.balance);index=0;
                state.pendingMember=round.deliveries().size()>1?claimed.member():null;state.pendingStartBalance=state.balance;state.pendingBet=bet;state.pendingLevel=level;
            }
            GameResult delivery=round.deliveries().get(index).result();ResultUtil.analyze(delivery);
            state.balance = delivery.end_gold();
            state.lastResult = delivery;
            state.lastRoundKey = round.roundKey();
            state.deliveryIndex = index;
            long now = Instant.now().getEpochSecond(); state.updatedAt = now;
            if(index==0)state.history.add(new HistoryOrder(round.roundKey(),0,now,delivery));
            else {HistoryOrder order=state.history.get(state.history.size()-1);order.results.add(delivery);order.deliveryIndex=index;}
            if(index==round.deliveries().size()-1)state.pendingMember=null;
            String effectiveKey = (idempotencyKey == null || idempotencyKey.isBlank()) ? "generated:" + round.roundKey() : idempotencyKey;
            state.idempotency.put(effectiveKey, new IdempotencyRecord(round.roundKey(), index, delivery));
            trimIdempotency(state);
            repository.save();
            return new SpinOutcome(delivery, round.roundKey(), index, false);
        }
    }

    public Map<String, Object> aggregateHistory(String token) {
        SessionState state = repository.get(token);
        synchronized (state) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            List<Map<String,Object>> list = new ArrayList<>();
            BigDecimal totalBet = BigDecimal.ZERO, totalChange = BigDecimal.ZERO;
            for (int offset=0; offset<7; offset++) {
                LocalDate day = today.minusDays(offset);
                long start = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC), end = start + 86400;
                BigDecimal bets = BigDecimal.ZERO, changes = BigDecimal.ZERO;
                for (HistoryOrder order : state.history) if (order.createdAt >= start && order.createdAt < end) {
                    bets = bets.add(order.result.bet_gold()); for(GameResult step:order.results)changes=changes.add(step.change_gold());
                }
                totalBet=totalBet.add(bets); totalChange=totalChange.add(changes);
                list.add(map("bet_gold", bets, "change_gold", changes, "day", start));
            }
            return map("list", list, "statistics", map("total_bet_gold", totalBet, "total_change_gold", totalChange));
        }
    }

    public Map<String, Object> detailHistory(String token, long day, int page, int pageSize) {
        SessionState state = repository.get(token);
        synchronized (state) {
            long end = day + 86400;
            List<HistoryOrder> selected = state.history.stream().filter(o -> o.createdAt >= day && o.createdAt < end)
                    .sorted(Comparator.comparingLong((HistoryOrder o) -> o.createdAt).reversed()).toList();
            int from=Math.min(selected.size(), Math.max(0,(page-1)*pageSize)), to=Math.min(selected.size(),from+pageSize);
            List<Map<String,Object>> orders = selected.subList(from,to).stream().map(this::historyOrder).toList();
            BigDecimal bet=BigDecimal.ZERO, change=BigDecimal.ZERO;
            for (HistoryOrder order : selected) {
                bet=bet.add(order.result.bet_gold());
                for (GameResult step : order.results) change=change.add(step.change_gold());
            }
            return map("list", orders, "statistics", map("total_bet_gold", bet, "total_change_gold", change));
        }
    }

    private Map<String,Object> historyOrder(HistoryOrder order) {
        GameResult r=order.result; Map<String,Object> base=resultFields(r);
        base.put("balance_after", r.end_gold().toPlainString()); base.put("bet_level",r.level()); base.put("bet_size",r.bet());
        base.put("cc","BRL"); base.put("cs","R$"); base.put("created_at",order.createdAt);
        base.put("order_id",order.roundKey+"-2060"); base.put("result",r.props()); base.put("time",order.createdAt);
        Map<String,Object> extend=map("act_bet_gold",0,"act_id","0","act_type",0);
        base.put("extend", extend);
        List<Map<String,Object>>steps=new ArrayList<>();
        for(GameResult result:order.results){
            Map<String,Object>step=resultFields(result);
            step.put("balance_after",result.end_gold().toPlainString());
            step.put("bet_level",result.level());
            step.put("bet_size",result.bet());
            step.put("cc","BRL");
            step.put("cs","R$");
            step.put("created_at",order.createdAt);
            step.put("result",result.props());
            step.put("bet",false);
            step.put("extend", extend);
            steps.add(step);
        }
        base.put("results",steps);
        return base;
    }

    private Map<String,Object> resultFields(GameResult r) {
        return map("bet",r.bet(),"bet_gold",r.bet_gold(),"change_gold",r.change_gold(),"end_gold",r.end_gold(),
                "frees",r.frees(),"level",r.level(),"odds",r.odds(),"oid",r.oid(),"props",r.props(),
                "setting_id",r.setting_id(),"small_game_type",r.small_game_type(),"start_gold",r.start_gold(),
                "total_win",r.total_win(),"type",r.type());
    }

    private void trimIdempotency(SessionState state) {
        while (state.idempotency.size()>512) state.idempotency.remove(state.idempotency.keySet().iterator().next());
    }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    public static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]); return m; }
    public record SpinOutcome(GameResult result, String roundKey, int deliveryIndex, boolean idempotentReplay) {}
}
