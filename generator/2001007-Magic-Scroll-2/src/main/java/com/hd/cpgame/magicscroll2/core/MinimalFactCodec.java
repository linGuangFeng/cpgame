package com.hd.cpgame.magicscroll2.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal compact fact codec. It stores only ordered raw formations and active-row facts.
 * The platform Redis member envelope is implemented separately by RedisRoundCodec.
 */
public final class MinimalFactCodec {
    private static final int VERSION = 2;
    private final FormationCodec formationCodec = new FormationCodec();
    private final ResultUtil resultUtil;
    private final RoundVerifier verifier;

    public MinimalFactCodec() { this(new ResultUtil(), GenerationPolicy.defaults()); }

    MinimalFactCodec(ResultUtil resultUtil, GenerationPolicy policy) {
        this.resultUtil = resultUtil;
        this.verifier = new RoundVerifier(resultUtil, policy);
    }

    public byte[] encode(GeneratedRound round) {
        verifier.verify(round);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            output.writeUTF(round.getSchemaVersion());
            output.writeUTF(round.getRulesVersion());
            output.writeUTF(round.getRulesHash());
            output.writeUTF(round.getRoundKey());
            output.writeLong(round.getDeterministicSeed());
            output.writeShort(round.getSteps().size());
            for (RoundStep step : round.getSteps()) {
                output.writeByte(step.getActiveRows());
                int[] raw = formationCodec.decode(step.getFormation());
                for (int cell : raw) output.writeInt(cell);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode minimal Round facts", e);
        }
    }

    public GeneratedRound decode(byte[] encoded, BigDecimal paidBet) {
        RoundFactory.requirePaidBet(paidBet);
        if (encoded == null) throw new IllegalArgumentException("encoded facts are required");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readUnsignedByte() != VERSION) throw new IllegalArgumentException("unsupported fact codec version");
            String schemaVersion = input.readUTF();
            String rulesVersion = input.readUTF();
            String rulesHash = input.readUTF();
            String roundKey = input.readUTF();
            long deterministicSeed = input.readLong();
            int count = input.readUnsignedShort();
            if (count < 1 || count > GenerationPolicy.DEFAULT_MAX_ROUND_STEPS) {
                throw new IllegalArgumentException("encoded Delivery count is invalid");
            }
            BigDecimal baseBet = RoundFactory.baseBet(paidBet);
            BigDecimal cumulative = RoundFactory.money(BigDecimal.ZERO);
            int multiplier = 1;
            List<RoundStep> steps = new ArrayList<RoundStep>();
            ResultUtil.Inspection first = null;
            for (int index = 0; index < count; index++) {
                int activeRows = input.readUnsignedByte();
                int[] raw = new int[GameConstants.CELL_COUNT];
                for (int cell = 0; cell < raw.length; cell++) raw[cell] = input.readInt();
                String formation = formationCodec.encode(raw);
                ResultUtil.Inspection inspection = resultUtil.inspect(formation, activeRows, baseBet, multiplier);
                if (first == null) first = inspection;
                cumulative = RoundFactory.money(cumulative.add(inspection.getWin()));
                boolean terminal = resultUtil.isTerminalBaseStep(inspection);
                steps.add(new RoundStep(formation, activeRows, multiplier, inspection.getWin(), cumulative, terminal));
                multiplier += inspection.getWildCountValue();
            }
            if (input.read() != -1) throw new IllegalArgumentException("trailing bytes in minimal facts");
            RoundMode mode = verifier.inferMode(first, steps);
            GeneratedRound round = new GeneratedRound(schemaVersion, rulesVersion, rulesHash,
                    deterministicSeed, roundKey, mode, paidBet, cumulative, steps);
            verifier.verify(round);
            return round;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed minimal Round facts", e);
        }
    }
}
