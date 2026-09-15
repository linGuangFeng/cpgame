package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;

import java.math.BigDecimal;

public record RoundVerification(BigDecimal terminalRwa, int actualMultiplier, RoundClass roundClass,
                                int paidPages, int freeSpins, int maxConsecutiveWins, boolean special) { }
