package com.cpgame.crazybirds.generator.model;

import java.math.BigDecimal;
import java.util.List;

public record WinWay(String symbol, List<List<Integer>> groups, int ways, int wildRpx, BigDecimal amount) {}
