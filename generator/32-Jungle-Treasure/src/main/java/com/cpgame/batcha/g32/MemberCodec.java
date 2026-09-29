package com.cpgame.batcha.g32;

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

/** Direct ASCII facts: two characters per height+symbol token, fixed-width Base36 coordinates. */
public final class MemberCodec {
    private static final String ASCII_PREFIX = "JT32A2|";
    private static final String LEGACY_PREFIX = "JT32V1.";
    private static final int LEGACY_MAGIC = 0x4a543332;
    private static final int LEGACY_VERSION = 1;
    private static final String CODES = "0123456789ABC";
    private static final List<String> SYMBOLS = List.of(
        "A", "H1", "H2", "H3", "H4", "H5", "H6", "J", "K", "Q", "T", "Scat", "Wild");
    private static final Map<String, Integer> SYMBOL_IDS = symbolIds();
    private static final SecureRandom LOSS_RANDOM = new SecureRandom();
    private static final CompleteRoundFactory LOSS_FACTORY = new CompleteRoundFactory(12, 30);

    public byte[] encode(CompleteRound round) { return encode(round, true); }
    public byte[] encodeFull(CompleteRound round) { return encode(round, false); }

    private byte[] encode(CompleteRound round, boolean compactLoss) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 32");
        if (compactLoss && compactableLoss(round)) return new byte[]{'#'};
        StringBuilder out = new StringBuilder(ASCII_PREFIX)
            .append(round.betSize().stripTrailingZeros().toPlainString()).append('|')
            .append(round.betLevel()).append('|');
        for (int stepIndex = 0; stepIndex < round.steps().size(); stepIndex++) {
            if (stepIndex > 0) out.append(';');
            Step step = round.steps().get(stepIndex);
            for (String token : step.tokens()) {
                int height = token.charAt(0) - '0';
                if (height < 1 || height > 4) throw new IllegalArgumentException("invalid token height");
                out.append((char) ('0' + height)).append(code(token.substring(1)));
            }
            out.append('~'); appendCoords(out, step.silver());
            out.append('~'); appendCoords(out, step.gold());
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public CompleteRound decode(byte[] member) {
        if (member == null || member.length == 0) throw new IllegalArgumentException("member is empty");
        String ascii = new String(member, StandardCharsets.US_ASCII);
        if ("#".equals(ascii)) {
            return LOSS_FACTORY.generate(RoundMode.LOSS, LOSS_RANDOM, new BigDecimal("0.02"), 1);
        }
        if (ascii.startsWith(ASCII_PREFIX)) return decodeAscii(ascii);
        if (ascii.startsWith(LEGACY_PREFIX)) return decodeLegacy(ascii);
        throw new IllegalArgumentException("not a Jungle Treasure ASCII member");
    }

    private CompleteRound decodeAscii(String value) {
        String[] header = value.split("\\|", 4);
        if (header.length != 4 || !"JT32A2".equals(header[0])) throw new IllegalArgumentException("invalid JT32A2 member");
        try {
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            BigDecimal paidBet = GameRuleCore.paidBet(betSize, betLevel);
            String[] encodedSteps = header[3].split(";", -1);
            if (encodedSteps.length < 1 || encodedSteps.length > 512) throw new IllegalArgumentException("invalid Step count");
            List<Step> facts = new ArrayList<>(encodedSteps.length);
            for (int delivery = 0; delivery < encodedSteps.length; delivery++) {
                String[] fields = encodedSteps[delivery].split("~", -1);
                if (fields.length != 3 || fields[0].isEmpty() || (fields[0].length() & 1) != 0) {
                    throw new IllegalArgumentException("invalid JT32A2 Step");
                }
                List<String> tokens = new ArrayList<>(fields[0].length() / 2);
                for (int offset = 0; offset < fields[0].length(); offset += 2) {
                    int height = fields[0].charAt(offset) - '0';
                    if (height < 1 || height > 4) throw new IllegalArgumentException("invalid token height");
                    tokens.add(height + symbol(fields[0].charAt(offset + 1)));
                }
                facts.add(Step.fact(delivery, delivery == 0 ? paidBet : BigDecimal.ZERO,
                    betSize, betLevel, tokens, decodeCoords(fields[1]), decodeCoords(fields[2])));
            }
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JT32A2 number", invalid);
        }
    }

    /** Read-only compatibility for pre-A2 Base64 members. */
    private CompleteRound decodeLegacy(String ascii) {
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(LEGACY_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid legacy Jungle Treasure member", invalid);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != LEGACY_MAGIC) throw new IllegalArgumentException("not a legacy Jungle Treasure member");
            if (in.readUnsignedByte() != LEGACY_VERSION) throw new IllegalArgumentException("unsupported legacy member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 32");
            BigDecimal paidBet = new BigDecimal(in.readUTF());
            BigDecimal betSize = new BigDecimal(in.readUTF());
            int betLevel = in.readUnsignedByte();
            int stepCount = in.readUnsignedShort();
            if (stepCount < 1 || stepCount > 512) throw new IllegalArgumentException("invalid Step count");
            List<Step> facts = new ArrayList<>(stepCount);
            for (int delivery = 0; delivery < stepCount; delivery++) {
                BigDecimal betAmount = new BigDecimal(in.readUTF());
                int tokenCount = in.readUnsignedShort();
                List<String> tokens = new ArrayList<>(tokenCount);
                for (int i = 0; i < tokenCount; i++) {
                    int height = in.readUnsignedByte();
                    int id = in.readUnsignedByte();
                    if (id >= SYMBOLS.size() || height < 1 || height > 4) throw new IllegalArgumentException("unknown token in member");
                    tokens.add(height + SYMBOLS.get(id));
                }
                List<Integer> silver = readCoords(in);
                List<Integer> gold = readCoords(in);
                facts.add(new Step(delivery, betAmount, betSize, betLevel, tokens, silver, gold,
                    1, 0, 0, 0, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
            }
            if (in.read() != -1) throw new IllegalArgumentException("member has trailing bytes");
            return GameRuleCore.materialize(paidBet, betSize, betLevel, facts);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated Jungle Treasure member", truncated);
        } catch (IOException | NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Jungle Treasure member", invalid);
        }
    }

    private static boolean compactableLoss(CompleteRound round) {
        if (round.mode() != RoundMode.LOSS || round.payout().signum() != 0 || round.steps().size() != 1) return false;
        Step step = round.steps().getFirst();
        return step.spinStatus() == 1 && step.freeSpinNum() == 0 && step.nowFreeSpinCount() == 0
            && step.winAmount().signum() == 0 && step.silver().isEmpty() && step.gold().isEmpty()
            && GameRuleCore.scatterCount(GameRuleCore.parse(step.tokens())) < 4;
    }

    private static void appendCoords(StringBuilder out, List<Integer> coords) {
        for (int coord : coords) {
            if (coord < 0 || coord >= 36 * 36) throw new IllegalArgumentException("coordinate out of range");
            out.append(Character.forDigit(coord / 36, 36)).append(Character.forDigit(coord % 36, 36));
        }
    }

    private static List<Integer> decodeCoords(String encoded) {
        if ((encoded.length() & 1) != 0) throw new IllegalArgumentException("invalid coordinate encoding");
        List<Integer> result = new ArrayList<>(encoded.length() / 2);
        for (int offset = 0; offset < encoded.length(); offset += 2) {
            int high = Character.digit(encoded.charAt(offset), 36);
            int low = Character.digit(encoded.charAt(offset + 1), 36);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid coordinate encoding");
            result.add(high * 36 + low);
        }
        return List.copyOf(result);
    }

    private static List<Integer> readCoords(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        List<Integer> coords = new ArrayList<>(n);
        for (int i = 0; i < n; i++) coords.add(in.readUnsignedByte());
        return coords;
    }

    private static char code(String symbol) {
        Integer id = SYMBOL_IDS.get(symbol);
        if (id == null) throw new IllegalArgumentException("unknown symbol: " + symbol);
        return CODES.charAt(id);
    }

    private static String symbol(char code) {
        int id = CODES.indexOf(code);
        if (id < 0 || id >= SYMBOLS.size()) throw new IllegalArgumentException("unknown symbol code");
        return SYMBOLS.get(id);
    }

    private static Map<String, Integer> symbolIds() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < SYMBOLS.size(); i++) result.put(SYMBOLS.get(i), i);
        return Map.copyOf(result);
    }
}
