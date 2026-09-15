package com.cpgame.saci.generator.model;

import com.cpgame.saci.generator.GameRules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpinStep(
        BigDecimal ba,
        int bl,
        BigDecimal bs,
        long ca,
        BigDecimal frwa,
        int fsn,
        int gm,
        int gt,
        int nfsc,
        int nrsc,
        BigDecimal pb,
        List<String> rskl,
        int rsn,
        BigDecimal rwa,
        int smallGameType,
        int ss,
        List<Integer> syxl,
        BigDecimal wa,
        List<Integer> wmkl,
        int wn,
        Map<String, Integer> wnl,
        List<Integer> wskl,
        List<List<Integer>> afnl
) {
    public SpinStep {
        rskl = List.copyOf(rskl);
        syxl = List.copyOf(syxl);
        wmkl = List.copyOf(wmkl);
        wskl = List.copyOf(wskl);
        wnl = Map.copyOf(wnl);
        afnl = afnl.stream().map(List::copyOf).toList();
    }

    public boolean roundTerminal() {
        return ss == 1 && fsn == nfsc && rsn == nrsc;
    }

    public StepFact fact() {
        return new StepFact(rskl, syxl, wskl, afnl, smallGameType, ss, fsn, nfsc, rsn, nrsc, gt, gm, wn);
    }

    public Map<String, Object> protocolMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("afnl", copyPairs(afnl));
        result.put("ba", amount(ba));
        result.put("bl", bl);
        result.put("bs", amount(bs));
        result.put("ca", ca);
        result.put("frwa", amount(frwa));
        result.put("fsn", fsn);
        result.put("gm", gm);
        result.put("gt", gt);
        result.put("nfsc", nfsc);
        result.put("nrsc", nrsc);
        result.put("pb", moneyText(pb));
        result.put("rskl", new ArrayList<>(rskl));
        result.put("rsn", rsn);
        result.put("rwa", amount(rwa));
        result.put("small_game_type", smallGameType);
        result.put("ss", ss);
        result.put("syxl", new ArrayList<>(syxl));
        result.put("wa", amount(wa));
        result.put("wmkl", new ArrayList<>(wmkl));
        result.put("wn", wn);
        result.put("wnl", new LinkedHashMap<>(wnl));
        result.put("wskl", new ArrayList<>(wskl));
        return result;
    }

    public Map<String, Object> historyStep(String bid, long createdAt) {
        Map<String, Object> result = new LinkedHashMap<>(protocolMap());
        result.put("balance_after", moneyText(pb));
        result.put("bet_level", bl);
        result.put("bet_size", amount(bs));
        result.put("bid", bid);
        result.put("ca", createdAt);
        result.put("created_at", createdAt);
        result.put("cc", "BRL");
        result.put("cs", "R$");
        return result;
    }

    public static Object amount(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() <= 0) return normalized.longValueExact();
        return normalized;
    }

    public static String moneyText(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static List<List<Integer>> copyPairs(List<List<Integer>> values) {
        List<List<Integer>> copy = new ArrayList<>();
        for (List<Integer> row : values) copy.add(new ArrayList<>(row));
        return copy;
    }

    public static Map<String, Integer> energyMap(BigDecimal paidBet, int wn) {
        if (wn <= 0) return Map.of();
        return Map.of(GameRules.energyKey(paidBet), wn);
    }
}
