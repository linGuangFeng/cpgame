package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.util.List;

/** Production ways evaluator. Delegates to ResultUtil so factory and reverse stay on one scan. */
final class RuleEvaluator {
    private RuleEvaluator() { }

    static ResultUtil.Evaluation evaluate(List<String> board, int level, BigDecimal size) {
        return ResultUtil.evaluate(board, level, size);
    }
}
