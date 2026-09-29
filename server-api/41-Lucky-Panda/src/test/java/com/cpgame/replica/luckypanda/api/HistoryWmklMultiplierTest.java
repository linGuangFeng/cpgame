package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryWmklMultiplierTest {
    private static final List<String> FOUR_H1_WAYS = List.of(
            "1H1", "1H1", "1A", "1K", "1Q",
            "1H1", "1K", "1Q", "1J", "1T", "1H2",
            "1H1", "1H3", "1H4", "1H5", "1H3", "1H4",
            "1H1", "1H2", "1H3", "1H4", "1H5", "1J",
            "1K", "1Q", "1J", "1T", "1H2", "1H3",
            "1K", "1Q", "1J", "1T", "1H4");

    @Test
    void historyRowsKeepThePreMultiplierAward() {
        var board = LuckyPandaBoard.fromRskl(FOUR_H1_WAYS);
        var evaluation = GameRuleCore.evaluate(board, new BigDecimal("0.10"), 1, 4);
        assertEquals(0, evaluation.wa().compareTo(new BigDecimal("20.00")));

        var delivery = new SpinProjector.Delivery(board.toRskl(), 4, evaluation.wa(), evaluation.wa(),
                1, 0, 0, evaluation.wskl(), evaluation.wmkl(), evaluation.wins(),
                true, true, false, List.of(), List.of());
        List<Map<String, Object>> rows = SpinProjector.historyWmkl(delivery);

        assertEquals(1, rows.size());
        assertEquals(0, ((BigDecimal) rows.get(0).get("wa")).compareTo(new BigDecimal("5.00")));
    }
}
