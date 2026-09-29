package com.cpgame.batcha.g8;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Compact complete-Round codec. New writes are direct US-ASCII and never Base64.
 * A verified independent paid loss is stored as {@code #}; legacy JJ8V1 members remain readable.
 */
public final class MemberCodec {
    private static final String ASCII_PREFIX = "JJ8A4|";
    private static final String ASCII_V2_PREFIX = "JJ8A2|";
    private static final String LEGACY_PREFIX = "JJ8V1.";
    private static final int LEGACY_MAGIC = 0x4a4a3801;
    private static final int LEGACY_VERSION = 1;
    private static final SecureRandom LOSS_RANDOM = new SecureRandom();
    private static final CompleteRoundFactory LOSS_FACTORY =
        new CompleteRoundFactory(GameRuleCore.MAX_STEPS_OBSERVED);

    public byte[] encode(CompleteRound round) {
        return encode(round, true);
    }

    /** Keeps the original board for codec/oracle diagnostics. Redis writes use {@link #encode}. */
    public byte[] encodeFull(CompleteRound round) {
        return encode(round, false);
    }

    private byte[] encode(CompleteRound round, boolean compactLoss) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) {
            throw new IllegalArgumentException("only raw gid 8 is supported");
        }
        if (compactLoss && compactableLoss(round)) return new byte[]{'#'};
        StringBuilder value = new StringBuilder(ASCII_PREFIX)
            .append(round.betSize().stripTrailingZeros().toPlainString()).append('|')
            .append(round.betLevel()).append('|');
        for (int index = 0; index < round.steps().size(); index++) {
            if (index > 0) value.append(';');
            Step step = round.steps().get(index);
            value.append(base36(step.spinStatus()))
                .append(base36(step.smallGameType()))
                .append(base36(step.removeStatus()))
                .append(base36(step.extra().size()));
            for (int extraIndex = 0; extraIndex < step.extra().size(); extraIndex++) {
                ExtraCell extra = step.extra().get(extraIndex);
                // Wire coords are row*10+col (0..44). One Base36 digit only covers 0..35, so bottom-row
                // extras (40..44) must use two digits. JJ8A4 writes coord as two Base36 digits.
                value.append(base36(extra.coord() / 36)).append(base36(extra.coord() % 36)).append(code(extra.oldSymbol()));
            }
            for (String symbol : step.symbols()) value.append(code(symbol));
        }
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public CompleteRound decode(byte[] member) {
        if (member == null || member.length == 0) throw new IllegalArgumentException("member is empty");
        String ascii = new String(member, StandardCharsets.US_ASCII);
        if ("#".equals(ascii)) {
            return LOSS_FACTORY.generate(RoundMode.LOSS, LOSS_RANDOM, new BigDecimal("0.05"), 1);
        }
        if (ascii.startsWith(ASCII_PREFIX) || ascii.startsWith("JJ8A3|")) return decodeAscii(ascii);
        if (ascii.startsWith(ASCII_V2_PREFIX)) return decodeAsciiV2(ascii);
        if (ascii.startsWith(LEGACY_PREFIX)) return decodeLegacy(ascii);
        throw new IllegalArgumentException("not a Jurassic Jungle ASCII member");
    }

    private CompleteRound decodeAscii(String value) {
        String[] header = value.split("\\|", 4);
        boolean asciiV4 = header.length == 4 && "JJ8A4".equals(header[0]);
        boolean asciiV3 = header.length == 4 && "JJ8A3".equals(header[0]);
        if (!asciiV4 && !asciiV3) {
            throw new IllegalArgumentException("invalid JJ8A3/JJ8A4 member");
        }
        // JJ8A3 used one Base36 digit per wire coord (broke on bottom-row 40..44).
        // JJ8A4 uses two digits per coord.
        int coordStride = asciiV4 ? 3 : 2;
        try {
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            BigDecimal paidBet = GameRuleCore.paidBet(betSize, betLevel);
            String[] encodedSteps = header[3].split(";", -1);
            if (encodedSteps.length < 1 || encodedSteps.length > 512) {
                throw new IllegalArgumentException("invalid complete-Round Step count");
            }
            List<Step> facts = new ArrayList<>(encodedSteps.length);
            for (int delivery = 0; delivery < encodedSteps.length; delivery++) {
                String encoded = encodedSteps[delivery];
                if (encoded.length() < 4 + GameRuleCore.CELLS) throw new IllegalArgumentException("invalid JJ8A member Step");
                int extraCount = fromBase36(encoded.charAt(3));
                int boardOffset = 4 + extraCount * coordStride;
                if (encoded.length() != boardOffset + GameRuleCore.CELLS) throw new IllegalArgumentException("invalid JJ8A member Step length");
                List<ExtraCell> extras = new ArrayList<>();
                for (int extra = 0; extra < extraCount; extra++) {
                    int coord;
                    if (asciiV4) {
                        coord = fromBase36(encoded.charAt(4 + extra * 3)) * 36
                            + fromBase36(encoded.charAt(5 + extra * 3));
                        extras.add(new ExtraCell(coord, symbol(encoded.charAt(6 + extra * 3))));
                    } else {
                        coord = fromBase36(encoded.charAt(4 + extra * 2));
                        extras.add(new ExtraCell(coord, symbol(encoded.charAt(5 + extra * 2))));
                    }
                }
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < GameRuleCore.CELLS; cell++) board.add(symbol(encoded.charAt(boardOffset + cell)));
                facts.add(Step.fact(delivery, delivery == 0 ? paidBet : BigDecimal.ZERO,
                    betSize, betLevel, board, extras, fromBase36(encoded.charAt(0)),
                    fromBase36(encoded.charAt(1)), fromBase36(encoded.charAt(2))));
            }
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JJ8A number", invalid);
        }
    }


    /** Read-only compatibility for the comma-delimited direct ASCII format. */
    private CompleteRound decodeAsciiV2(String value) {
        String[] header = value.split("\\|", 4);
        if (header.length != 4 || !"JJ8A2".equals(header[0])) throw new IllegalArgumentException("invalid JJ8A2 member");
        try {
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            BigDecimal paidBet = GameRuleCore.paidBet(betSize, betLevel);
            String[] encodedSteps = header[3].split(";", -1);
            List<Step> facts = new ArrayList<>(encodedSteps.length);
            for (int delivery = 0; delivery < encodedSteps.length; delivery++) {
                String[] fields = encodedSteps[delivery].split(",", -1);
                if (fields.length != 5 || fields[4].length() != GameRuleCore.CELLS) throw new IllegalArgumentException("invalid JJ8A2 Step");
                List<ExtraCell> extras = new ArrayList<>();
                if (!fields[3].isEmpty()) for (String encoded : fields[3].split("\\."))
                    extras.add(new ExtraCell(Integer.parseInt(encoded.substring(0, encoded.length() - 1), 36), symbol(encoded.charAt(encoded.length() - 1))));
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < fields[4].length(); cell++) board.add(symbol(fields[4].charAt(cell)));
                facts.add(Step.fact(delivery, delivery == 0 ? paidBet : BigDecimal.ZERO, betSize, betLevel, board,
                    extras, Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
            }
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JJ8A2 number", invalid);
        }
    }

    /** Read-only compatibility for members written before the direct ASCII format. */
    private CompleteRound decodeLegacy(String ascii) {
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(LEGACY_PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid legacy Jurassic Jungle member", invalidBase64);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != LEGACY_MAGIC) throw new IllegalArgumentException("not a legacy Jurassic Jungle member");
            if (in.readUnsignedByte() != LEGACY_VERSION) throw new IllegalArgumentException("unsupported legacy member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 8");
            BigDecimal paidBet = new BigDecimal(in.readUTF());
            BigDecimal betSize = new BigDecimal(in.readUTF());
            int betLevel = in.readUnsignedByte();
            int stepCount = in.readUnsignedShort();
            if (stepCount < 1 || stepCount > 512) throw new IllegalArgumentException("invalid complete-Round Step count");
            List<Step> facts = new ArrayList<>(stepCount);
            for (int delivery = 0; delivery < stepCount; delivery++) {
                BigDecimal betAmount = new BigDecimal(in.readUTF());
                int spinStatus = in.readUnsignedByte();
                int smallGameType = in.readUnsignedByte();
                int removeStatus = in.readUnsignedByte();
                int extraCount = in.readUnsignedByte();
                if (extraCount > GameRuleCore.CELLS) throw new IllegalArgumentException("invalid extra count");
                List<ExtraCell> extra = new ArrayList<>(extraCount);
                for (int i = 0; i < extraCount; i++) {
                    extra.add(new ExtraCell(in.readUnsignedByte(), symbol(in.readUnsignedByte())));
                }
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < GameRuleCore.CELLS; cell++) board.add(symbol(in.readUnsignedByte()));
                facts.add(Step.fact(delivery, betAmount, betSize, betLevel, board, extra,
                    spinStatus, smallGameType, removeStatus));
            }
            if (in.read() != -1) throw new IllegalArgumentException("member has trailing bytes");
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated Jurassic Jungle member", truncated);
        } catch (IOException | NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Jurassic Jungle member", invalid);
        }
    }

    private static boolean compactableLoss(CompleteRound round) {
        if (round.mode() != RoundMode.LOSS || round.payout().signum() != 0 || round.steps().size() != 1) return false;
        Step step = round.steps().getFirst();
        return step.spinStatus() == 1 && step.smallGameType() == 0 && step.removeStatus() == 0
            && step.extra().isEmpty() && step.winAmount().signum() == 0;
    }

    private static char code(String symbol) {
        return (char) ('0' + symbolId(symbol));
    }

    private static char base36(int value) {
        char result = Character.forDigit(value, 36);
        if (result == 0) throw new IllegalArgumentException("value outside single Base36 digit: " + value);
        return Character.toUpperCase(result);
    }

    private static int fromBase36(char value) {
        int result = Character.digit(value, 36);
        if (result < 0) throw new IllegalArgumentException("invalid Base36 digit");
        return result;
    }

    private static int symbolId(String symbol) {
        if (symbol == null || symbol.length() != 2 || symbol.charAt(0) != 'S') {
            throw new IllegalArgumentException("unknown symbol: " + symbol);
        }
        int id = symbol.charAt(1) - '0';
        if (id < 1 || id > 9) throw new IllegalArgumentException("unknown symbol: " + symbol);
        return id;
    }

    private static String symbol(char code) {
        return symbol(code - '0');
    }

    private static String symbol(int id) {
        if (id < 1 || id > 9) throw new IllegalArgumentException("unknown symbol id in member");
        return "S" + id;
    }
}
