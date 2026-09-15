package com.cpgame.replica.freedomday;

import java.math.BigDecimal;

public record RoundVerification(BigDecimal multiplier, int spins, int pages,
                                int maxConsecutiveWins, boolean featureBuy) { }
