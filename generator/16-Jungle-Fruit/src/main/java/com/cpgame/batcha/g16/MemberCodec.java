package com.cpgame.batcha.g16;

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

/**
 * Minimal complete-Round codec. It stores only bets, Step state and 36-symbol boards; matches,
 * wins, mode, payout and multiplier are recomputed by the single GameRuleCore on decode.
 */
public final class MemberCodec {
    private static final String ASCII_PREFIX = "JF16V1.";
    private static final int MAGIC = 0x4a463136; // JF16
    private static final int VERSION = 1;
    private static final List<String> SYMBOLS = List.of(
        "A", "H1", "H2", "H3", "H4", "H5", "J", "K", "Q", "T", "Scat",
        "X2", "X3", "X4", "X5", "X7", "X15");
    private static final Map<String, Integer> SYMBOL_IDS = symbolIds();

    public byte[] encode(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 16 is supported");
        byte[] binary = encodeBinary(round);
        return (ASCII_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(binary))
            .getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] encodeBinary(CompleteRound round) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + round.steps().size() * 48);
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
                    out.writeByte(step.spinStatus());
                    out.writeShort(step.freeSpinNum());
                    out.writeShort(step.nowFreeSpinCount());
                    out.writeByte(step.smallGameType());
                    for (String symbol : step.symbols()) {
                        Integer id = SYMBOL_IDS.get(symbol);
                        if (id == null) throw new IllegalArgumentException("unknown symbol: " + symbol);
                        out.writeByte(id);
                    }
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
        if (!ascii.startsWith(ASCII_PREFIX)) throw new IllegalArgumentException("not an ASCII Jungle Fruit member");
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(ASCII_PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid Jungle Fruit ASCII member", invalidBase64);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a Jungle Fruit member");
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("unsupported member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 16");
            BigDecimal paidBet = new BigDecimal(in.readUTF());
            BigDecimal betSize = new BigDecimal(in.readUTF());
            int betLevel = in.readUnsignedByte();
            GameRuleCore.validateBet(betSize, betLevel);
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
                for (int cell = 0; cell < GameRuleCore.CELLS; cell++) {
                    int symbolId = in.readUnsignedByte();
                    if (symbolId >= SYMBOLS.size()) throw new IllegalArgumentException("unknown symbol id in member");
                    board.add(SYMBOLS.get(symbolId));
                }
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

    private static Map<String, Integer> symbolIds() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < SYMBOLS.size(); i++) result.put(SYMBOLS.get(i), i);
        return Map.copyOf(result);
    }
}
