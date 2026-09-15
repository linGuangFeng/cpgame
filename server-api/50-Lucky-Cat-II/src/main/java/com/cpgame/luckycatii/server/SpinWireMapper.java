package com.cpgame.luckycatii.server;

import com.cpgame.luckycatii.GameRules;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SpinWireMapper {
    private SpinWireMapper() {}

    static Map<String, Object> spinData(RoundResult round, BigDecimal balanceAfter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", number(round.betAmount()));
        data.put("gm", round.gameMode());
        data.put("pb", number(balanceAfter));
        data.put("rdri", round.respinReelIndex());
        data.put("rdskl", round.respinSymbols());
        data.put("rpx", round.rpx());
        data.put("rskl", round.finalBoard());
        data.put("wa", number(round.award()));
        data.put("wmkl", round.winningLines());
        return data;
    }

    static Map<String, Object> configLast(RoundResult round, BigDecimal balanceAfter, long createdAt) {
        Map<String, Object> data = spinData(round, balanceAfter);
        data.put("bl", round.betLevel());
        data.put("bs", number(round.betSize()));
        data.put("ca", createdAt);
        data.put("gt", 1);
        return data;
    }

    static Map<String, Object> historyDetail(RoundResult round, BigDecimal balanceAfter, long createdAt, String tis) {
        Map<String, Object> data = spinData(round, balanceAfter);
        data.put("baf", moneyString(balanceAfter));
        data.put("bid", GameRules.GAME_ID + "-" + tis);
        data.put("bl", round.betLevel());
        data.put("bs", number(round.betSize()));
        data.put("ca", createdAt);
        data.put("tis", tis);
        return data;
    }

    static Map<String, Object> historyRecord(RoundResult round, BigDecimal balanceAfter, long createdAt, String tis) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ba", moneyString(round.betAmount()));
        row.put("baf", moneyString(balanceAfter));
        row.put("bid", GameRules.GAME_ID + "-" + tis);
        row.put("ca", createdAt);
        row.put("fe", 0);
        row.put("gm", round.gameMode());
        row.put("gt", GameRules.GAME_ID);
        row.put("tis", tis);
        row.put("wa", moneyString(round.award()));
        return row;
    }

    static Map<String, Object> configBody(Map<String, Object> last) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auto", List.of(10, 30, 50, 100, 500));
        data.put("bll", GameRules.BET_LEVELS);
        data.put("bsl", GameRules.BET_SIZES);
        data.put("cc", "BRL");
        data.put("cs", "R$");
        data.put("dbl", 10);
        data.put("dbs", new BigDecimal("0.1"));
        data.put("last", last);
        Map<String, Integer> spl = new LinkedHashMap<>();
        spl.put("S1", 25); spl.put("S2", 20); spl.put("S3", 15); spl.put("S4", 7);
        spl.put("S5", 5); spl.put("S6", 2); spl.put("WILD", 80);
        data.put("spl", spl);
        data.put("ts", System.currentTimeMillis() / 1000L);
        return data;
    }

    static Object number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) return stripped.longValueExact();
        return stripped;
    }

    static String moneyString(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
