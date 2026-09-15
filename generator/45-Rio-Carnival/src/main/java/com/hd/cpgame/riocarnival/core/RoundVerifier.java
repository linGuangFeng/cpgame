package com.hd.cpgame.riocarnival.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 与随机候选和 RoundFactory 分离的最终验收器。 */
public final class RoundVerifier {
    private RoundVerifier() {}

    public static RoundResult verify(GeneratedRound round) {
        if (round == null) throw new IllegalArgumentException("Round 不能为空");
        if (round.roundKey == null || round.roundKey.trim().isEmpty())
            throw new IllegalArgumentException("Round 缺少唯一标识");
        if (round.createdAt <= 0) throw new IllegalArgumentException("Round 创建时间无效");
        return ResultUtil.infer(round);
    }

    public static void verifyUnique(List<GeneratedRound> rounds) {
        Set<String> keys = new HashSet<String>();
        Set<String> facts = new HashSet<String>();
        for (GeneratedRound round : rounds) {
            verify(round);
            if (!keys.add(round.roundKey)) throw new IllegalArgumentException("完整局标识重复");
            StringBuilder fingerprint = new StringBuilder();
            fingerprint.append(round.betSize).append('|').append(round.betLevel);
            for (SpinStep step : round.steps) fingerprint.append('|').append(step.rskl);
            if (!facts.add(fingerprint.toString())) throw new IllegalArgumentException("完整局随机事实重复");
        }
    }
}
