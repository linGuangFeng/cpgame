package com.cpgame.clubgoddess.core;

import java.math.BigDecimal;
import java.util.List;

public final class GameModels {
    private GameModels() {}

    public enum ResultMode { ORDINARY_PAID_LOSS, ORDINARY_PAID_WIN, FREE_SPINS }

    public record WinItem(int odd, List<Integer> pos_arr, BigDecimal tw, int ways, int wp) {}

    public record Props(int frees_prop, int m, List<Integer> prop, int spe_num,
                        BigDecimal tw, List<WinItem> win_arr) {}

    public record Frees(BigDecimal ba, BigDecimal bet, int l, int m, int spe_num, int st, int tt,
                        BigDecimal twa) {
        public static Frees disabledBaseState() {
            return new Frees(BigDecimal.ZERO, BigDecimal.ZERO, 0, 2, 0, 0, 0, BigDecimal.ZERO);
        }
    }

    public record GameResult(BigDecimal bet, BigDecimal bet_gold, BigDecimal change_gold,
                             BigDecimal end_gold, Frees frees, int level, BigDecimal odds,
                             String oid, Props props, int setting_id, int small_game_type,
                             BigDecimal start_gold, BigDecimal total_win, int type) {}

    public record Delivery(int deliveryIndex, GameResult result, boolean terminal) {}

    public record ResultAnalysis(ResultMode mode, BigDecimal totalWin, BigDecimal stakeMultiplier,
                                 boolean terminal, boolean hasContinuation, int scatterCount,
                                 List<WinItem> wins) {
        public ResultAnalysis { wins = List.copyOf(wins); }
    }

    public record RoundBundle(String roundKey, String rulesHash, ResultAnalysis analysis,
                              List<Delivery> deliveries) {
        public RoundBundle {
            deliveries = List.copyOf(deliveries);
            if (analysis == null || !analysis.terminal()) {
                throw new IllegalArgumentException("Complete round analysis must be terminal");
            }
            if (deliveries.isEmpty()) throw new IllegalArgumentException("Round must have a delivery");
            for (int i=0;i<deliveries.size();i++) {
                Delivery d=deliveries.get(i);
                if (d.deliveryIndex()!=i || d.terminal()!=(i==deliveries.size()-1)) {
                    throw new IllegalArgumentException("Delivery boundary is invalid");
                }
            }
        }
    }
}
