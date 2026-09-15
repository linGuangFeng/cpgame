package com.cpgame.saci.server;

import com.cpgame.saci.generator.GameRuleCore;
import com.cpgame.saci.generator.ResultUtil;
import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundFacts;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 2290 同款拼装：当前能量进 UTIL，普通/免费/漩涡尾部分开取 Redis，
 * 满进度才把漩涡尾部接到这一手后面。
 */
final class RuntimeRoundComposer {
    private final RedisRoundStore store;
    private final GameRuleCore core;

    RuntimeRoundComposer(RedisRoundStore store, GameRuleCore core) {
        this.store = store;
        this.core = core;
    }

    RoundResult composePaid(BigDecimal bs, int bl, BigDecimal startingBalance,
                            ResultUtil.EnergyState energy, RedisRoundStore.Outcome outcome) {
        if (outcome == RedisRoundStore.Outcome.FREE) {
            RoundCandidate free = store.claimCandidate(true, RoundMode.FREE_SPINS);
            return core.restore(new RoundFacts(
                    UUID.randomUUID().toString().replace("-", ""), bl, bs, free), startingBalance);
        }
        RoundCandidate ordinary = store.claimCandidate(false,
                outcome == RedisRoundStore.Outcome.LOSS ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN);
        ResultUtil.EnergyProjection projection = ResultUtil.applyEnergyTransition(energy, ordinary.steps());
        RoundCandidate vortex = projection.vortex()
                ? store.claimCandidate(true, RoundMode.WILD_VORTEX)
                : null;
        return core.compose(ordinary, energy, vortex, bl, bs, startingBalance);
    }
}
