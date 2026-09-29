package com.cpgame.monsterslayer.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/** Pure ways evaluator; all amounts are represented as integer hundredths of paid bet. */
public final class ResultUtil {
    private ResultUtil() { }

    public record Award(int symbol, int reels, int ways, int payoutCenti, List<Integer> cells) { }
    public record StepResult(List<Award> awards, List<Integer> winningCells, int multiplierCenti) { }
    public record RoundResult(GameRuleCore.RoundClass roundClass, List<StepResult> steps, int multiplierCenti) { }

    public static StepResult evaluateStep(GameRuleCore.Step step) {
        boolean feature = step.gameType() > 0;
        int[] board = step.board();
        HuntTrace.Payout featurePay = HuntTrace.payout(step);
        List<Award> awards = new ArrayList<>();
        Set<Integer> allWinning = new LinkedHashSet<>();
        int total = 0;
        for (int symbol = 1; symbol <= 10; symbol++) {
            List<List<Integer>> paths = new ArrayList<>();
            for (int row = 0; row < GameRuleCore.ROWS; row++) if (GameRuleCore.matches(board[row], symbol, feature)) paths.add(new ArrayList<>(List.of(row)));
            if (paths.isEmpty()) continue;
            Map<Integer,List<List<Integer>>> terminalByReels = new TreeMap<>((a,b) -> Integer.compare(b,a));
            for (int col = 1; col < GameRuleCore.COLS; col++) {
                List<List<Integer>> next = new ArrayList<>();
                for (List<Integer> path : paths) {
                    boolean extendedAny = false;
                    for (int row = 0; row < GameRuleCore.ROWS; row++) {
                        int cell = col * GameRuleCore.ROWS + row;
                        if (GameRuleCore.matches(board[cell], symbol, feature) && GameRuleCore.connects(path.get(path.size()-1), cell)) {
                            List<Integer> extended = new ArrayList<>(path); extended.add(cell); next.add(extended); extendedAny = true;
                        }
                    }
                    if (!extendedAny && path.size() >= 3) terminalByReels.computeIfAbsent(path.size(), ignored -> new ArrayList<>()).add(path);
                }
                if (next.isEmpty()) { paths = List.of(); break; }
                paths = next;
            }
            if (!paths.isEmpty() && paths.get(0).size() >= 3) terminalByReels.computeIfAbsent(paths.get(0).size(), ignored -> new ArrayList<>()).addAll(paths);
            for (Map.Entry<Integer,List<List<Integer>>> entry : terminalByReels.entrySet()) {
                int reels = entry.getKey(), payout = GameRuleCore.payoutCenti(symbol, reels);
                Set<Integer> cells = new LinkedHashSet<>(); for (List<Integer> path : entry.getValue()) cells.addAll(path);
                List<Integer> orderedCells = new ArrayList<>(cells);
                int natural = -1;
                for (int i = 0; i < orderedCells.size(); i++) if (board[orderedCells.get(i)] == symbol) { natural = i; break; }
                // A chain made only from feature placeholders has no paying
                // symbol to identify it and is not a valid captured win.
                if (natural < 0) continue;
                if (natural > 0) { int first = orderedCells.remove(natural); orderedCells.add(0, first); }
                // Split tiles multiply the number of ways; upgraded ICE FANG
                // multiplies only paths through that Wild. Global ICE multiplier applies once.
                Map<Integer,Integer> weightedWays = new TreeMap<>();
                for (List<Integer> path : entry.getValue()) {
                    int ways = 1, local = 1;
                    for (int cell : path) {
                        if (featurePay.split().contains(cell)) ways = Math.multiplyExact(ways, 2);
                        if (featurePay.doubled().contains(cell)) local = Math.multiplyExact(local, 2);
                    }
                    weightedWays.merge(local, ways, Math::addExact);
                }
                for (var group : weightedWays.entrySet()) {
                    int adjustedPayout = Math.multiplyExact(payout, Math.multiplyExact(featurePay.multiplier(), group.getKey()));
                    int value = Math.multiplyExact(adjustedPayout, group.getValue());
                    Set<Integer> groupCells = new LinkedHashSet<>();
                    for (List<Integer> path : entry.getValue()) {
                        int local = 1;
                        for (int cell : path) if (featurePay.doubled().contains(cell)) local *= 2;
                        if (local == group.getKey()) groupCells.addAll(path);
                    }
                    List<Integer> ordered = new ArrayList<>(groupCells);
                    for (int i = 0; i < ordered.size(); i++) if (board[ordered.get(i)] == symbol) {
                        int anchor = ordered.remove(i); ordered.add(0, anchor); break;
                    }
                    awards.add(new Award(symbol, reels, group.getValue(), adjustedPayout, List.copyOf(ordered)));
                    allWinning.addAll(groupCells); total = Math.addExact(total, value);
                }
            }
        }
        return new StepResult(List.copyOf(awards), List.copyOf(allWinning), total);
    }

    public static RoundResult evaluate(GameRuleCore.CompleteRound round) {
        GameRuleCore.validate(round);
        List<StepResult> steps = new ArrayList<>(); int total = 0;
        for (GameRuleCore.Step step : round.steps()) { StepResult result = evaluateStep(step); steps.add(result); total = Math.addExact(total, result.multiplierCenti()); }
        GameRuleCore.RoundClass kind;
        if (round.buyType() == 3 || round.buyType() == 4 || round.buyType() == 5) kind = GameRuleCore.RoundClass.BUY_FEATURE;
        else if (round.special()) kind = GameRuleCore.RoundClass.MONSTER_FEATURE;
        else kind = total == 0 ? GameRuleCore.RoundClass.ORDINARY_LOSS : GameRuleCore.RoundClass.ORDINARY_WIN;
        return new RoundResult(kind, List.copyOf(steps), total);
    }

    public static int redisMultiplierCenti(GameRuleCore.CompleteRound round) {
        // Redis倍率始终以最基础押注为分母。购买倍数只用于扣款，不能改变
        // 牌面实际倍率，更不能通过除以购买价后四舍五入破坏倍率桶精度。
        return evaluate(round).multiplierCenti();
    }
}
