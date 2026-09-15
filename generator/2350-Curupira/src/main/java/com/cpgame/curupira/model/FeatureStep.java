package com.cpgame.curupira.model;

import java.util.List;

/** 完整局里的一步可见事实。符号步用 1/2/3/4/11-14/21/31；Hold 步用 0-10 金币面值。 */
public record FeatureStep(
        Role role,
        List<Integer> cells,
        EvaluatedBoard evaluated,
        int st,
        int tt,
        int featureT,
        int gt,
        int resGt,
        int fcc,
        int fcnw,
        List<Integer> fcn,
        List<Integer> fcp,
        int redisUnits) {

    public enum Role { ORDINARY, TRIGGER, FREE_EW, HOLD }

    public FeatureStep {
        cells = List.copyOf(cells);
        fcn = List.copyOf(fcn);
        fcp = List.copyOf(fcp);
        if (cells.size() != 15 || redisUnits < 0 || st < 0 || tt < 0) {
            throw new IllegalArgumentException("Invalid feature step");
        }
        if (role == Role.HOLD) {
            if (evaluated != null) throw new IllegalArgumentException("Hold step has no payline board");
        } else if (evaluated == null) {
            throw new IllegalArgumentException("Symbol step requires evaluated board");
        }
    }

    public EvaluatedBoard evaluatedBoard() {
        if (evaluated == null) throw new IllegalStateException("Hold step has no payline board");
        return evaluated;
    }

    public static FeatureStep symbol(Role role, List<Integer> cells, EvaluatedBoard evaluated,
                                     int st, int tt, int featureT, int gt, int resGt) {
        return new FeatureStep(role, cells, evaluated, st, tt, featureT, gt, resGt,
                15, 0, List.of(), List.of(), evaluated.multiplierSum());
    }

    public static FeatureStep hold(List<Integer> cells, int st, int tt, int fcc, int fcnw,
                                   List<Integer> fcn, List<Integer> fcp, int gt, int redisUnits) {
        return new FeatureStep(Role.HOLD, cells, null, st, tt, 3, gt, 3, fcc, fcnw, fcn, fcp, redisUnits);
    }
}
