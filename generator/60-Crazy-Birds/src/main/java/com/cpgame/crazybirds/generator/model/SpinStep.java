package com.cpgame.crazybirds.generator.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpinStep(
        List<String> rskl,
        List<List<List<Integer>>> wmkl,
        List<String> wskl,
        Map<Integer, Integer> pxl,
        BigDecimal ba,
        BigDecimal wa,
        BigDecimal rwa,
        BigDecimal pb,
        int ss,
        int fsn,
        int nfsc,
        int gt,
        int smallGameType
) {
    public boolean terminal() {
        return ss != 0 && (fsn == 0 || nfsc >= fsn);
    }

    public Map<String, Object> protocolMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", ba.stripTrailingZeros());
        data.put("fsn", fsn);
        data.put("gt", gt);
        data.put("nfsc", nfsc);
        data.put("pb", pb.stripTrailingZeros().toPlainString());
        if (pxl == null || pxl.isEmpty()) data.put("pxl", List.of());
        else {
            Map<String, Integer> mapped = new LinkedHashMap<>();
            pxl.forEach((k, v) -> mapped.put(String.valueOf(k), v));
            data.put("pxl", mapped);
        }
        data.put("rskl", rskl);
        data.put("rwa", rwa.stripTrailingZeros());
        data.put("small_game_type", smallGameType);
        data.put("ss", ss);
        data.put("wa", wa.stripTrailingZeros());
        data.put("wmkl", wmkl);
        data.put("wskl", wskl);
        return data;
    }

    public static Object moneyText(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
