package com.cpgame.crazy777.generator.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpinStep(
        BigDecimal ba,
        int bl,
        BigDecimal bs,
        int fsn,
        int gt,
        int nfsc,
        BigDecimal pb,
        int rpx,
        List<String> rskl,
        BigDecimal rwa,
        int smallGameType,
        int ss,
        BigDecimal wa,
        Map<String, String> wmkl
) {
    public SpinStep {
        rskl = List.copyOf(rskl);
        wmkl = Map.copyOf(wmkl);
    }

    public boolean terminal() {
        return fsn == 0 || (fsn == nfsc && ss == 1);
    }

    public Map<String, Object> protocolMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ba", amount(ba));
        result.put("bl", bl);
        result.put("bs", amount(bs));
        result.put("fsn", fsn);
        result.put("gt", gt);
        result.put("nfsc", nfsc);
        result.put("pb", moneyText(pb));
        result.put("rpx", rpx);
        result.put("rskl", rskl);
        result.put("rwa", amount(rwa));
        result.put("small_game_type", smallGameType);
        result.put("ss", ss);
        result.put("wa", amount(wa));
        result.put("wmkl", new LinkedHashMap<>(wmkl));
        return result;
    }

    public static Object amount(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 0 ? normalized.longValueExact() : normalized;
    }

    public static String moneyText(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
