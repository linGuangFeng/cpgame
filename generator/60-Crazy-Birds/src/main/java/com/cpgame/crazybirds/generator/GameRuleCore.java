package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * Crazy Birds 的唯一无状态规则核心。
 *
 * <p>外部传入完整局事实；核心只负责按当前游戏规则恢复、计奖与校验。
 * 权重、随机数、Redis、钱包和选局都不在核心内。</p>
 */
public final class GameRuleCore {
    private final RoundFactory factory = new RoundFactory();
    private final RoundVerifier verifier = new RoundVerifier();

    public RoundResult build(String roundKey, int bl, BigDecimal bs, BigDecimal start,
                             List<List<String>> boards) {
        RoundResult round = factory.restore(roundKey, bs, bl, start, boards);
        verifier.verify(round);
        return round;
    }

    public RoundResult restore(String payload, BigDecimal bs, int bl, BigDecimal start) {
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(factory, verifier);
        return codec.decodeRedisMember(payload, bs, bl, start);
    }
}
