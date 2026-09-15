package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One US-ASCII Redis member is one complete paid Round: prefix;rulesHash;seed;rows:formation|... */
public final class RedisMemberCodec {
    private final ResultUtil resultUtil;
    private final RoundVerifier verifier;
    private final GenerationPolicy policy;

    public RedisMemberCodec() {
        this(new ResultUtil(), GenerationPolicy.defaults());
    }

    public RedisMemberCodec(ResultUtil resultUtil, GenerationPolicy policy) {
        if (resultUtil == null || policy == null) {
            throw new IllegalArgumentException("ResultUtil and policy are required");
        }
        this.resultUtil = resultUtil;
        this.policy = policy;
        this.verifier = new RoundVerifier(resultUtil, policy);
    }

    public String encode(GeneratedRound round) {
        verifier.verify(round);
        StringBuilder out = new StringBuilder();
        out.append(GameConstants.REDIS_MEMBER_PREFIX).append(';')
                .append(GameConstants.RULES_HASH).append(';')
                .append(round.getDeterministicSeed()).append(';');
        List<RoundStep> steps = round.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) out.append('|');
            RoundStep step = steps.get(i);
            out.append(step.getActiveRows()).append(':').append(step.getFormation());
        }
        String payload = out.toString();
        if (payload.startsWith("{") || payload.startsWith("[")) {
            throw new IllegalStateException("ASCII member must not be JSON");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalStateException("ASCII member must be US-ASCII");
        }
        return payload;
    }

    public GeneratedRound decode(String payload, BigDecimal paidBet) {
        RoundFactory.requirePaidBet(paidBet);
        if (payload == null || payload.isEmpty()) throw new IllegalArgumentException("member is required");
        if (payload.startsWith("{") || payload.startsWith("[")) {
            throw new IllegalArgumentException("member 禁止 JSON");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("member 必须是 US-ASCII");
        }
        String[] fields = payload.split(";", -1);
        if (fields.length != 4 || !GameConstants.REDIS_MEMBER_PREFIX.equals(fields[0])) {
            throw new IllegalArgumentException("member 格式错误");
        }
        if (!GameConstants.RULES_HASH.equals(fields[1])) {
            throw new IllegalArgumentException("rulesHash 不匹配");
        }
        long seed = Long.parseLong(fields[2]);
        String[] rawSteps = fields[3].split("\\|", -1);
        if (rawSteps.length < 1 || rawSteps.length > GenerationPolicy.DEFAULT_MAX_ROUND_STEPS) {
            throw new IllegalArgumentException("encoded Delivery count is invalid");
        }
        BigDecimal baseBet = RoundFactory.baseBet(paidBet);
        BigDecimal cumulative = RoundFactory.money(BigDecimal.ZERO);
        int multiplier = 1;
        List<RoundStep> steps = new ArrayList<RoundStep>();
        ResultUtil.Inspection first = null;
        for (int index = 0; index < rawSteps.length; index++) {
            int colon = rawSteps[index].indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("step encoding is invalid");
            int activeRows = Integer.parseInt(rawSteps[index].substring(0, colon));
            String formation = rawSteps[index].substring(colon + 1);
            ResultUtil.Inspection inspection = resultUtil.inspect(formation, activeRows, baseBet, multiplier);
            if (first == null) first = inspection;
            cumulative = RoundFactory.money(cumulative.add(inspection.getWin()));
            boolean terminal = resultUtil.isTerminalBaseStep(inspection);
            steps.add(new RoundStep(formation, activeRows, multiplier, inspection.getWin(), cumulative, terminal));
            multiplier += inspection.getWildCountValue();
        }
        RoundMode mode = verifier.inferMode(first, steps);
        String roundKey = RoundFactory.roundKeyFor(seed, mode, paidBet);
        GeneratedRound round = new GeneratedRound(GameConstants.ROUND_SCHEMA_VERSION,
                GameConstants.RULES_VERSION, GameConstants.RULES_HASH, seed, roundKey, mode,
                paidBet, cumulative, steps);
        verifier.verify(round);
        return round;
    }
}
