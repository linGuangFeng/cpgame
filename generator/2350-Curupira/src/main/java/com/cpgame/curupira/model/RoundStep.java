package com.cpgame.curupira.model;

public record RoundStep(int deliveryIndex, EvaluatedBoard evaluatedBoard) {
    public RoundStep {
        if (deliveryIndex < 1 || evaluatedBoard == null) {
            throw new IllegalArgumentException("A delivery requires a positive index and evaluated board");
        }
    }
}
