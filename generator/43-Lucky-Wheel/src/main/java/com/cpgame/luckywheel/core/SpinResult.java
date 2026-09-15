package com.cpgame.luckywheel.core;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpinResult(
        BigDecimal ba, int bl, int bs, long ca, String fsk, BigDecimal fwa,
        List<String> fwi, String fws, int gt, int md, String pb, int rpx,
        List<String> rskl, int smallGameType, BigDecimal wa, List<String> wskl
) implements Serializable {
    public SpinResult {
        fwi = List.copyOf(fwi);
        rskl = List.copyOf(rskl);
        wskl = List.copyOf(wskl);
    }

    public Map<String, Object> toProtocolData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", number(ba));
        data.put("bl", bl);
        data.put("bs", bs);
        data.put("ca", ca);
        data.put("fsk", fsk);
        data.put("fwa", number(fwa));
        data.put("fwi", fwi);
        data.put("fws", fws);
        data.put("gt", gt);
        data.put("md", md);
        data.put("pb", pb);
        data.put("rpx", rpx);
        data.put("rskl", rskl);
        data.put("small_game_type", smallGameType);
        data.put("wa", number(wa));
        data.put("wskl", wskl);
        return data;
    }

    private static Number number(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 0 ? normalized.longValueExact() : normalized;
    }
}
