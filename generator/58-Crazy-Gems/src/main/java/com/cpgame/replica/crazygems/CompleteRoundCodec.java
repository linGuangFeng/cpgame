package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsResultUtil;

/**
 * 10-character ASCII member: 9 reel-major symbols + 1 rpx code.
 * rpx cannot be inferred from symbols, so it is stored. wmkl/wa are not.
 */
public final class CompleteRoundCodec {
    public static final int MEMBER_LENGTH = 10;

    public String encode(CompleteRoundFact fact) {
        CrazyGemsBoard board = fact.board();
        StringBuilder out = new StringBuilder(MEMBER_LENGTH);
        for (String symbol : board.rskl()) out.append(symbolChar(symbol));
        out.append(rpxChar(board.rpx()));
        return out.toString();
    }

    public CompleteRoundFact decode(String value) {
        if (value == null) throw new IllegalArgumentException("empty member");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[') {
            throw new IllegalArgumentException("JSON members are forbidden");
        }
        if (trimmed.length() != MEMBER_LENGTH) {
            throw new IllegalArgumentException("member must be " + MEMBER_LENGTH + " chars");
        }
        String[] rskl = new String[CrazyGemsBoard.CELLS];
        for (int i = 0; i < CrazyGemsBoard.CELLS; i++) rskl[i] = symbolFromChar(trimmed.charAt(i));
        int rpx = rpxFromChar(trimmed.charAt(9));
        return new CompleteRoundFact(CompleteRoundFact.VERSION, rskl, rpx);
    }

    public CrazyGemsEvaluation verify(String member) {
        CompleteRoundFact fact = decode(member);
        CrazyGemsEvaluation evaluation = CrazyGemsResultUtil.evaluate(fact.board());
        if (encode(fact).equals(member) == false) {
            throw new IllegalStateException("codec round-trip mismatch");
        }
        return evaluation;
    }

    static char symbolChar(String symbol) {
        return switch (symbol) {
            case "WILD" -> 'W';
            case "H1" -> '1';
            case "H2" -> '2';
            case "H3" -> '3';
            case "H4" -> '4';
            case "H5" -> '5';
            case "H6" -> '6';
            case "H7" -> '7';
            default -> throw new IllegalArgumentException("symbol: " + symbol);
        };
    }

    static String symbolFromChar(char value) {
        return switch (value) {
            case 'W' -> "WILD";
            case '1' -> "H1";
            case '2' -> "H2";
            case '3' -> "H3";
            case '4' -> "H4";
            case '5' -> "H5";
            case '6' -> "H6";
            case '7' -> "H7";
            default -> throw new IllegalArgumentException("symbol char: " + value);
        };
    }

    static char rpxChar(int rpx) {
        return switch (rpx) {
            case 1 -> '1';
            case 2 -> '2';
            case 3 -> '3';
            case 5 -> '5';
            case 10 -> 'A';
            case 15 -> 'F';
            default -> throw new IllegalArgumentException("rpx: " + rpx);
        };
    }

    static int rpxFromChar(char value) {
        return switch (value) {
            case '1' -> 1;
            case '2' -> 2;
            case '3' -> 3;
            case '5' -> 5;
            case 'A' -> 10;
            case 'F' -> 15;
            default -> throw new IllegalArgumentException("rpx char: " + value);
        };
    }
}
