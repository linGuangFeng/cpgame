package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.util.List;

/** 以最小事实重新反推真实模式、派奖和状态，不使用工厂侧期望值。 */
public final class IndependentRoundVerifier {
    private static final MinimalFactCodec FACT_CODEC = new MinimalFactCodec();

    private IndependentRoundVerifier() { }

    public static ResultAnalysis verify(GameRound round, BigDecimal balanceBefore) {
        ResultUtil.require(round != null && round.deliveries().size() == 1, "Round 必须一次完整生成且仅有一个 Delivery");
        RoundDelivery delivery = round.deliveries().get(0);
        ResultUtil.require(delivery.deliveryIndex() == 0, "deliveryIndex 必须从0开始");
        SpinResult result = delivery.result();
        BigDecimal expectedBet = BigDecimal.valueOf((long) result.bl() * result.bs());
        ResultUtil.require(result.bl() >= 1 && result.bs() == 1 && result.ba().compareTo(expectedBet) == 0,
                "下注金额与 bl/bs 不一致");
        ResultUtil.require(result.gt() == 1, "协议 gt 不匹配");
        ResultUtil.require(result.rskl().size() == (result.bl() < 5 ? 2 : 3), "基础符号尺寸与下注门槛不一致");
        List<String> nonBlank = result.rskl().stream().filter(symbol -> !"H0".equals(symbol)).toList();
        ResultUtil.require(nonBlank.equals(result.wskl()), "wskl 必须由 rskl 去除 H0 得到");

        RoundFacts facts = FACT_CODEC.extract(round);
        ResultAnalysis analysis = ResultUtil.analyze(facts);
        ResultUtil.require(round.outcome() == analysis.outcome(), "Round outcome 与独立反推不一致");
        ResultUtil.require(result.wa().compareTo(analysis.totalAward()) == 0, "wa 与独立反推不一致");
        ResultUtil.require(result.fwa().compareTo(analysis.featureAward()) == 0, "fwa 与独立反推不一致");
        ResultUtil.require(!analysis.continuationRequired(), "当前已确认 Round 不得残留跨请求状态");

        switch (result.md()) {
            case 0 -> {
                ResultUtil.require("H0".equals(result.fws()) && result.fws().equals(result.fsk()), "md=0 轮字段错误");
                ResultUtil.require(result.rpx() == 1 && result.fwi().isEmpty(), "md=0 附加字段错误");
            }
            case 1 -> {
                ResultUtil.require(Integer.toString(result.rpx()).equals(result.fws()) && result.fws().equals(result.fsk()),
                        "md=1 轮字段错误");
                ResultUtil.require(result.fwi().isEmpty(), "md=1 不得携带重转事实");
            }
            case 2 -> ResultUtil.require("RS".equals(result.fws()) && result.fws().equals(result.fsk()) && result.rpx() == 1,
                    "md=2 轮字段错误");
            case 3 -> ResultUtil.require(result.bl() >= 5 && "SCAT".equals(result.fws())
                            && result.fws().equals(result.fsk()) && result.rpx() == 1
                            && result.smallGameType() == 2 && result.fwi().equals(List.of(result.fwa().stripTrailingZeros().toPlainString())),
                    "md=3 解锁门槛或标量奖励字段错误");
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + result.md());
        }

        if (result.md() != 3) ResultUtil.require(result.smallGameType() == 0, "非MD3 small_game_type必须为0");
        BigDecimal settled = balanceBefore.subtract(result.ba()).add(analysis.totalAward());
        ResultUtil.require(new BigDecimal(result.pb()).compareTo(settled) == 0, "余额递推错误");
        return analysis;
    }
}
