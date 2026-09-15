package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundFacts;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.StepFact;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MinimalRoundFactCodec {
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory roundFactory, RoundVerifier verifier) {
        this.roundFactory = roundFactory;
        this.verifier = verifier;
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        return KernelCodec.PREFIX + ";" + GameRules.RULES_HASH + ";"
                + KernelCodec.encodeSteps(round.candidate().steps());
    }

    public List<StepFact> decodeSteps(String payload) {
        if (payload == null || payload.startsWith("{") || payload.startsWith("[")) {
            throw new IllegalArgumentException("member 禁止 JSON");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("member 必须是 US-ASCII");
        }
        String[] fields = payload.split(";", -1);
        if (fields.length != 3 || !KernelCodec.PREFIX.equals(fields[0])) {
            throw new IllegalArgumentException("member 格式错误");
        }
        if (!GameRules.RULES_HASH.equals(fields[1])) throw new IllegalArgumentException("rulesHash 不匹配");
        return KernelCodec.decodeSteps(fields[2]);
    }

    public RoundResult decodeRedisMember(String payload, String roundKey, BigDecimal bs, int bl,
                                         BigDecimal startingBalance) {
        try {
            List<StepFact> steps = decodeSteps(payload);
            RoundCandidate candidate = new RoundCandidate(infer(steps), steps);
            RoundResult restored = roundFactory.restore(new RoundFacts(roundKey, bl, bs, candidate), startingBalance);
            verifier.verify(restored);
            return restored;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }

    public RoundResult decodeRedisMember(String payload, BigDecimal bs, int bl, BigDecimal startingBalance) {
        return decodeRedisMember(payload, Long.toUnsignedString(payload.hashCode() & 0xffffffffL, 16),
                bs, bl, startingBalance);
    }

    public static RoundMode infer(List<StepFact> steps) {
        boolean anyWin = false;
        for (StepFact step : steps) {
            if (step.fsn() > 0) return RoundMode.FREE_SPINS;
            if (step.rsn() > 0 || step.gm() == 3) return RoundMode.WILD_VORTEX;
            if (!ResultUtil.expectedWmkl(step.rskl()).isEmpty()) anyWin = true;
        }
        return anyWin ? RoundMode.ORDINARY_WIN : RoundMode.ORDINARY_LOSS;
    }
}
