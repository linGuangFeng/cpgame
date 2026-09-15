package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 一整局的编排器。随机牌面由 BoardGenerator 产生，每页奖金只接受 ResultUtil 的反推结果。
 * 该类不依赖 Spring/Redis，可连同两个核心类直接复制到其他项目。
 */
public final class FreedomDayRoundGenerator {
    private static final int MAX_CASCADE_PAGES = 6;
    private static final int MAX_FREE_SPINS = 30;

    private final FreedomDayBoardGenerator boardGenerator;
    private final java.security.SecureRandom lossRandom = new java.security.SecureRandom();
    private final FreedomDayIndependentLossGenerator losses = new FreedomDayIndependentLossGenerator();

    public FreedomDayRoundGenerator() { this(new FreedomDayBoardGenerator()); }
    public FreedomDayRoundGenerator(FreedomDayBoardGenerator boardGenerator) { this.boardGenerator = boardGenerator; }

    public JSONObject generate(double betSize, int level, boolean featureBuy, int targetRatio) {
        BigDecimal unitBet = BigDecimal.valueOf(betSize).multiply(BigDecimal.valueOf(level));
        JSONArray spins = new JSONArray();

        FreedomDayBoard initial = featureBuy
                ? boardGenerator.generateFeatureTrigger()
                : generateInitial(targetRatio);
        JSONObject first = generateSpin(initial, unitBet, false, featureBuy ? 3 : 1, 1, 2);
        spins.add(first);

        int freeTotal = ((Number) first.get("awardedFreeSpins")).intValue();
        int freeIndex = 0;
        int freeMultiplier = 2;
        while (freeIndex < freeTotal && freeIndex < MAX_FREE_SPINS) {
            JSONObject freeSpin = generateSpin(boardGenerator.generate(true), unitBet, true, 2,
                    freeMultiplier, 2);
            freeMultiplier = freeSpin.getIntValue("endingMultiplier");
            int retrigger = freeSpin.getIntValue("awardedFreeSpins");
            freeTotal = Math.min(MAX_FREE_SPINS, freeTotal + retrigger);
            freeIndex++;
            freeSpin.put("freeIndex", freeIndex);
            spins.add(freeSpin);
        }

        BigDecimal totalMultiplier = BigDecimal.ZERO;
        for (int i = 0; i < spins.size(); i++) {
            totalMultiplier = totalMultiplier.add(spins.getJSONObject(i).getBigDecimal("spinMultiplier"));
        }
        JSONObject result = new JSONObject(true);
        result.put("ml", totalMultiplier.stripTrailingZeros().toPlainString());
        result.put("mul", totalMultiplier.stripTrailingZeros().toPlainString());
        result.put("totalSpins", spins.size());
        result.put("freeTotal", freeTotal);
        result.put("featureBuy", featureBuy);
        result.put("spins", spins);
        return result;
    }

    private FreedomDayBoard generateInitial(int targetRatio) {
        // 结算层投到 0 倍时必须真的是未中奖牌面，不能只把金额改成 0。
        if (targetRatio <= 0) return losses.generate(lossRandom, false);
        return boardGenerator.generate(false);
    }

    private JSONObject generateSpin(FreedomDayBoard firstBoard, BigDecimal unitBet, boolean freeMode,
                                    int type, int startingMultiplier, int increment) {
        JSONArray pages = new JSONArray();
        FreedomDayBoard board = firstBoard;
        BigDecimal spinMultiplier = BigDecimal.ZERO;
        int multiplier = startingMultiplier;
        int awardedFreeSpins = 0;

        for (int pageIndex = 0; pageIndex < MAX_CASCADE_PAGES; pageIndex++) {
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, unitBet, multiplier, increment);
            pages.add(toPage(board, evaluation));
            spinMultiplier = spinMultiplier.add(evaluation.getTotalMultiplier());
            multiplier = evaluation.getMultiplier();
            if (pageIndex == 0) awardedFreeSpins = evaluation.getAwardedFreeSpins();
            if (evaluation.getWins().isEmpty()) break;
            board = boardGenerator.cascade(board, evaluation, freeMode);
        }

        JSONObject spin = new JSONObject(true);
        spin.put("type", type);
        spin.put("props", pages);
        spin.put("spinMultiplier", spinMultiplier);
        spin.put("spinWin", unitBet.multiply(spinMultiplier).setScale(2, RoundingMode.HALF_UP));
        spin.put("awardedFreeSpins", awardedFreeSpins);
        spin.put("endingMultiplier", multiplier);
        return spin;
    }

    private JSONObject toPage(FreedomDayBoard board, FreedomDayEvaluation evaluation) {
        JSONObject page = new JSONObject(true);
        page.put("prop", toList(board.getProp()));
        page.put("trl", toList(board.getTrl()));
        page.put("grids", new JSONArray());
        page.put("gf", new JSONArray());
        page.put("sl", new JSONArray());
        page.put("m", evaluation.getMultiplier());
        page.put("tw", evaluation.getTotalWin());
        JSONArray winArray = new JSONArray();
        for (FreedomDayWin win : evaluation.getWins()) {
            JSONObject item = new JSONObject(true);
            item.put("p", win.getMainPositions());
            item.put("h", win.getTopPositions());
            item.put("wm", win.getWinMoney());
            item.put("way", win.getWays());
            item.put("m", win.getMultiplier());
            item.put("way_n", win.getReelCount());
            item.put("pr", win.getSymbol());
            item.put("p_odd", win.getPayOdd());
            winArray.add(item);
        }
        page.put("win_arr", winArray);
        return page;
    }

    private List<Integer> toList(int[] source) {
        List<Integer> result = new ArrayList<>(source.length);
        for (int value : source) result.add(value);
        return result;
    }
}
