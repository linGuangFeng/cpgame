package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.GameRuleCore;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotEvaluation;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotGameRuleCore;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotPageKind;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotWin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 Redis 完整局 member 投影成 gameResult / History 字段。
 * 判奖只走 GameRuleCore / HotpotResultUtil，不在这里再写一套 8 连或倍率。
 *
 * 反推要点（见 protocol/1830-Hotpot/spin-source-projection.md）：
 * - 无 Wild 替代；11=Scatter；12..23 是倍率符，不是 8 连赔付符号。
 * - 同一 gameResult 的 props[] 是该次旋转全部连消页；免费是后续另一次 gameResult。
 * - 末页才把盘上倍率求和后乘该次旋转 odd 合计（History 页 2+3+3=8）。
 * - Scatter 付费 3 个起 10 次（每多 1 个 +2）；免费中 2 个再给 5 次。触发局可以同时有 8 连奖，并非“必不中”。
 */
public final class HotpotSpinProjector {
    /** betLinesConfig=[20] 是 Base Bet，不是 paylineCount。charged = betSize × level × 20。 */
    public static final int BASE_BET = 20;
    public static final int RAW_GID = 1830;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final GameRuleCore core;

    public HotpotSpinProjector() {
        this.core = new HotpotGameRuleCore();
    }

    public GameRuleCore core() {
        return core;
    }

