package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** ASCII v2 codec: complete terminal Round facts; payout/result type are recomputed. */
public final class MinimalFactCodec {
    private static final List<String> SYMBOLS = List.of("H0", "H1", "H2", "H3", "H4", "WILD");
    private static final String CODES = "01234W";

    public byte[] encode(GameRound round) { return encode(RoundFacts.from(round)); }

    public byte[] encode(RoundFacts facts) {
        StringBuilder out = new StringBuilder("v2|");
        out.append("bs=").append(facts.betSize().stripTrailingZeros().toPlainString());
        out.append("|bl=").append(facts.betLevel());
        out.append("|rpx=").append(facts.reelMultiplier());
        out.append("|s=");
        for (String symbol : facts.symbols()) out.append(code(symbol));
        out.append("|rk=").append(facts.roundKey());
        out.append("|di=").append(facts.deliveryIndex());
        out.append("|tm=").append(facts.terminal() ? 1 : 0);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public RoundFacts decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("Redis member is null");
        String text = new String(encoded, StandardCharsets.US_ASCII);
        String[] fields = text.split("\\|", -1);
        if (fields.length != 8 || !"v2".equals(fields[0])) throw new IllegalArgumentException("invalid gid42 member");
        BigDecimal bs = new BigDecimal(value(fields, "bs"));
        int bl = Integer.parseInt(value(fields, "bl"));
        int rpx = Integer.parseInt(value(fields, "rpx"));
        String symbols = value(fields, "s");
        if (symbols.length() != 3) throw new IllegalArgumentException("invalid symbol payload");
        List<String> decodedSymbols = List.of(symbol(symbols.charAt(0)), symbol(symbols.charAt(1)), symbol(symbols.charAt(2)));
        int deliveryIndex = Integer.parseInt(value(fields, "di"));
        String terminal = value(fields, "tm");
        if (!"0".equals(terminal) && !"1".equals(terminal)) throw new IllegalArgumentException("invalid terminal flag");
        return new RoundFacts(bs, bl, decodedSymbols, rpx, value(fields, "rk"), deliveryIndex, "1".equals(terminal));
    }

    private static String value(String[] fields, String name) {
        String prefix = name + "=";
        for (String field : fields) if (field.startsWith(prefix)) return field.substring(prefix.length());
        throw new IllegalArgumentException("missing member field " + name);
    }

    private static char code(String symbol) {
        int index = SYMBOLS.indexOf(symbol);
        if (index < 0) throw new IllegalArgumentException("unknown symbol " + symbol);
        return CODES.charAt(index);
    }

    private static String symbol(char code) {
        int index = CODES.indexOf(code);
        if (index < 0) throw new IllegalArgumentException("unknown symbol code " + code);
        return SYMBOLS.get(index);
    }
}
