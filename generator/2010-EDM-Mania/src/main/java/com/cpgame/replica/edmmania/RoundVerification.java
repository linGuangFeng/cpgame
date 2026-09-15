package com.cpgame.replica.edmmania;

import java.math.BigDecimal;

public record RoundVerification(BigDecimal multiplier, int spins, int pages,
                                int maxConsecutiveWins, boolean featureBuy) { }
