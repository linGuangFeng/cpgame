package com.cpgame.curupira.model;
import com.cpgame.curupira.core.RulesContract;
import java.util.List;
public record CompleteRound(long roundKey, int sourceGameId, String rulesVersion, String rulesHash,
                            String resultPool, List<RoundStep> deliveries) {
    public CompleteRound {
        deliveries = List.copyOf(deliveries);
        if (roundKey <= 0 || sourceGameId != RulesContract.GAME_ID || deliveries.isEmpty()) {
            throw new IllegalArgumentException("Invalid complete Round");
        }
    }
    public int totalMultiplier(){return deliveries.stream().mapToInt(s->s.evaluatedBoard().multiplierSum()).sum();}
}
