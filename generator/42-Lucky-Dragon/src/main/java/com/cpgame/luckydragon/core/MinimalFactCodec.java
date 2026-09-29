package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/** ASCII Redis member codec. New writes are lossless v2; legacy # and LD3| remain readable. */
public final class MinimalFactCodec {
    private static final List<String> SYMBOLS = List.of("H0", "H1", "H2", "H3", "H4", "WILD");
    private static final String CODES = "01234W";
    private static final String PREFIX = "LD3|";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final GameRuleCore RULES = new GameRuleCore();
    private static final RandomRoundGenerator LOSSES = new RandomRoundGenerator(RULES);

    public byte[] encode(GameRound round) { return encode(RoundFacts.from(round)); }

    public byte[] encode(RoundFacts facts) {
        // Lossless v2 write: LoaderMain and server require encode→decode RoundFacts equality.
        // Legacy "#" / LD3| remain readable in decode for any old cache rows.
        StringBuilder out = new StringBuilder(96);
        out.append("v2");
        out.append("|bs=").append(facts.betSize().stripTrailingZeros().toPlainString());
        out.append("|bl=").append(facts.betLevel());
        out.append("|rpx=").append(facts.reelMultiplier());
        out.append("|s=");
        for (String symbol : facts.symbols()) out.append(code(symbol));
        out.append("|rk=").append(facts.roundKey());
        out.append("|di=").append(facts.deliveryIndex());
        out.append("|tm=").append(facts.terminal() ? '1' : '0');
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public RoundFacts decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("Redis member is null");
        String text = new String(encoded, StandardCharsets.US_ASCII);
        if ("#".equals(text)) {
            SpinResult loss = LOSSES.independentLoss(new RoundRequest(new BigDecimal("0.5"), 1));
            return facts(loss.symbols(), loss.reelMultiplier());
        }
        if (text.startsWith(PREFIX)) {
            if (text.length() != PREFIX.length() + 4) throw new IllegalArgumentException("invalid gid42 compact member");
            int rpx = Character.digit(text.charAt(PREFIX.length()), 10);
            if (rpx < 0) throw new IllegalArgumentException("invalid gid42 rpx");
            List<String> symbols = List.of(symbol(text.charAt(PREFIX.length() + 1)),
                symbol(text.charAt(PREFIX.length() + 2)), symbol(text.charAt(PREFIX.length() + 3)));
            RoundFacts facts = facts(symbols, rpx);
            RULES.evaluate(new RoundRequest(facts.betSize(), facts.betLevel()), symbols, rpx);
            return facts;
        }
        return decodeLegacyV2(text);
    }

    /** Legacy v2 reader (also used for new lossless writes). */
    private RoundFacts decodeLegacyV2(String text) {
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

    private static RoundFacts facts(List<String> symbols, int rpx) {
        byte[] key = new byte[8];
        RANDOM.nextBytes(key);
        return new RoundFacts(new BigDecimal("0.5"), 1, symbols, rpx,
            "LD42-" + HexFormat.of().formatHex(key), 0, true);
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
