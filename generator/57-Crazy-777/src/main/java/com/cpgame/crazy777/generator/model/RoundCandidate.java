package com.cpgame.crazy777.generator.model;

import java.util.List;

public record RoundCandidate(List<List<String>> boards) {
    public RoundCandidate {
        boards = boards.stream().map(List::copyOf).toList();
    }
}
