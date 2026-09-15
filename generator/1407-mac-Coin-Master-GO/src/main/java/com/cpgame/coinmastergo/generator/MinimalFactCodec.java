package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.CoinMasterResultUtil;
import com.cpgame.coinmastergo.core.CardMaterialState;
import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.core.RoundScenario;
import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpgame.coinmastergo.service.GameProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Redis member 仅保存不能由规则反推的完整 Round 事实：Delivery 模式、25 格牌面与金色坐标。
 * 余额、中奖字段、倍率和场景均不入 member，解码后由当前 ResultUtil 重新计算。
 */
public final class MinimalFactCodec {
    public static final String INDEPENDENT_LOSS = "#";
    private static final class LossGenerator {
        private static final GameRuleCore CORE = new GameRuleCore(new GameProperties());
    }
    private static final int VERSION = 1;
    private static final int BOARD_CELLS = 25;
    private static final int GOLD_CELLS = 15;
    private static final int STEP_CHARS = BOARD_CELLS + GOLD_CELLS;
    private static final BigDecimal VERIFY_BET_SIZE = new BigDecimal("0.02");
    private final ObjectMapper mapper = new ObjectMapper();

    public MinimalRoundFacts extract(RoundPlan round) {
        List<DeliveryFacts> deliveries = new ArrayList<>();
        for (RoundDelivery delivery : round.deliveries) {
            List<StepFacts> steps = new ArrayList<>();
            for (SpinStep step : delivery.steps) {
                steps.add(new StepFacts(List.copyOf(step.rskl), List.copyOf(step.gfl)));
            }
            deliveries.add(new DeliveryFacts(delivery.mode, List.copyOf(steps)));
        }
        return new MinimalRoundFacts(VERSION, List.copyOf(deliveries));
    }

    public String encode(RoundPlan round) {
        return encode(round, true);
    }

    /** 原盘审计/回归入口；Redis 正式写入使用 encode。 */
    public String encodeFull(RoundPlan round) {
        return encode(round, false);
    }

    private String encode(RoundPlan round, boolean compactLosses) {
        MinimalRoundFacts facts = extract(round);
        StringJoiner deliveries = new StringJoiner("|");
        for (DeliveryFacts delivery : facts.d()) {
            // Delivery 是一整个 Spin；单页无奖且无免费触发才独立。多页连消全部保留。
            if (compactLosses && delivery.s().size() == 1
                    && GameRuleCore.isIndependentLossBoard(delivery.s().getFirst().b())) {
                deliveries.add(INDEPENDENT_LOSS);
                continue;
            }
            StringBuilder steps = new StringBuilder(delivery.s().size() * STEP_CHARS);
            for (StepFacts step : delivery.s()) appendStep(steps, step);
            deliveries.add(steps);
        }
        return deliveries.toString();
    }

    public MinimalRoundFacts decode(String member) {
        try {
            String trimmed = member == null ? "" : member.trim();
            if (trimmed.isEmpty()) throw new IllegalArgumentException("empty compact round");
            MinimalRoundFacts facts = trimmed.charAt(0) == '{'
                    ? mapper.readValue(trimmed, MinimalRoundFacts.class)
                    : decodeCompactAscii(trimmed);
            validateFacts(facts);
            return facts;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("无法解码完整 Round member", error);
        }
    }

    /** 用事实重建所有派生状态，供独立 ResultUtil 二次复核。 */
    public RoundPlan rebuild(String member) {
        return rebuild(decode(member));
    }

