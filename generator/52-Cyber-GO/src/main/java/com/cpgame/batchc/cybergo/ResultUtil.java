package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoModels.*;
import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 独立结果反推工具：只读取盘面和Delivery事实，不读取生成器的目标类型。 */
public final class ResultUtil {
    private ResultUtil() { }

    public record Evaluation(BigDecimal baseWin, List<WinMatch> matches, List<String> winningSymbols,
                             int scatterCount, int wildCount) {
        public boolean isLoss() { return baseWin.signum() == 0 && scatterCount < 3; }
    }

    public record RoundResult(RoundKind inferredKind, BigDecimal totalWin,
                              List<Evaluation> stepEvaluations, int freeSpinCount) { }

    public static Evaluation evaluate(List<String> board, int betLevel, BigDecimal betSize) {
        validateBoard(board);
        List<WinMatch> matches = new ArrayList<>();
        List<String> winningSymbols = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String symbol : PAYING_SYMBOLS) {
            List<List<Integer>> matchingCells = new ArrayList<>();
            long ways = 1;
            int consecutive = 0;
            for (int reel = 0; reel < REELS; reel++) {
                List<Integer> cells = new ArrayList<>();
                for (int row = 0; row < ROWS; row++) {
                    String cell = board.get(reel * ROWS + row);
                    if (cell.equals(symbol) || cell.equals(WILD)) cells.add(reel * 10 + row);
                }
                if (cells.isEmpty()) break;
                matchingCells.add(List.copyOf(cells));
                ways *= cells.size();
                consecutive++;
            }
            if (consecutive >= 3) {
                int pay = PAYTABLE.get(symbol).get(consecutive);
                BigDecimal amount = betSize.multiply(BigDecimal.valueOf(betLevel))
                        .multiply(BigDecimal.valueOf(pay)).multiply(BigDecimal.valueOf(ways))
                        .setScale(2, RoundingMode.HALF_UP);
                total = total.add(amount);
                matches.add(new WinMatch(symbol, amount.toPlainString(), List.copyOf(matchingCells)));
                winningSymbols.add(symbol);
            }
        }
        int scatters = (int) board.stream().filter(SCATTER::equals).count();
        int wilds = (int) board.stream().filter(WILD::equals).count();
        return new Evaluation(total.setScale(2), List.copyOf(matches), List.copyOf(winningSymbols), scatters, wilds);
    }

    /** 反推整个Round的真实类型、倍率轨迹、金额和终态；任何声明字段不一致都会拒绝。 */
    public static RoundResult reverse(CompleteRound round) {
        if (round == null || round.deliveries().isEmpty()) throw new IllegalArgumentException("Round无Delivery");
        List<Evaluation> evaluations = new ArrayList<>(round.deliveries().size());
        BigDecimal cumulative = BigDecimal.ZERO.setScale(2);
        Step first = round.deliveries().getFirst();
        int expectedFsn = 0;
        int multiplier = 2;
        int collectedWilds = 0;

        for (int index = 0; index < round.deliveries().size(); index++) {
            Step step = round.deliveries().get(index);
            if(step.bl()!=first.bl() || step.bs().compareTo(first.bs())!=0 || step.bl()<1 || step.bl()>10
                || !(step.bs().compareTo(new BigDecimal("0.02"))==0 || step.bs().compareTo(new BigDecimal("0.2"))==0))
                throw new IllegalArgumentException("Invalid or changed Round bet");
            Evaluation evaluation = evaluate(step.rskl(), step.bl(), step.bs());
            evaluations.add(evaluation);
            if (!evaluation.matches().equals(step.wmkl()) || !evaluation.winningSymbols().equals(step.wskl())) {
                throw new IllegalArgumentException("Step Ways明细反推失败: " + index);
            }
            if (index == 0) {
                expectedFsn = FREE_SPIN_AWARDS.getOrDefault(evaluation.scatterCount(), 0);
                if (step.nfsc() != 0 || step.fsn() != expectedFsn || step.rpx() != 1 || step.gt() != 1
                        || step.small_game_type() != 0 || step.ba().compareTo(step.bs().multiply(BigDecimal.valueOf((long)step.bl()*BASIC_BET_FACTOR))) != 0) {
                    throw new IllegalArgumentException("付费起点字段或Scatter触发反推失败");
                }
            } else {
                collectedWilds += evaluation.wildCount();
                while (collectedWilds >= 3 && multiplier < 20) {
                    multiplier = Math.min(20, multiplier + 2);
                    collectedWilds -= 3;
                }
                if (step.fsn() != expectedFsn || step.nfsc() != index || step.rpx() != multiplier
                        || step.gt() != 2 || step.small_game_type() != 2 || step.ba().signum() != 0
                        || evaluation.scatterCount() != 0 || step.ss() != (index == expectedFsn ? 0 : 1)) {
                    throw new IllegalArgumentException("免费Step相邻状态或倍率反推失败: " + index);
                }
            }
            BigDecimal expectedWin = evaluation.baseWin().multiply(BigDecimal.valueOf(step.rpx())).setScale(2);
            if (expectedWin.compareTo(step.wa()) != 0) throw new IllegalArgumentException("Step中奖金额反推失败: " + index);
            cumulative = cumulative.add(expectedWin).setScale(2);
            if (cumulative.compareTo(step.rwa()) != 0) throw new IllegalArgumentException("Round金额守恒失败: " + index);
            if (index < round.deliveries().size() - 1 && step.terminal()) {
                throw new IllegalArgumentException("Round在最后Delivery前已终止: " + index);
            }
        }

        Step last = round.deliveries().getLast();
        if (!last.terminal()) throw new IllegalArgumentException("Round未合法终止");
        if (expectedFsn == 0 && round.deliveries().size() != 1) throw new IllegalArgumentException("普通局只能有一个Delivery");
        if (expectedFsn > 0 && round.deliveries().size() != expectedFsn + 1) throw new IllegalArgumentException("免费Delivery数量非法");
        RoundKind inferred = expectedFsn > 0 ? RoundKind.FREE_SPINS
                : first.wa().signum() > 0 ? RoundKind.ORDINARY_WIN : RoundKind.ORDINARY_LOSS;
        return new RoundResult(inferred, cumulative, List.copyOf(evaluations), expectedFsn);
    }

    public static void validateCompleteRound(CompleteRound round) {
        RoundResult result = reverse(round);
        if (result.inferredKind() != round.kind()) throw new IllegalArgumentException("Round声明类型与反推类型不一致");
    }

    private static void validateBoard(List<String> board) {
        if (board == null || board.size() != VISIBLE_CELLS) throw new IllegalArgumentException("rskl必须恰含15个可见格");
        if (java.util.Collections.frequency(board, SCATTER)>4 || java.util.Collections.frequency(board,WILD)>3)
            throw new IllegalArgumentException("Special symbol board ceiling exceeded");
        for(int reel=0;reel<5;reel++) {
            List<String> window=board.subList(reel*3,reel*3+3);
            if(java.util.Collections.frequency(window,SCATTER)>1 || java.util.Collections.frequency(window,WILD)>1)
                throw new IllegalArgumentException("Special symbol reel ceiling exceeded");
        }
        for (int index = 0; index < board.size(); index++) {
            String symbol = board.get(index);
            int reel = index / ROWS;
            if (!PAYING_SYMBOLS.contains(symbol) && !WILD.equals(symbol) && !SCATTER.equals(symbol)) {
                throw new IllegalArgumentException("未知符号: " + symbol);
            }
            if (WILD.equals(symbol) && (reel == 0 || reel == 4)) throw new IllegalArgumentException("Wild只能出现在卷轴2/3/4");
        }
    }
}
