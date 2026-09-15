package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpinWireMapper {
    private SpinWireMapper() {}

    public static Map<String, Object> toSpinData(RoundResult round, BigDecimal postBalance, RoundLedger ledger) {
        ledger.claim();
        List<Integer> positions = new ArrayList<>();
        List<Integer> multipliers = new ArrayList<>();
        while (true) {
            var next = ledger.nextDelivery();
            if (next.isEmpty()) break;
            positions.add(next.get().position());
            if (!next.get().terminal()) multipliers.add(next.get().multiplier());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", decimal(round.betAmount()));
        data.put("fwa", decimal(round.wheelAward()));
        data.put("fwtl", positions);
        data.put("fwxl", multipliers);
        data.put("gm", round.gameMode());
        data.put("pb", postBalance.setScale(2).toPlainString());
        data.put("rskl", round.symbols());
        data.put("small_game_type", round.smallGameType());
        data.put("wa", decimal(round.totalAward()));
        data.put("wmkl", wireWins(round));
        return data;
    }

    public static Map<String, Object> toLastData(RoundResult round, BigDecimal postBalance, Map<String, Object> spin) {
        Map<String, Object> last = new LinkedHashMap<>(spin);
        last.put("bs", decimal(round.betSize()));
        last.put("bl", round.betLevel());
        last.put("ca", round.createdAtEpochSecond());
        last.put("gt", 1);
        last.put("pb", postBalance.setScale(2).toPlainString());
        return last;
    }

    private static Object wireWins(RoundResult round) {
        if (round.lineWins().isEmpty()) return List.of();
        if (round.lineWins().size() == 5) {
            List<String> wins = new ArrayList<>();
            for (int i = 0; i < 5; i++) wins.add(round.lineWins().get(i));
            return wins;
        }
        return new LinkedHashMap<>(round.lineWins());
    }

    private static Object decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