    public BigDecimal chargedStake(BigDecimal betSize, int level) {
        return betSize.multiply(BigDecimal.valueOf(level))
                .multiply(BigDecimal.valueOf(BASE_BET))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal unitStake(BigDecimal betSize, int level) {
        return betSize.multiply(BigDecimal.valueOf(level)).setScale(4, RoundingMode.HALF_UP);
    }

    public ProjectedSpin project(List<CompleteRoundFact.BoardFact> pages, HotpotSpinMode mode,
                                 BigDecimal betSize, int level, BigDecimal startGold, long oid,
                                 int remainingBefore, int totalAwardedBefore, BigDecimal featureWinBefore) {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("spin has no pages");
        List<HotpotBoard> boards = new ArrayList<>(pages.size());
        for (CompleteRoundFact.BoardFact page : pages) boards.add(page.toBoard());
        int integerMultiplier = HotpotResultUtil.spinIntegerMultiplier(boards);
        BigDecimal unit = unitStake(betSize, level);
        BigDecimal charged = chargedStake(betSize, level);
        BigDecimal totalWin = BigDecimal.valueOf(integerMultiplier).multiply(unit).setScale(2, RoundingMode.HALF_UP);

        ArrayNode props = JSON.createArrayNode();
        int oddRunning = 0;
        HotpotEvaluation lastEval = null;
        for (int i = 0; i < boards.size(); i++) {
            HotpotBoard board = boards.get(i);
            HotpotEvaluation evaluation = core.evaluate(board);
            lastEval = evaluation;
            ObjectNode page = props.addObject();
            writeProp(page, board);
            boolean last = i == boards.size() - 1;
            switch (evaluation.getPageKind()) {
                case WIN -> {
                    if (last) throw new IllegalStateException("winning spin missing terminal no-win page");
                    oddRunning += evaluation.getOddSum();
                    writeWinArr(page, evaluation, unit);
                    page.set("mult", JSON.createArrayNode());
                    putDecimal(page, "tw", money(BigDecimal.valueOf(oddRunning).multiply(unit)));
                }
                case TERMINAL_NO_WIN -> {
                    if (!last) throw new IllegalStateException("page after terminal no-win");
                    int factor = 1;
                    if (boards.size() >= 2 && evaluation.getMultiplierSum() > 0) {
                        factor = evaluation.getMultiplierSum();
                    }
                    page.set("win_arr", JSON.createArrayNode());
                    writeMult(page, board);
                    putDecimal(page, "tw", money(BigDecimal.valueOf(oddRunning).multiply(BigDecimal.valueOf(factor)).multiply(unit)));
                }
            }
        }
        if (lastEval == null) throw new IllegalStateException("empty evaluation");
        int awarded = core.awardedFreeSpins(lastEval.getScatterCount(), mode);
        int remaining;
        int totalAwarded;
        BigDecimal featureWin;
        boolean paid = mode == HotpotSpinMode.PAID;
        if (paid) {
            remaining = awarded;
            totalAwarded = awarded;
            featureWin = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            remaining = remainingBefore - 1 + awarded;
            totalAwarded = totalAwardedBefore + awarded;
            featureWin = featureWinBefore.add(totalWin).setScale(2, RoundingMode.HALF_UP);
        }
        if (remaining < 0) throw new IllegalStateException("free remaining went negative");

        BigDecimal deduct = paid ? charged : BigDecimal.ZERO;
        BigDecimal change = totalWin.subtract(deduct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal endGold = startGold.subtract(deduct).add(totalWin).setScale(2, RoundingMode.HALF_UP);
        BigDecimal odds = charged.signum() == 0 ? BigDecimal.ZERO
                : totalWin.divide(charged, 6, RoundingMode.HALF_UP).stripTrailingZeros();

        ObjectNode data = JSON.createObjectNode();
        putDecimal(data, "bet", money(betSize));
        putDecimal(data, "bet_gold", money(charged));
        putDecimal(data, "change_gold", money(change));
        putDecimal(data, "end_gold", money(endGold));
        ObjectNode frees = data.putObject("frees");
        if (totalAwarded == 0) {
            frees.put("ba", 0).put("bet", 0).put("l", 0).put("lwa", 0).put("st", 0).put("tt", 0).put("twa", 0);
        } else {
            putDecimal(frees, "ba", money(charged));
            putDecimal(frees, "bet", money(betSize));
            frees.put("l", level);
            frees.put("lwa", 0);
            frees.put("st", remaining);
            frees.put("tt", totalAwarded);
            putDecimal(frees, "twa", money(featureWin));
        }
        data.put("level", level);
        putDecimal(data, "odds", odds);
        data.put("oid", oid);
        data.set("props", props);
        data.put("small_game_type", paid ? 0 : 2);
        putDecimal(data, "start_gold", money(startGold));
        putDecimal(data, "total_win", money(totalWin));
        data.put("type", paid ? 1 : 2);
        return new ProjectedSpin(data, remaining, totalAwarded, featureWin, totalWin, charged, integerMultiplier);
    }

    public ArrayNode payTable() {
        ArrayNode table = JSON.createArrayNode();
        for (int symbol = HotpotResultUtil.MIN_PAY_SYMBOL; symbol <= HotpotResultUtil.MAX_PAY_SYMBOL; symbol++) {
            ObjectNode row = table.addObject();
            row.put("prop_id", symbol);
            ArrayNode odds = row.putArray("odds");
            for (int count = HotpotResultUtil.MIN_MATCH; count <= HotpotBoard.SIZE; count++) {
                odds.addObject().put("num", count).put("odds", HotpotResultUtil.payOdd(symbol, count));
            }
        }
        return table;
    }

    /**
     * 进厅盘用抓包 initRoom 快照（captures/1830-Hotpot/init-room.response.json），
     * 不是 1..7 斜纹。ResultUtil 判定无 8 连且未触发 Scatter。不是付费结果，不走 Redis。
     */
    static final int[] IDLE_FROM_CAPTURED_INITROOM = {
            8, 4, 2, 4, 8, 9,
            2, 10, 9, 10, 9, 7,
            1, 9, 2, 5, 8, 8,
            8, 4, 5, 9, 10, 10,
            1, 5, 6, 6, 9, 10,
            8, 1, 3, 1, 5, 3
    };

    public int[] idleProp() {
        HotpotBoard board = new HotpotBoard(IDLE_FROM_CAPTURED_INITROOM);
        HotpotEvaluation evaluation = core.evaluate(board);
        if (evaluation.getPageKind() != HotpotPageKind.TERMINAL_NO_WIN
                || core.awardedFreeSpins(evaluation.getScatterCount(), HotpotSpinMode.PAID) != 0) {
            throw new IllegalStateException("idle snapshot is not an ordinary no-win board");
        }
        return board.getProp();
    }

    public ObjectNode idleInit(BigDecimal balance, BigDecimal betSize, int level) {
        ObjectNode data = JSON.createObjectNode();
        BigDecimal charged = chargedStake(betSize, level);
        putDecimal(data, "bet", money(betSize));
        putDecimal(data, "bet_gold", money(charged));
        data.put("change_gold", 0);
        putDecimal(data, "end_gold", money(balance));
        data.putObject("frees").put("ba", 0).put("bet", 0).put("l", 0).put("lwa", 0).put("st", 0).put("tt", 0).put("twa", 0);
        data.put("level", level);
        data.put("odds", 0);
        data.put("oid", 0);
        data.set("prop_odds", payTable());
        ObjectNode page = data.putArray("props").addObject();
        writeProp(page, new HotpotBoard(idleProp()));
        page.set("mult", JSON.createArrayNode());
        page.set("win_arr", JSON.createArrayNode());
        page.put("tw", 0);
        data.put("small_game_type", 0);
        putDecimal(data, "start_gold", money(balance));
        data.put("total_win", 0);
        data.put("type", 1);
        return data;
    }

    public ObjectNode historyGroup(ObjectNode spin, ArrayNode pages, boolean paid, String parentOrder) {
        ObjectNode group = spin.deepCopy();
        group.remove("props");
        group.set("result", pages);
        if (group.path("extend").isMissingNode()) {
            group.putObject("extend").put("act_bet_gold", 0).put("act_id", "0").put("act_type", 0);
        }
        if (!paid) {
            group.put("bet_gold", 0);
            group.put("forder_id", parentOrder);
        }
        return group;
    }

    private void writeProp(ObjectNode page, HotpotBoard board) {
        ArrayNode prop = page.putArray("prop");
        for (int symbol : board.getProp()) prop.add(symbol);
    }

    private void writeWinArr(ObjectNode page, HotpotEvaluation evaluation, BigDecimal unit) {
        ArrayNode wins = page.putArray("win_arr");
        for (HotpotWin win : evaluation.getWins()) {
            ObjectNode row = wins.addObject();
            row.put("n", win.getCount());
            row.put("odd", win.getOdd());
            row.put("p", win.getSymbol());
            putDecimal(row, "wm", money(BigDecimal.valueOf(win.getOdd()).multiply(unit)));
        }
    }

    private void writeMult(ObjectNode page, HotpotBoard board) {
        int[] prop = board.getProp();
        ObjectNode mult = JSON.createObjectNode();
        boolean any = false;
        for (int i = 0; i < prop.length; i++) {
            if (prop[i] >= HotpotResultUtil.MIN_MULTIPLIER_ID && prop[i] <= HotpotResultUtil.MAX_MULTIPLIER_ID) {
                mult.put(Integer.toString(i), HotpotResultUtil.multiplierValue(prop[i]));
                any = true;
            }
        }
        if (any) page.set("mult", mult);
        else page.set("mult", JSON.createArrayNode());
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) node.put(field, stripped.longValue());
        else node.put(field, stripped.doubleValue());
    }

    public record ProjectedSpin(ObjectNode data, int remaining, int totalAwarded, BigDecimal featureWin,
                                BigDecimal totalWin, BigDecimal charged, int integerMultiplier) { }
}
