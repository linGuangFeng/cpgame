package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundFact;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaSymbol;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaWin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Redis 完整局 member 投影成 spin / History 字段。
 * 判奖只走 GameRuleCore / LuckyPandaResultUtil，不在这里再写一套 ways 或赔表。
 *
 * 反推要点（见 protocol/41-Lucky-Panda/spin-source-projection.md）：
 * - ba 付费起点 = bs*bl*20，连消/免费续局 ba=0。
 * - pb 在整局未结束前停在「已扣注」余额，终态 ss=1 且 (fsn=0 或 nfsc=fsn) 才把 rwa 加进 pb。
 * - 玛丽触发页必是 ss=1/wa=0，但整局 38/38 仍有正 rwa，不是「触发局必定不中奖」。
 * - rpx 原样使用 member 页值；0 表示 x1。不要猜 +2/连消。
 * - spin wmkl 是 array-of-arrays；History / config.last 用 {sk,wa,wmk}。
 */
final class SpinProjector {
    static final int STAKE_FACTOR = GameRuleCore.STAKE_FACTOR;
    static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    static final List<BigDecimal> BET_SIZES = List.of(new BigDecimal("0.02"), new BigDecimal("0.2"));
    static final List<Integer> AUTO = List.of(10, 30, 50, 80, 1000);

    private SpinProjector() { }

    static BigDecimal stake(BigDecimal betSize, int betLevel) {
        return GameRuleCore.stakeAmount(betSize, betLevel).setScale(2, RoundingMode.HALF_UP);
    }

    static boolean legalBet(BigDecimal betSize, int betLevel) {
        return BET_LEVELS.contains(betLevel)
                && BET_SIZES.stream().anyMatch(value -> value.compareTo(betSize) == 0);
    }

    static Map<String, int[]> symbolPayList() {
        Map<String, int[]> table = new LinkedHashMap<>();
        Map<LuckyPandaSymbol, int[]> pays = LuckyPandaResultUtil.copyPayTable();
        for (LuckyPandaSymbol symbol : GameRuleCore.payingSymbols()) {
            int[] src = pays.get(symbol);
            int[] indexed = new int[7];
            for (int reels = 3; reels <= 6; reels++) indexed[reels] = src[reels - 3];
            table.put(symbol.wireName(), indexed);
        }
        return table;
    }

    static List<Delivery> flatten(CompleteRoundFact fact, BigDecimal betSize, int betLevel) {
        boolean scatterFree = !fact.freeSpins().isEmpty();
        List<Delivery> out = new ArrayList<>();
        BigDecimal rwa = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int awarded = 0;
        for (int i = 0; i < fact.paid().size(); i++) {
            CompleteRoundFact.PageFact page = fact.paid().get(i);
            LuckyPandaEvaluation evaluation = GameRuleCore.evaluate(page.board(), betSize, betLevel, page.rpx());
            rwa = rwa.add(evaluation.wa()).setScale(2, RoundingMode.HALF_UP);
            boolean lastPaid = i == fact.paid().size() - 1;
            int fsn = 0;
            if (scatterFree && lastPaid) {
                if (!LuckyPandaResultUtil.scatterFreeTrigger(evaluation, 0)) {
                    throw new IllegalStateException("scatter-free member paid terminal is not a 4-block trigger");
                }
                awarded = LuckyPandaResultUtil.scatterFreeAwarded(evaluation.scatterTokens());
                fsn = awarded;
            }
            out.add(delivery(page, evaluation, rwa, fsn, 0, i == 0, scatterFree, page.gfl(), page.sfl()));
        }
        int runningFsn = awarded;
        for (int spin = 0; spin < fact.freeSpins().size(); spin++) {
            List<CompleteRoundFact.PageFact> pages = fact.freeSpins().get(spin);
            CompleteRoundFact.PageFact termPage = pages.get(pages.size() - 1);
            LuckyPandaEvaluation termEval = GameRuleCore.evaluate(
                    termPage.board(), betSize, betLevel, termPage.rpx());
            int extra = 0;
            if (LuckyPandaResultUtil.scatterRetrigger(termEval)) {
                extra = LuckyPandaResultUtil.scatterFreeAwarded(termEval.scatterTokens());
            }
            for (int p = 0; p < pages.size(); p++) {
                CompleteRoundFact.PageFact page = pages.get(p);
                LuckyPandaEvaluation evaluation = GameRuleCore.evaluate(page.board(), betSize, betLevel, page.rpx());
                rwa = rwa.add(evaluation.wa()).setScale(2, RoundingMode.HALF_UP);
                int fsnNow = runningFsn + (p == pages.size() - 1 ? extra : 0);
                out.add(delivery(page, evaluation, rwa, fsnNow, spin + 1, false, true,
                        page.gfl(), page.sfl()));
            }
            runningFsn += extra;
        }
        if (out.isEmpty()) throw new IllegalStateException("complete Round has no deliveries");
        Delivery last = out.get(out.size() - 1);
        if (!LuckyPandaResultUtil.roundTerminal(last.ss(), last.fsn(), last.nfsc())) {
            throw new IllegalStateException("projected Round is not terminal");
        }
        return List.copyOf(out);
    }

