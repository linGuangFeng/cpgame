package com.cpgame.crazy777.generator.model;

import java.math.BigDecimal;
import java.util.List;

public record RoundFacts(String roundKey, BigDecimal betSize, int betLevel, List<List<String>> boards) {
    public RoundFacts {
        boards = boards.stream().map(List::copyOf).toList();
    }
}
