package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** server-api、试玩和正式Loader共同依赖的唯一规则门面。 */
public final class GameRuleCore {
    private final RoundFactory roundFactory;

    /** 正式入口只使用系统安全随机源，不接受seed。 */
    public GameRuleCore() {
        this(new SecureRandom(), GenerationLimits.defaults());
    }

    /** 仅供显式测试注入可复现随机源；正式Loader与试玩不调用此构造器。 */
    public GameRuleCore(RandomGenerator random) {
        this(random, GenerationLimits.defaults());
    }

    public GameRuleCore(RandomGenerator random, GenerationLimits limits) {
        this.roundFactory = new RoundFactory(Objects.requireNonNull(random), Objects.requireNonNull(limits));
    }

    public int gameId() { return GAME_ID; }
    public String gameName() { return GAME_NAME; }
    public String rulesHash() { return RULES_HASH; }

    public CyberGoModels.CompleteRound generateCompleteRound() {
        return roundFactory.createRandomCompleteRound();
    }

    public CyberGoModels.CompleteRound generateOrdinaryLoss() {
        return roundFactory.createOrdinaryLoss();
    }

    public CyberGoModels.CompleteRound generateOrdinaryWin() {
        return roundFactory.createOrdinaryWin();
    }

    public CyberGoModels.CompleteRound generateFreeSpinRound() {
        return roundFactory.createFreeSpinRound();
    }

    public CyberGoModels.CompleteRound rebuild(CyberGoModels.MinimalRoundFacts facts) {
        return roundFactory.rebuild(facts);
    }
    /** Apply a configured positive bet to an already claimed complete-round member. No new deal. */
    public CyberGoModels.CompleteRound atBet(CyberGoModels.CompleteRound round, int level, java.math.BigDecimal size) {
        var factor=ResultUtil.unitBet(size, level).divide(MINIMUM_BET_SIZE);
        var steps=round.deliveries().stream().map(s -> {
            var e=RuleEvaluator.evaluate(s.rskl(),level,size);
            return new CyberGoModels.Step(s.ba().multiply(factor),s.bid(),level,size,s.ca(),s.fsn(),s.frwa().multiply(factor),s.gt(),s.nfsc(),s.rpx(),s.rskl(),s.rwa().multiply(factor),s.small_game_type(),s.ss(),s.wa().multiply(factor),e.matches(),e.winningSymbols());
        }).toList();
        var result=new CyberGoModels.CompleteRound(round.roundKey(),round.kind(),round.bet().multiply(factor),round.generatedAtEpochSecond(),steps);
        ResultUtil.validateCompleteRound(result);
        return result;
    }

}