    /** 同一局只解码物化一次；校验与后续投影复用这份事实，避免重新随机出牌。 */
    public RoundPlan rebuild(MinimalRoundFacts facts) {
        validateFacts(facts);
        RoundPlan round = new RoundPlan();
        round.roundKey = "redis-verification";
        round.transferId = "redis-verification";
        round.paidBid = GameRules.GAME_PROTOCOL_ID + "-redis-verification";
        round.betLevel = 1;
        round.betSize = VERIFY_BET_SIZE;
        round.betAmount = GameRules.betAmount(round.betLevel, round.betSize);
        round.postDebitBalance = BigDecimal.ZERO;
        round.createdAt = 0L;

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal freeCumulative = BigDecimal.ZERO;
        int fsn = 0;
        int nfsc = 0;
        boolean retrigger = false;
        boolean golden = false;
        int globalStep = 0;
        for (DeliveryFacts deliveryFact : facts.d()) {
            boolean free = "FREE".equals(deliveryFact.m());
            if (free) nfsc++;
            CoinMasterResultUtil.Evaluation terminalBoard = CoinMasterResultUtil.evaluate(
                    deliveryFact.s().getLast().b(), round.betLevel, round.betSize,
                    (free ? GameRules.FREE_RPX : GameRules.BASE_RPX).get(Math.min(
                            deliveryFact.s().size() - 1,
                            (free ? GameRules.FREE_RPX : GameRules.BASE_RPX).size() - 1)));
            int awardAtTerminal = GameRules.freeAward(terminalBoard.scatterCount());
            if (free && awardAtTerminal > 0) retrigger = true;
            List<SpinStep> rebuiltSteps = new ArrayList<>();
            for (int stepIndex = 0; stepIndex < deliveryFact.s().size(); stepIndex++) {
                StepFacts stepFact = deliveryFact.s().get(stepIndex);
                int rpx = (free ? GameRules.FREE_RPX : GameRules.BASE_RPX)
                        .get(Math.min(stepIndex, (free ? GameRules.FREE_RPX : GameRules.BASE_RPX).size() - 1));
                CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                        stepFact.b(), round.betLevel, round.betSize, rpx);
                boolean terminal = stepIndex + 1 == deliveryFact.s().size();
                SpinStep step = new SpinStep();
                step.ba = globalStep++ == 0 ? round.betAmount : BigDecimal.ZERO;
                step.gt = free ? 2 : 1;
                step.small_game_type = free ? 2 : (step.ba.signum() > 0 ? 0 : 1);
                step.rpx = rpx;
                step.ss = terminal ? 1 : 0;
                step.fsn = terminal ? fsn + awardAtTerminal : fsn;
                step.nfsc = free ? nfsc : 0;
                step.pb = "0.00";
                step.rskl = new ArrayList<>(stepFact.b());
                step.gfl = new ArrayList<>(stepFact.g());
                step.silverCardCoordinates = new ArrayList<>(
                        CardMaterialState.decodeSilverCoordinates(step.rskl, step.gfl));
                step.wa = evaluation.totalWin();
                cumulative = cumulative.add(step.wa);
                if (free) freeCumulative = freeCumulative.add(step.wa);
                step.rwa = CoinMasterResultUtil.money(cumulative);
                step.frwa = CoinMasterResultUtil.money(freeCumulative);
                step.matchDetails = new ArrayList<>(evaluation.matches());
                for (WinMatch match : evaluation.matches()) {
                    step.wskl.add(match.symbol);
                    step.wmkl.add(match.coordinates);
                }
                Set<Integer> goldenCoordinates = new HashSet<>(step.gfl);
                golden |= evaluation.matches().stream()
                        .flatMap(match -> match.coordinates.stream())
                        .flatMap(List::stream)
                        .anyMatch(rawCoordinate -> goldenCoordinates.contains(rawCoordinate + 1));
                rebuiltSteps.add(step);
            }
            round.deliveries.add(new RoundDelivery(deliveryFact.m(), rebuiltSteps));
            fsn += awardAtTerminal;
        }
        round.totalWin = CoinMasterResultUtil.money(cumulative);
        SpinStep last = round.deliveries.getLast().steps.getLast();
        last.pb = round.totalWin.setScale(2).toPlainString();
        if (nfsc > 0) round.scenario = retrigger ? RoundScenario.FREE_RETRIGGER.name() : RoundScenario.FREE_SPINS.name();
        else if (golden) round.scenario = RoundScenario.GOLDEN_TRANSFORM.name();
        else round.scenario = round.totalWin.signum() > 0 ? RoundScenario.BASE_WIN.name() : RoundScenario.LOSS.name();
        new RoundValidator().validate(round);
        return round;
    }

    private MinimalRoundFacts decodeCompactAscii(String value) {
        String[] encodedDeliveries = value.split("\\|", -1);
        List<DeliveryFacts> deliveries = new ArrayList<>(encodedDeliveries.length);
        for (int deliveryIndex = 0; deliveryIndex < encodedDeliveries.length; deliveryIndex++) {
            String encoded = encodedDeliveries[deliveryIndex];
            if (INDEPENDENT_LOSS.equals(encoded)) {
                SpinStep loss = LossGenerator.CORE.generateIndependentLoss(deliveryIndex > 0);
                deliveries.add(new DeliveryFacts(deliveryIndex == 0 ? "BASE" : "FREE",
                        List.of(new StepFacts(List.copyOf(loss.rskl), List.copyOf(loss.gfl)))));
                continue;
            }
            if (encoded.isEmpty() || encoded.length() % STEP_CHARS != 0) {
                throw new IllegalArgumentException("invalid compact delivery length");
            }
            List<StepFacts> steps = new ArrayList<>();
            for (int offset = 0; offset < encoded.length(); offset += STEP_CHARS) {
                List<String> board = new ArrayList<>(BOARD_CELLS);
                for (int i = 0; i < BOARD_CELLS; i++) board.add(decodeSymbol(encoded.charAt(offset + i)));
                List<Integer> gold = new ArrayList<>();
                int maskAt = offset + BOARD_CELLS;
                int goldIndex = 0;
                for (int reel = 1; reel <= 3; reel++) {
                    for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                        char mask = encoded.charAt(maskAt + goldIndex);
                        if (mask != '0' && mask != '1') throw new IllegalArgumentException("invalid gold mask");
                        if (mask == '1') gold.add(reel * 10 + row);
                        goldIndex++;
                    }
                }
                steps.add(new StepFacts(List.copyOf(board), List.copyOf(gold)));
            }
            deliveries.add(new DeliveryFacts(deliveryIndex == 0 ? "BASE" : "FREE", List.copyOf(steps)));
        }
        return new MinimalRoundFacts(VERSION, List.copyOf(deliveries));
    }

    private void appendStep(StringBuilder target, StepFacts step) {
        if (step.b().size() != BOARD_CELLS) throw new IllegalArgumentException("transport board size differs from capability");
        for (String symbol : step.b()) target.append(encodeSymbol(symbol));
        Set<Integer> gold = new HashSet<>(step.g());
        for (int reel = 1; reel <= 3; reel++) {
            for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                target.append(gold.contains(reel * 10 + row) ? '1' : '0');
            }
        }
    }

    private static char encodeSymbol(String symbol) {
        return switch (symbol) {
            case "H1" -> '1'; case "H2" -> '2'; case "H3" -> '3'; case "H4" -> '4';
            case "H5" -> '5'; case "H6" -> '6'; case "H7" -> '7'; case "H8" -> '8';
            case "WILD" -> '9'; case "SC" -> 'A';
            default -> throw new IllegalArgumentException("unconfirmed symbol: " + symbol);
        };
    }

    private static String decodeSymbol(char encoded) {
        return switch (encoded) {
            case '1' -> "H1"; case '2' -> "H2"; case '3' -> "H3"; case '4' -> "H4";
            case '5' -> "H5"; case '6' -> "H6"; case '7' -> "H7"; case '8' -> "H8";
            case '9' -> "WILD"; case 'A' -> "SC";
            default -> throw new IllegalArgumentException("unknown compact symbol: " + encoded);
        };
    }

    private void validateFacts(MinimalRoundFacts facts) {
        if (facts == null || facts.v() != VERSION || facts.d() == null || facts.d().isEmpty()) {
            throw new IllegalArgumentException("member 版本或 Delivery 为空");
        }
        if (!"BASE".equals(facts.d().getFirst().m())) throw new IllegalArgumentException("首个 Delivery 必须为 BASE");
        for (int deliveryIndex = 0; deliveryIndex < facts.d().size(); deliveryIndex++) {
            DeliveryFacts delivery = facts.d().get(deliveryIndex);
            String expectedMode = deliveryIndex == 0 ? "BASE" : "FREE";
            if (!("BASE".equals(delivery.m()) || "FREE".equals(delivery.m()))
                    || delivery.s() == null || delivery.s().isEmpty()) {
                throw new IllegalArgumentException("Delivery 模式或 Step 为空");
            }
            if (!expectedMode.equals(delivery.m())) {
                throw new IllegalArgumentException("完整 Round 只能包含一个首段 BASE，后续必须是 FREE");
            }
            for (StepFacts step : delivery.s()) {
                CoinMasterResultUtil.validateTransportBoard(step.b());
                if (step.g() == null) throw new IllegalArgumentException("gfl 必须是数组");
            }
        }
    }

    public record MinimalRoundFacts(int v, List<DeliveryFacts> d) { }
    public record DeliveryFacts(String m, List<StepFacts> s) { }
    public record StepFacts(List<String> b, List<Integer> g) { }
}
