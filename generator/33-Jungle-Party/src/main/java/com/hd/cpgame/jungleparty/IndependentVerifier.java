package com.hd.cpgame.jungleparty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Recomputes every generated fact without trusting serialized award fields. */
public final class IndependentVerifier {
    private IndependentVerifier() {}
    public static Verification verify(GameRuleCore.Round round) {
        List<String> errors = new ArrayList<>();
        if (round.rawGameId() != 33) errors.add("raw gid is not 33");
        BigDecimal expectedBet = round.betSize().multiply(BigDecimal.valueOf(round.betLevel() * 25L));
        if (expectedBet.compareTo(round.paidBet()) != 0) errors.add("paid bet mismatch");
        BigDecimal cumulative = BigDecimal.ZERO;
        if (round.deliveries().isEmpty()) errors.add("Round has no deliveries");
        for (int index = 0; index < round.deliveries().size(); index++) {
            GameRuleCore.Delivery delivery = round.deliveries().get(index);
            if (delivery.index() != index) errors.add("delivery index mismatch at " + index);
            if (index == 0 && delivery.paidBet().compareTo(round.paidBet()) != 0) errors.add("first delivery bet mismatch");
            if (index > 0 && delivery.paidBet().signum() != 0) errors.add("continuation bet must be zero");
            GameRuleCore.Evaluation evaluation = GameRuleCore.evaluate(delivery.board(), round.betLevel(), round.betSize(), delivery.rpx());
            if (!evaluation.wins().equals(delivery.wins())) errors.add("wins mismatch at " + index);
            if (evaluation.award().compareTo(delivery.award()) != 0) errors.add("award mismatch at " + index);
            cumulative = cumulative.add(evaluation.award());
            if (cumulative.compareTo(delivery.cumulativeAward()) != 0) errors.add("cumulative award mismatch at " + index);
            boolean finalDelivery = index + 1 == round.deliveries().size();
            if (delivery.terminal() != finalDelivery) errors.add("terminal marker mismatch at " + index);
            if (round.scenario() == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS) {
                if (delivery.smallGameType() != 2 || delivery.fsn() < 8) errors.add("free mode fields mismatch at " + index);
                if (index == 0 && (delivery.gameType() != 1 || delivery.nfsc() != 0)) errors.add("free trigger mismatch");
                if (index > 0 && (delivery.gameType() != 2 || delivery.nfsc() != index)) errors.add("free continuation mismatch at " + index);
            }
        }
        if (round.scenario() == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS) {
            GameRuleCore.Delivery last=round.deliveries().get(round.deliveries().size()-1);
            if (last.nfsc()!=last.fsn()) errors.add("free Round did not terminate at nfsc=fsn");
        }
        if (cumulative.compareTo(round.totalAward()) != 0) errors.add("Round total mismatch");
        return new Verification(errors.isEmpty(), List.copyOf(errors));
    }
    public record Verification(boolean pass, List<String> errors) {}
}
