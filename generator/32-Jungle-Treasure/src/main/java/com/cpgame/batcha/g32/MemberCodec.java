package com.cpgame.batcha.g32;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemberCodec {
    private static final String ASCII_PREFIX = "JT32V1.";
    private static final int MAGIC = 0x4a543332;
    private static final int VERSION = 1;
    private static final List<String> SYMBOLS = List.of(
        "A", "H1", "H2", "H3", "H4", "H5", "H6", "J", "K", "Q", "T", "Scat", "Wild");
    private static final Map<String, Integer> SYMBOL_IDS = symbolIds();

    public byte[] encode(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 32");
        byte[] binary = encodeBinary(round);
        return (ASCII_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(binary))
            .getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] encodeBinary(CompleteRound round) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeByte(GameRuleCore.RAW_GAME_ID);
                out.writeUTF(round.paidBet().toPlainString());
                out.writeUTF(round.betSize().toPlainString());
                out.writeByte(round.betLevel());
                out.writeShort(round.steps().size());
                for (Step step : round.steps()) {
                    out.writeUTF(step.betAmount().toPlainString());
                    out.writeShort(step.tokens().size());
                    for (String token : step.tokens()) {
                        int height = token.charAt(0) - '0';
                        Integer id = SYMBOL_IDS.get(token.substring(1));
                        if (id == null) throw new IllegalArgumentException("unknown symbol: " + token);
                        out.writeByte(height);
                        out.writeByte(id);
                    }
                    writeCoords(out, step.silver());
                    writeCoords(out, step.gold());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode in-memory member", impossible);
        }
    }

    public CompleteRound decode(byte[] member) {
        if (member == null || member.length == 0) throw new IllegalArgumentException("member is empty");
        String ascii = new String(member, StandardCharsets.US_ASCII);
        if (!ascii.startsWith(ASCII_PREFIX)) throw new IllegalArgumentException("not an ASCII Jungle Treasure member");
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(ASCII_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid Jungle Treasure ASCII member", invalid);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a Jungle Treasure member");
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("unsupported member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 32");
            BigDecimal paidBet = new BigDecimal(in.readUTF());
            BigDecimal betSize = new BigDecimal(in.readUTF());
            int betLevel = in.readUnsignedByte();
            GameRuleCore.validateBet(betSize, betLevel);
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
                    if (id >= SYMBOLS.size() || height < 1 || height > 4) {
                        throw new IllegalArgumentException("unknown token in member");
                    }
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

    private static void writeCoords(DataOutputStream out, List<Integer> coords) throws IOException {
        out.writeByte(coords.size());
        for (int coord : coords) out.writeByte(coord);
    }

    private static List<Integer> readCoords(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        List<Integer> coords = new ArrayList<>(n);
        for (int i = 0; i < n; i++) coords.add(in.readUnsignedByte());
        return coords;
    }

    private static Map<String, Integer> symbolIds() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < SYMBOLS.size(); i++) result.put(SYMBOLS.get(i), i);
        return Map.copyOf(result);
    }
}