    private static Delivery delivery(CompleteRoundFact.PageFact page, LuckyPandaEvaluation evaluation,
                                     BigDecimal rwa, int fsn, int nfsc, boolean paidStart, boolean scatterFree,
                                     List<Integer> gfl, List<Integer> sfl) {
        if (!GameRuleCore.withinCapturedCaps(page.board())) {
            throw new IllegalStateException("projected page exceeds captured Scat/Wild caps");
        }
        boolean terminal = LuckyPandaResultUtil.roundTerminal(evaluation.ss(), fsn, nfsc);
        return new Delivery(
                page.board().toRskl(),
                page.rpx(),
                evaluation.wa().setScale(2, RoundingMode.HALF_UP),
                rwa,
                evaluation.ss(),
                fsn,
                nfsc,
                evaluation.wskl(),
                evaluation.wmkl(),
                evaluation.wins(),
                paidStart,
                terminal,
                scatterFree,
                List.copyOf(gfl == null ? List.of() : gfl),
                List.copyOf(sfl == null ? List.of() : sfl));
    }

    static Map<String, Object> spinBody(Delivery delivery, BigDecimal stake, BigDecimal pb) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ba", delivery.paidStart() ? stake : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        data.put("frwa", 0);
        data.put("fsn", delivery.fsn());
        data.put("gfl", delivery.gfl());
        data.put("gt", 1);
        data.put("nfsc", delivery.nfsc());
        data.put("pb", pb.toPlainString());
        data.put("rpx", delivery.rpx());
        data.put("rskl", delivery.rskl());
        data.put("rwa", delivery.rwa());
        data.put("sfl", delivery.sfl());
        data.put("ss", delivery.ss());
        data.put("wa", delivery.wa());
        data.put("wmkl", delivery.wmkl());
        data.put("wskl", delivery.wskl());
        data.put("_source", "redis-db15-complete-round");
        return data;
    }

    static Map<String, Object> historyStep(Delivery delivery, BigDecimal betSize, int betLevel,
                                           BigDecimal pb, String bid, long createdAt, BigDecimal stake) {
        Map<String, Object> data = new LinkedHashMap<>(spinBody(delivery, stake, pb));
        data.remove("_source");
        data.put("ba", delivery.wa().stripTrailingZeros().toPlainString());
        data.put("balance_after", pb.toPlainString());
        data.put("bet_level", betLevel);
        data.put("bet_size", betSize);
        data.put("bid", bid);
        data.put("bl", betLevel);
        data.put("bs", betSize);
        data.put("ca", createdAt);
        data.put("cc", "BRL");
        data.put("created_at", createdAt);
        data.put("cs", "R$");
        data.put("wmkl", historyWmkl(delivery));
        return data;
    }

    static List<Map<String, Object>> historyWmkl(Delivery delivery) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LuckyPandaWin win : delivery.wins()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sk", win.symbol().wireName());
            row.put("wa", win.award());
            row.put("wmk", win.wmkl());
            rows.add(row);
        }
        return rows;
    }

    static Map<String, Object> lastForConfig(Delivery delivery, BigDecimal betSize, int betLevel,
                                             BigDecimal pb, BigDecimal stake) {
        Map<String, Object> last = new LinkedHashMap<>(spinBody(delivery, stake, pb));
        last.put("bl", betLevel);
        last.put("bs", betSize);
        List<Map<String, Object>> wmkl = historyWmkl(delivery);
        last.put("wmkl", wmkl);
        return last;
    }

    record Delivery(List<String> rskl, int rpx, BigDecimal wa, BigDecimal rwa, int ss, int fsn, int nfsc,
                    List<String> wskl, List<List<List<Integer>>> wmkl, List<LuckyPandaWin> wins,
                    boolean paidStart, boolean roundTerminal, boolean scatterFree,
                    List<Integer> gfl, List<Integer> sfl) { }
}
