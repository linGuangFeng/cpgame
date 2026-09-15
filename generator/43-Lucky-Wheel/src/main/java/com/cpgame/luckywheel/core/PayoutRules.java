package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.util.List;

/** 仅供生成侧使用；独立 ResultUtil 不调用本类。 */
final class PayoutRules {
    private PayoutRules() { }

    static BigDecimal score(List<String> symbols) {
        StringBuilder number = new StringBuilder();
        for (String symbol : symbols) {
            switch (symbol) {
                case "H0" -> { }
                case "H1" -> number.append('0');
                case "H2" -> number.append("00");
                case "H3" -> number.append('1');
                case "H4" -> number.append('5');
                case "H5" -> number.append("10");
                default -> throw new IllegalArgumentException("未知符号: " + symbol);
            }
        }
        return number.length() == 0 ? BigDecimal.ZERO : new BigDecimal(number.toString());
    }
}
