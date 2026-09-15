package com.cpgame.coinmastergo.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class WinMatch {
    public String symbol;
    public BigDecimal win;
    public List<List<Integer>> coordinates = new ArrayList<>();

    public WinMatch() { }

    public WinMatch(String symbol, BigDecimal win, List<List<Integer>> coordinates) {
        this.symbol = symbol;
        this.win = win;
        this.coordinates = coordinates;
    }
}
