package com.cpgame.saci.generator.model;

/** rskl 格子：copies + 符号 + 倍率。3 字符为 CSm，4+ 字符为 CS 后跟十进制倍率。 */
public record Cell(String raw, int copies, String symbol, int multiplier) {
    public static Cell parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("空 rskl 格子");
        if (raw.length() == 3) {
            int copies = digit(raw.charAt(0));
            String symbol = String.valueOf(raw.charAt(1));
            char m = raw.charAt(2);
            int multiplier = m == 'a' ? 10 : digit(m);
            return new Cell(raw, copies, symbol, multiplier);
        }
        if (raw.length() >= 4) {
            int copies = digit(raw.charAt(0));
            String symbol = String.valueOf(raw.charAt(1));
            int multiplier = Integer.parseInt(raw.substring(2));
            return new Cell(raw, copies, symbol, multiplier);
        }
        throw new IllegalArgumentException("非法 rskl 格子: " + raw);
    }

    public boolean wild() {
        return "9".equals(symbol);
    }

    public boolean scatter() {
        return "a".equals(symbol);
    }

    private static int digit(char c) {
        if (c < '0' || c > '9') throw new IllegalArgumentException("非法数字字符: " + c);
        return c - '0';
    }
}
