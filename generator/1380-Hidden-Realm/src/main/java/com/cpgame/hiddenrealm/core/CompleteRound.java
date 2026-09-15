package com.cpgame.hiddenrealm.core;

import java.util.List;

/** One paid GameResult plus every continuation until type_skill == total_skill_type. */
public record CompleteRound(List<Delivery> deliveries) {
    public CompleteRound {
        deliveries = List.copyOf(deliveries);
        if (deliveries.isEmpty()) throw new IllegalArgumentException("empty round");
    }

    public record Delivery(int type, int typeSkill, int maxPhase, int collection, int smallGameType, List<Page> pages) {
        public Delivery {
            pages = List.copyOf(pages);
            if (pages.isEmpty()) throw new IllegalArgumentException("empty delivery");
        }
    }

    public record Page(int[][] board) {
        public Page {
            int[][] copy = new int[5][5];
            if (board == null || board.length != 5) throw new IllegalArgumentException("board");
            for (int c = 0; c < 5; c++) {
                if (board[c] == null || board[c].length != 5) throw new IllegalArgumentException("column");
                copy[c] = board[c].clone();
            }
            board = copy;
        }
        @Override public int[][] board() {
            int[][] copy = new int[5][5];
            for (int c = 0; c < 5; c++) copy[c] = board[c].clone();
            return copy;
        }
    }

    public int maxPhase() {
        return deliveries.get(deliveries.size() - 1).maxPhase();
    }

    public int totalOdds() {
        GameRuleCore rules = new GameRuleCore();
        int sum = 0;
        for (Delivery delivery : deliveries) {
            for (Page page : delivery.pages()) {
                sum += rules.evaluatePage(page.board(), delivery.typeSkill()).oddsSum();
            }
        }
        return sum;
    }

    public boolean special() { return maxPhase() > 0; }
    public boolean win() { return totalOdds() > 0; }
}
