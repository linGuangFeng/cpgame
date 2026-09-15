package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 当前游戏协议中的 Java 领域对象；字段名保持原前端协议原值。 */
public final class CyberGoModels {
    private CyberGoModels() { }

    public enum RoundKind { ORDINARY_LOSS, ORDINARY_WIN, FREE_SPINS }

    public record WinMatch(String sk, String wa, List<List<Integer>> wmk) { }

    public record Step(
            BigDecimal ba,
            String bid,
            int bl,
            BigDecimal bs,
            long ca,
            int fsn,
            BigDecimal frwa,
            int gt,
            int nfsc,
            int rpx,
            List<String> rskl,
            BigDecimal rwa,
            int small_game_type,
            int ss,
            BigDecimal wa,
            List<WinMatch> wmkl,
            List<String> wskl) {
        public boolean terminal() { return fsn == 0 || fsn == nfsc; }
    }

    public record CompleteRound(
            String roundKey,
            RoundKind kind,
            BigDecimal bet,
            long generatedAtEpochSecond,
            List<Step> deliveries) {
        public CompleteRound {
            deliveries = List.copyOf(deliveries);
            if (deliveries.isEmpty() || !deliveries.get(deliveries.size() - 1).terminal()) {
                throw new IllegalArgumentException("完整Round必须以合法终态结束");
            }
        }

        public BigDecimal totalWin() {
            return deliveries.get(deliveries.size() - 1).rwa();
        }
    }

    /**
     * 当前消费者重建完整局所需的最小事实：仅保留各 Delivery 的随机盘面。
     * 类型、倍率、中奖、累计金额和终态都必须由规则重新计算，不能存入事实。
     */
    public record MinimalRoundFacts(List<List<String>> boards) {
        public MinimalRoundFacts {
            if (boards == null || boards.isEmpty()) throw new IllegalArgumentException("最小事实至少包含一个盘面");
            List<List<String>> copied = new ArrayList<>(boards.size());
            for (List<String> board : boards) copied.add(List.copyOf(board));
            boards = List.copyOf(copied);
        }
    }
}
