package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 将独立判奖结果适配成 CP 2260 客户端协议；不参与出牌与判奖。 */
public final class FreedomDayProtocolUtil {
    private FreedomDayProtocolUtil() { }

    @SuppressWarnings("unchecked")
    public static void apply(FreedomDaySiVo si, JSONObject round, int spinIndex, double betSize,
                             int level, double chargedBet, double balanceBefore, long oid) {
        JSONArray spins = round.getJSONArray("spins");
        JSONObject spin = spins.getJSONObject(Math.min(spinIndex, spins.size() - 1));
        double spinWin = spin.getBigDecimal("spinWin").doubleValue();
        boolean first = spinIndex == 0;
        double charge = first ? chargedBet : 0D;

        si.setBet(betSize);
        si.setBet_gold(charge);
        si.setChange_gold(spinWin - charge);
        si.setStart_gold(balanceBefore);
        si.setEnd_gold(balanceBefore - charge + spinWin);
        si.setTotal_win(spinWin);
        si.setOdds(charge > 0 ? spinWin / charge : 0D);
        si.setLevel(level);
        si.setOid(oid);
        si.setType(spin.getIntValue("type"));
        si.setProps((List<Map<String, Object>>) (List<?>) spin.getJSONArray("props"));

        Map<String, Object> extend = new HashMap<>();
        extend.put("act_bet_gold", 0);
        extend.put("act_id", "0");
        extend.put("bet_type", round.getBooleanValue("featureBuy") ? 3 : 0);
        si.setExtend(extend);

        int total = round.getIntValue("freeTotal");
        int freeIndex = spin.getIntValue("freeIndex");
        double cumulative = 0D;
        for (int i = 1; i <= spinIndex && i < spins.size(); i++) cumulative += spins.getJSONObject(i).getDoubleValue("spinWin");
        Map<String, Object> frees = new HashMap<>();
        frees.put("tt", total);
        frees.put("st", total == 0 ? 0 : Math.max(0, total - freeIndex));
        frees.put("twa", cumulative);
        frees.put("lwa", spinIndex == 0 ? 0D : spinWin);
        frees.put("m", spin.getIntValue("endingMultiplier"));
        frees.put("ba", chargedBet);
        frees.put("bet", betSize);
        si.setFrees(frees);
    }
}
