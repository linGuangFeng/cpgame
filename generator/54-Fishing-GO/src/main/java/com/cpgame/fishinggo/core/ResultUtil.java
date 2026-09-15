package com.cpgame.fishinggo.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent 243-ways oracle. Does not call RoundGenerator. */
public final class ResultUtil {
    public enum Outcome { LOSS, WIN, FREE_SPINS }
    public record Win(List<String> symbols, List<List<List<Integer>>> coords, BigDecimal payout) {}
    public record Analysis(Outcome outcome, int odds, int deliveries, BigDecimal totalWin) {}

    public Win evaluate(List<String> board, int rpx) {
        requireBoard(board);
        if (rpx < 1) throw new IllegalArgumentException("rpx");
        List<String> symbols = new ArrayList<>();
        List<List<List<Integer>>> groups = new ArrayList<>();
        BigDecimal payout = BigDecimal.ZERO;
        for (String symbol : ProtocolConstants.ORDINARY) {
            List<List<Integer>> reels = matching(board, symbol);
            if (reels.size() < 3) continue;
            payout = payout.add(symbolPayout(symbol, reels, rpx));
            symbols.add(symbol);
            groups.add(reels);
        }
        return new Win(List.copyOf(symbols), List.copyOf(groups), payout.stripTrailingZeros());
    }

    public BigDecimal symbolPayout(String symbol, List<List<Integer>> reels, int rpx) {
        int ways = 1;
        for (List<Integer> reel : reels) ways *= reel.size();
        int pay = ProtocolConstants.PAYTABLE.get(symbol).get(reels.size());
        return BigDecimal.valueOf((long) pay * ways * rpx).multiply(ProtocolConstants.MIN_BET_SIZE);
    }

    private List<List<Integer>> matching(List<String> board, String symbol) {
        List<List<Integer>> reels = new ArrayList<>();
        for (int reel = 0; reel < ProtocolConstants.REELS; reel++) {
            List<Integer> positions = new ArrayList<>();
            for (int row = 0; row < ProtocolConstants.ROWS; row++) {
                String actual = board.get(reel * ProtocolConstants.ROWS + row);
                if (actual.equals(symbol) || actual.equals("WILD")) positions.add(reel * 10 + row);
            }
            if (positions.isEmpty()) break;
            reels.add(List.copyOf(positions));
        }
        return reels;
    }

    public void requireBoard(List<String> board) {
        if (board == null || board.size() != ProtocolConstants.CELLS) throw new IllegalArgumentException("15 cells");
        for (int i = 0; i < board.size(); i++) {
            String s = board.get(i);
            if (!ProtocolConstants.ALL.contains(s)) throw new IllegalArgumentException("symbol " + s);
            int reel = i / ProtocolConstants.ROWS;
            if (s.equals("WILD") && (reel == 0 || reel == 4)) throw new IllegalArgumentException("wild reel");
        }
    }

    public int scatter(List<String> board) {
        requireBoard(board);
        int n = 0;
        for (String s : board) if (s.equals("SC")) n++;
        return n;
    }

    public void requireCaps(List<String> board, boolean free) {
        requireBoard(board);
        int sc = 0, wild = 0;
        int[] scCol = new int[ProtocolConstants.REELS];
        int[] wildCol = new int[ProtocolConstants.REELS];
        for (int i = 0; i < board.size(); i++) {
            int reel = i / ProtocolConstants.ROWS;
            if (board.get(i).equals("SC")) { sc++; scCol[reel]++; }
            if (board.get(i).equals("WILD")) { wild++; wildCol[reel]++; }
        }
        int scBoard = free ? ProtocolConstants.MAX_SCATTER_FREE_BOARD : ProtocolConstants.MAX_SCATTER_PAID;
        int scReel = free ? ProtocolConstants.MAX_SCATTER_FREE_REEL : ProtocolConstants.MAX_SCATTER_PER_REEL;
        int wildBoard = free ? ProtocolConstants.MAX_WILD_FREE : ProtocolConstants.MAX_WILD_PAID;
        if (sc > scBoard) throw new IllegalArgumentException("scatter board cap");
        if (wild > wildBoard) throw new IllegalArgumentException("wild board cap");
        for (int reel = 0; reel < ProtocolConstants.REELS; reel++) {
            if (scCol[reel] > scReel) throw new IllegalArgumentException("scatter reel cap");
            if (wildCol[reel] > ProtocolConstants.MAX_WILD_PER_REEL) throw new IllegalArgumentException("wild reel cap");
        }
    }

    public Analysis analyze(CompleteRound round) {
        Objects.requireNonNull(round);
        BigDecimal total = BigDecimal.ZERO;
        for (CompleteRound.Step step : round.steps()) {
            Win win = evaluate(step.board(), step.rpx());
            if (win.payout().compareTo(step.wa()) != 0) throw new IllegalArgumentException("ways mismatch");
            total = total.add(win.payout());
        }
        Outcome outcome = round.steps().size() > 1 ? Outcome.FREE_SPINS
                : total.signum() == 0 ? Outcome.LOSS : Outcome.WIN;
        int odds = total.divide(ProtocolConstants.MIN_BET_SIZE, 0, RoundingMode.UNNECESSARY).intValueExact();
        return new Analysis(outcome, odds, round.steps().size(), total.stripTrailingZeros());
    }
}
