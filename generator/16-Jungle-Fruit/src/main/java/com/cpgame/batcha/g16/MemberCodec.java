package com.cpgame.batcha.g16;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct compact ASCII complete-Round codec with a {@code #} independent-loss marker. */
public final class MemberCodec {
    private static final String ASCII_PREFIX = "JF16A3|";
    private static final String ASCII_V2_PREFIX = "JF16A2|";
    private static final String LEGACY_PREFIX = "JF16V1.";
    private static final int LEGACY_MAGIC = 0x4a463136;
    private static final int LEGACY_VERSION = 1;
    private static final String CODES = "0123456789ABCDEFG";
    private static final List<String> SYMBOLS = List.of(
        "A", "H1", "H2", "H3", "H4", "H5", "J", "K", "Q", "T", "Scat",
        "X2", "X3", "X4", "X5", "X7", "X15");
    private static final Map<String, Integer> SYMBOL_IDS = symbolIds();
    private static final SecureRandom LOSS_RANDOM = new SecureRandom();
    private static final CompleteRoundFactory LOSS_FACTORY = new CompleteRoundFactory(18, 25);

    public byte[] encode(CompleteRound round) {
        return encode(round, true);
    }

    public byte[] encodeFull(CompleteRound round) {
        return encode(round, false);
    }

    private byte[] encode(CompleteRound round, boolean compactLoss) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) {
            throw new IllegalArgumentException("only raw gid 16 is supported");
        }
        if (compactLoss && compactableLoss(round)) return new byte[]{'#'};
        StringBuilder value = new StringBuilder(ASCII_PREFIX)
            .append(round.betSize().stripTrailingZeros().toPlainString()).append('|')
            .append(round.betLevel()).append('|');
        for (int index = 0; index < round.steps().size(); index++) {
            if (index > 0) value.append(';');
            Step step = round.steps().get(index);
            value.append(base36(step.spinStatus())).append(base36(step.freeSpinNum()))
                .append(base36(step.nowFreeSpinCount())).append(base36(step.smallGameType()));
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
        if (ascii.startsWith(ASCII_PREFIX)) return decodeAscii(ascii);
        if (ascii.startsWith(ASCII_V2_PREFIX)) return decodeAsciiV2(ascii);
        if (ascii.startsWith(LEGACY_PREFIX)) return decodeLegacy(ascii);
        throw new IllegalArgumentException("not a Jungle Fruit ASCII member");
    }

    private CompleteRound decodeAscii(String value) {
        String[] header = value.split("\\|", 4);
        if (header.length != 4 || !"JF16A3".equals(header[0])) throw new IllegalArgumentException("invalid JF16A3 member");
        try {
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            BigDecimal paidBet = betSize.multiply(BigDecimal.valueOf(20L * betLevel)).stripTrailingZeros();
            String[] encodedSteps = header[3].split(";", -1);
            if (encodedSteps.length < 1 || encodedSteps.length > 512) throw new IllegalArgumentException("invalid Step count");
            List<Step> facts = new ArrayList<>(encodedSteps.length);
            for (int delivery = 0; delivery < encodedSteps.length; delivery++) {
                String encoded = encodedSteps[delivery];
                if (encoded.length() != 4 + GameRuleCore.CELLS) throw new IllegalArgumentException("invalid JF16A3 Step");
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < GameRuleCore.CELLS; cell++) board.add(symbol(encoded.charAt(4 + cell)));
                facts.add(Step.fact(delivery, delivery == 0 ? paidBet : BigDecimal.ZERO,
                    betSize, betLevel, board, fromBase36(encoded.charAt(0)), fromBase36(encoded.charAt(1)),
                    fromBase36(encoded.charAt(2)), fromBase36(encoded.charAt(3))));
            }
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JF16A2 number", invalid);
        }
    }

    /** Read-only compatibility for the comma-delimited direct ASCII format. */
    private CompleteRound decodeAsciiV2(String value) {
        String[] header = value.split("\\|", 4);
        if (header.length != 4 || !"JF16A2".equals(header[0])) throw new IllegalArgumentException("invalid JF16A2 member");
        try {
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            BigDecimal paidBet = betSize.multiply(BigDecimal.valueOf(20L * betLevel)).stripTrailingZeros();
            String[] encodedSteps = header[3].split(";", -1);
            List<Step> facts = new ArrayList<>(encodedSteps.length);
            for (int delivery = 0; delivery < encodedSteps.length; delivery++) {
                String[] fields = encodedSteps[delivery].split(",", -1);
                if (fields.length != 5 || fields[4].length() != GameRuleCore.CELLS) throw new IllegalArgumentException("invalid JF16A2 Step");
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < fields[4].length(); cell++) board.add(symbol(fields[4].charAt(cell)));
                facts.add(Step.fact(delivery, delivery == 0 ? paidBet : BigDecimal.ZERO, betSize, betLevel, board,
                    Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
            }
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JF16A2 number", invalid);
        }
    }

    /** Read-only compatibility for pre-A2 Base64 members. */
    private CompleteRound decodeLegacy(String ascii) {
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(LEGACY_PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid legacy Jungle Fruit member", invalidBase64);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != LEGACY_MAGIC) throw new IllegalArgumentException("not a legacy Jungle Fruit member");
            if (in.readUnsignedByte() != LEGACY_VERSION) throw new IllegalArgumentException("unsupported legacy member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 16");
            BigDecimal paidBet = new BigDecimal(in.readUTF());
            BigDecimal betSize = new BigDecimal(in.readUTF());
            int betLevel = in.readUnsignedByte();
            int stepCount = in.readUnsignedShort();
            if (stepCount < 1 || stepCount > 512) throw new IllegalArgumentException("invalid complete-Round Step count");
            List<Step> facts = new ArrayList<>(stepCount);
            for (int delivery = 0; delivery < stepCount; delivery++) {
                BigDecimal betAmount = new BigDecimal(in.readUTF());
                int spinStatus = in.readUnsignedByte();
                int freeSpinNum = in.readUnsignedShort();
                int nowFreeSpinCount = in.readUnsignedShort();
                int smallGameType = in.readUnsignedByte();
                List<String> board = new ArrayList<>(GameRuleCore.CELLS);
                for (int cell = 0; cell < GameRuleCore.CELLS; cell++) board.add(symbol(in.readUnsignedByte()));
                facts.add(Step.fact(delivery, betAmount, betSize, betLevel, board,
                    spinStatus, freeSpinNum, nowFreeSpinCount, smallGameType));
            }
            if (in.read() != -1) throw new IllegalArgumentException("member has trailing bytes");
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated Jungle Fruit member", truncated);
        } catch (IOException | NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Jungle Fruit member", invalid);
        }
    }

    private static boolean compactableLoss(CompleteRound round) {
        if (round.mode() != RoundMode.LOSS || round.payout().signum() != 0 || round.steps().size() != 1) return false;
        Step step = round.steps().getFirst();
        return step.spinStatus() == 1 && step.smallGameType() == 0 && step.freeSpinNum() == 0
            && step.nowFreeSpinCount() == 0 && step.winAmount().signum() == 0
            && step.symbols().stream().filter("Scat"::equals).count() < 3;
    }

    private static char code(String symbol) {
        Integer id = SYMBOL_IDS.get(symbol);
        if (id == null) throw new IllegalArgumentException("unknown symbol: " + symbol);
        return CODES.charAt(id);
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

    private static String symbol(char code) {
        int id = CODES.indexOf(code);
        if (id < 0 || id >= SYMBOLS.size()) throw new IllegalArgumentException("unknown symbol code in member");
        return SYMBOLS.get(id);
    }

    private static String symbol(int id) {
        if (id < 0 || id >= SYMBOLS.size()) throw new IllegalArgumentException("unknown symbol id in member");
        return SYMBOLS.get(id);
    }

    private static Map<String, Integer> symbolIds() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < SYMBOLS.size(); i++) result.put(SYMBOLS.get(i), i);
        return Map.copyOf(result);
    }
}
