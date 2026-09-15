package com.cpgame.batcha.g8;

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
import java.util.List;

/**
 * Minimal complete-Round codec. Stores bets, Step state, extra facts and 25-symbol boards.
 * Matches, wins, remove_num, mode, payout and multiplier are recomputed by GameRuleCore.
 */
public final class MemberCodec {
    private static final String ASCII_PREFIX = "JJ8V1.";
    private static final int MAGIC = 0x4a4a3801; // JJ8\x01
    private static final int VERSION = 1;

    public byte[] encode(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 8 is supported");
        byte[] binary = encodeBinary(round);
        return (ASCII_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(binary))
            .getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] encodeBinary(CompleteRound round) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128 + round.steps().size() * 40);
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
                    out.writeByte(step.smallGameType());
                    out.writeByte(step.removeStatus());
                    out.writeByte(step.extra().size());
                    for (ExtraCell extra : step.extra()) {
                        out.writeByte(extra.coord());
                        out.writeByte(symbolId(extra.oldSymbol()));
                    }
                    for (String symbol : step.symbols()) out.writeByte(symbolId(symbol));
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
        if (!ascii.startsWith(ASCII_PREFIX)) throw new IllegalArgumentException("not an ASCII Jurassic Jungle member");
        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(ascii.substring(ASCII_PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid Jurassic Jungle ASCII member", invalidBase64);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a Jurassic Jungle member");
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("unsupported member version");
            if (in.readUnsignedByte() != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("member raw gid is not 8");
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

    private static int symbolId(String symbol) {
        if (symbol == null || symbol.length() != 2 || symbol.charAt(0) != 'S') {
            throw new IllegalArgumentException("unknown symbol: " + symbol);
        }
        int id = symbol.charAt(1) - '0';
        if (id < 1 || id > 9) throw new IllegalArgumentException("unknown symbol: " + symbol);
        return id;
    }

    private static String symbol(int id) {
        if (id < 1 || id > 9) throw new IllegalArgumentException("unknown symbol id in member");
        return "S" + id;
    }
}
