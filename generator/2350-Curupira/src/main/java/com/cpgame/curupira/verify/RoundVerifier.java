package com.cpgame.curupira.verify;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.FeatureStep.Role;
import java.util.ArrayList;
import java.util.List;

/** 从最小事实独立反推普通与两个 Mary 场景的完整状态边界。 */
public final class RoundVerifier {
    private final ResultUtil independent = new ResultUtil();

    public void verifyFact(CompleteRoundFact fact) {
        if (fact == null || fact.steps().isEmpty()) throw new IllegalArgumentException("Invalid complete fact");
        switch (fact.kind()) {
            case LOSS, WIN, EXPANDING_WILD, TRIGGER -> verifyPaidStart(fact);
            case FREE_EW, BUY_FE -> verifyFree(fact);
            case HOLD, BUY_HS -> verifyHold(fact);
        }
    }

    private void verifyPaidStart(CompleteRoundFact fact) {
        if (fact.entry() != CompleteRoundFact.EntryKind.PAID || fact.steps().size() != 1) {
            throw new IllegalArgumentException("2350 paid start must contain exactly one step");
        }
        FeatureStep step = fact.steps().get(0);
        EvaluatedBoard board = independent.evaluate(step.cells());
        requireScatterLimit(board);
        if (!board.equals(step.evaluatedBoard())) throw new IllegalArgumentException("派生牌面字段不一致");
        if (independent.classifyPaid(board) != fact.kind()) throw new IllegalArgumentException("事实分类不一致");
        if (fact.kind() == Kind.TRIGGER) {
            if (step.role() != Role.TRIGGER || step.st() != 1 || step.tt() != 1 || step.featureT() != 1) {
                throw new IllegalArgumentException("Scatter 触发状态不一致");
            }
        } else if (step.role() != Role.ORDINARY || step.st() != 0 || step.tt() != 0) {
            throw new IllegalArgumentException("普通状态不一致");
        }
    }

    private void verifyFree(CompleteRoundFact fact) {
        if (fact.steps().size() != GameRules.FREE_EXPANDING_WILD_COUNT) {
            throw new IllegalArgumentException("Free Expanding Wild must contain six spins");
        }
        for (int index = 0; index < fact.steps().size(); index++) {
            FeatureStep step = fact.steps().get(index);
            EvaluatedBoard board = independent.evaluate(step.cells());
            requireScatterLimit(board);
            if (step.role() != Role.FREE_EW || !board.equals(step.evaluatedBoard())) {
                throw new IllegalArgumentException("Free Expanding Wild derived board mismatch");
            }
            if (board.expandingWildColumns().size() != 1) {
                throw new IllegalArgumentException("Each free spin requires exactly one expanding Wild column");
            }
            if (board.scatterCount() >= GameRules.SCATTER_TRIGGER) {
                throw new IllegalArgumentException("Free retrigger is unresolved and cannot enter this subset");
            }
            int expectedRemaining = GameRules.FREE_EXPANDING_WILD_COUNT - 1 - index;
            if (step.st() != expectedRemaining || step.tt() != GameRules.FREE_EXPANDING_WILD_COUNT
                    || step.featureT() != 2 || step.resGt() != 2
                    || step.gt() != (index == 0 ? 2 : 1)
                    || step.redisUnits() != board.multiplierSum()) {
                throw new IllegalArgumentException("Free Expanding Wild state transition mismatch");
            }
        }
    }

    private void verifyHold(CompleteRoundFact fact) {
        if (fact.steps().size() > GameRules.HOLD_START_SPINS + GameRules.COIN_TOTAL_COUNT) {
            throw new IllegalArgumentException("Hold & Spins exceeds its reachable step bound");
        }
        List<Integer> previous = new ArrayList<>(java.util.Collections.nCopies(GameRules.COIN_TOTAL_COUNT, 0));
        int remaining = GameRules.HOLD_START_SPINS;
        for (int index = 0; index < fact.steps().size(); index++) {
            FeatureStep step = fact.steps().get(index);
            if (step.role() != Role.HOLD
                    || step.cells().stream().anyMatch(value -> value < 0 || value > GameRules.COIN_MAX)) {
                throw new IllegalArgumentException("Invalid Hold & Spins cells");
            }
            List<Integer> newPositions = new ArrayList<>();
            int valueSum = 0;
            int filled = 0;
            for (int position = 0; position < step.cells().size(); position++) {
                int before = previous.get(position);
                int after = step.cells().get(position);
                if (before != 0 && after != before) throw new IllegalArgumentException("Held coin changed value");
                if (before == 0 && after > 0) {
                    newPositions.add(position);
                    valueSum += after;
                }
                if (after > 0) filled++;
            }
            int expectedRemaining = remaining - 1 + newPositions.size();
            if (filled == GameRules.COIN_TOTAL_COUNT) expectedRemaining = 0;
            if (!step.fcn().equals(newPositions) || !step.fcp().equals(step.cells())
                    || step.fcnw() != valueSum || step.fcc() != filled
                    || step.redisUnits() != valueSum * GameRules.REDIS_UNITS_PER_ACTUAL_MULTIPLIER
                    || step.st() != expectedRemaining || step.tt() != GameRules.HOLD_START_SPINS
                    || step.featureT() != 3 || step.resGt() != 3
                    || step.gt() != (index == 0 ? 3 : 1)) {
                throw new IllegalArgumentException("Hold & Spins state transition mismatch");
            }
            previous = step.cells();
            remaining = expectedRemaining;
            if (remaining == 0 && index != fact.steps().size() - 1) {
                throw new IllegalArgumentException("Hold & Spins continued after terminal state");
            }
        }
        if (remaining != 0) throw new IllegalArgumentException("Hold & Spins did not terminate");
    }

    private static void requireScatterLimit(EvaluatedBoard board) {
        if (!GameRules.hasAtMostOneScatterPerColumn(board.ps())) {
            throw new IllegalArgumentException("同列最多允许一个 Scatter");
        }
    }
}
