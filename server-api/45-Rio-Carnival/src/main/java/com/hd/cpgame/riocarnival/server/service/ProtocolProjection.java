package com.hd.cpgame.riocarnival.server.service;

import com.hd.cpgame.riocarnival.core.GameRules;
import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.ResultUtil;
import com.hd.cpgame.riocarnival.core.SpinStep;
import com.hd.cpgame.riocarnival.core.WinEvaluation;
import com.hd.cpgame.riocarnival.server.state.GameSession;
import com.hd.cpgame.riocarnival.server.state.HistoryRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ProtocolProjection {
    public Map<String,Object> config(GameSession session) {
        Map<String,Object> d=new LinkedHashMap<String,Object>();
        d.put("auto",GameRules.AUTO_SPINS); d.put("bll",GameRules.BET_LEVELS); d.put("bsl",GameRules.BET_SIZES);
        d.put("cc","BRL"); d.put("cs","R$"); d.put("dbl",new BigDecimal("0.8")); d.put("dbs",new BigDecimal("0.02"));
        d.put("last",session.activeRound==null?null:last(session)); d.put("spl",GameRules.PAYTABLE); d.put("ts",System.currentTimeMillis()/1000L);
        return d;
    }

    public Map<String,Object> last(GameSession session) {
        GeneratedRound r=session.activeRound; SpinStep s=session.lastDelivered;
        if(s==null) s=r.steps.get(0);
        Map<String,Object> d=stepFields(r,s,session.playerId,session.balance,false,0);
        d.put("bl",r.betLevel); d.put("bs",r.betSize); d.put("ca",System.currentTimeMillis()/1000L);
        d.put("wmkl",historyMatches(r,s));
        return d;
    }

    public Map<String,Object> historyList(GameSession session,int page,long begin,long end) {
        List<HistoryRecord> filtered=new ArrayList<HistoryRecord>();
        for(HistoryRecord h:session.history) if((begin<=0||h.round.createdAt>=begin)&&(end<=0||h.round.createdAt<=end)) filtered.add(h);
        Collections.sort(filtered,new Comparator<HistoryRecord>(){public int compare(HistoryRecord a,HistoryRecord b){return Long.compare(b.round.createdAt,a.round.createdAt);}});
        int from=Math.min(Math.max(0,(page-1)*10),filtered.size()), to=Math.min(from+10,filtered.size());
        List<Map<String,Object>> ll=new ArrayList<Map<String,Object>>();
        BigDecimal totalBet=BigDecimal.ZERO,totalWin=BigDecimal.ZERO;
        for(HistoryRecord h:filtered){totalBet=totalBet.add(h.round.totalBet());totalWin=totalWin.add(finalAward(h));}
        for(HistoryRecord h:filtered.subList(from,to)){
            Map<String,Object> x=new LinkedHashMap<String,Object>();
            x.put("ba",plain(h.round.totalBet()));x.put("baf",plain(h.balanceAfterBet));x.put("bid",displayId(h.round.roundKey+":0"));
            x.put("ca",h.round.createdAt);x.put("fe",0);x.put("gm",null);x.put("gt",45);x.put("tis",displayId(h.round.roundKey));x.put("wa",plain(finalAward(h)));ll.add(x);
        }
        Map<String,Object> d=new LinkedHashMap<String,Object>();
        if(page==1){d.put("ba",plain(totalBet));d.put("lc",filtered.size());d.put("wa",plain(totalWin));}
        d.put("end",to>=filtered.size()?1:0);d.put("ll",ll);return d;
    }

    public Map<String,Object> historyView(GameSession session,String transferId) {
        HistoryRecord found=null; for(HistoryRecord h:session.history) if(h.round.roundKey.equals(transferId)||displayId(h.round.roundKey).equals(transferId)){found=h;break;}
        if(found==null) throw new IllegalArgumentException("unknown transfer_id");
        Map<String,Object> d=new LinkedHashMap<String,Object>(); d.put("baf",found.balanceAfterBet);d.put("bid",displayId(found.round.roundKey+":0"));
        List<Map<String,Object>> bsl=new ArrayList<Map<String,Object>>(),fsl=new ArrayList<Map<String,Object>>();
        GeneratedRound r=found.round;
        for(int i=0;i<r.steps.size();i++){
            SpinStep s=r.steps.get(i); BigDecimal balance=s.terminal()?found.balanceAfterBet.add(s.rwa):found.balanceAfterBet;
            Map<String,Object> x=stepFields(r,s,session.playerId,balance,true,i); if(i==0)bsl.add(x);else fsl.add(x);
        }
        d.put("bsl",bsl);d.put("fsl",fsl);return d;
    }

    private Map<String,Object> stepFields(GeneratedRound r,SpinStep s,long playerId,BigDecimal balance,boolean history,int index){
        Map<String,Object> d=new LinkedHashMap<String,Object>();d.put("ba",s.ba);
        if(history){d.put("balance_after",plain(balance));d.put("bet_level",r.betLevel);d.put("bet_size",r.betSize);d.put("bid",displayId(r.roundKey+":"+index));d.put("bl",r.betLevel);d.put("bs",r.betSize.toPlainString());d.put("ca",r.createdAt+index);d.put("cc","BRL");d.put("created_at",r.createdAt+index);d.put("cs","R$");}
        d.put("fsn",s.fsn);d.put("gt",s.gt);d.put("nfsc",s.nfsc);Map<String,Object> pl=new LinkedHashMap<String,Object>();pl.put("balance",RoundService.format(balance));pl.put("id",playerId);d.put("pl",pl);
        d.put("rpx",s.rpx);d.put("rskl",s.rskl);d.put("rwa",s.rwa);d.put("small_game_type",s.small_game_type);d.put("ss",s.ss);d.put("wa",s.wa);d.put("wmkl",history?historyMatches(r,s):s.wmkl);return d;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> historyMatches(GeneratedRound r,SpinStep s){
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(); if(!(s.wmkl instanceof Map))return out;
        WinEvaluation evaluation=ResultUtil.evaluate(s.rskl,r.betSize,r.betLevel,s.rpx);
        for(Map.Entry<String,Map<String,Integer>> e:((Map<String,Map<String,Integer>>)s.wmkl).entrySet()){
            int line=Integer.parseInt(e.getKey());Map.Entry<String,Integer> win=e.getValue().entrySet().iterator().next();Map<String,Object>x=new LinkedHashMap<String,Object>();
            x.put("psn",win.getValue());x.put("sk",win.getKey());x.put("wa",evaluation.lineAwards.get(line));x.put("wpk",line);out.add(x);
        }return out;
    }
    // Local opaque display identifiers; original identifiers were redacted in the evidence.
    private static String displayId(String value) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new java.math.BigInteger(1,java.util.Arrays.copyOf(digest,8)).toString(36);
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static BigDecimal finalAward(HistoryRecord h){return h.round.steps.get(h.round.steps.size()-1).rwa;}
    private static String plain(BigDecimal v){return v.stripTrailingZeros().toPlainString();}
}
