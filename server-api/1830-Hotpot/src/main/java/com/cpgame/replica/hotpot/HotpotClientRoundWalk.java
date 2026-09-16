package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotEvaluation;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotWin;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Original Game1830 ResultDisplay hangs forever when a non-last cascade page
 * creates no empty cells: ScatterNextPageEliminate / ReadyNextPageEliminate
 * only advance on fill-tween callbacks, with no e==0 fallback.
 * RecBetResult also calls change_gold.toFixed, so money fields must be JSON numbers.
 */
public final class HotpotClientRoundWalk {
    private HotpotClientRoundWalk() { }

    public static String hangReason(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) return "gameResult data missing";
        for (String field : new String[]{"change_gold", "bet_gold", "total_win", "start_gold", "end_gold"}) {
            JsonNode value = data.get(field);
            if (value == null || value.isNull() || !value.isNumber()) {
                return field + " is not a JSON number; RecBetResult toFixed throws and the spin button stays disabled";
            }
        }
        JsonNode frees = data.get("frees");
        if (frees == null || frees.isNull() || !frees.has("st") || !frees.get("st").isNumber()) {
            return "frees.st is not a JSON number; GetFreeTimesView OnShow throws";
        }
        JsonNode props = data.get("props");
        if (props == null || !props.isArray() || props.isEmpty()) return "props missing";
        int lastScatter = 0;
        for (int pageIndex = 0; pageIndex < props.size(); pageIndex++) {
            JsonNode page = props.get(pageIndex);
            JsonNode prop = page.get("prop");
            JsonNode winArr = page.get("win_arr");
            if (prop == null || !prop.isArray() || prop.size() != 36) {
                return "page " + pageIndex + " prop is not 36 cells";
            }
            if (winArr == null || !winArr.isArray()) {
                return "page " + pageIndex + " win_arr missing; StartEliminate throws";
            }
            int[] boardValues = new int[HotpotBoard.SIZE];
            for (int cellIndex = 0; cellIndex < prop.size(); cellIndex++) {
                boardValues[cellIndex] = prop.get(cellIndex).asInt();
            }
            HotpotEvaluation evaluation;
            try {
                evaluation = HotpotResultUtil.evaluate(new HotpotBoard(boardValues));
            } catch (IllegalArgumentException invalidBoard) {
                return "page " + pageIndex + " has an invalid symbol matrix: " + invalidBoard.getMessage();
            }
            Map<Integer, HotpotWin> expectedWins = new HashMap<>();
            for (HotpotWin expected : evaluation.getWins()) expectedWins.put(expected.getSymbol(), expected);
            if (winArr.size() != expectedWins.size()) {
                return "page " + pageIndex + " win_arr does not match the 6x6 matrix: expected "
                        + expectedWins.size() + " winning symbols but response has " + winArr.size();
            }
            boolean last = pageIndex == props.size() - 1;
            int eliminated = 0;
            boolean[] removed = new boolean[HotpotBoard.SIZE];
            Set<Integer> winSymbols = new HashSet<>();
            for (JsonNode win : winArr) {
                if (win == null || !win.has("p") || !win.get("p").isNumber()) {
                    return "page " + pageIndex + " win_arr.p is not a number";
                }
                int symbol = win.get("p").asInt();
                HotpotWin expected = expectedWins.get(symbol);
                if (expected == null) {
                    return "page " + pageIndex + " win_arr.p=" + symbol + " is not a matrix win";
                }
                if (!win.has("n") || win.path("n").asInt() != expected.getCount()
                        || !win.has("odd") || win.path("odd").asInt() != expected.getOdd()) {
                    return "page " + pageIndex + " win_arr for symbol " + symbol
                            + " disagrees with the 6x6 matrix count/pay odd";
                }
                if (!winSymbols.add(symbol)) {
                    return "page " + pageIndex + " repeats win_arr.p=" + symbol
                            + "; original StartEliminate would schedule the same nodes twice";
                }
                int count = 0;
                for (int cellIndex = 0; cellIndex < prop.size(); cellIndex++) {
                    if (prop.get(cellIndex).asInt() == symbol) {
                        removed[cellIndex] = true;
                        count++;
                    }
                }
                if (count == 0) {
                    return "page " + pageIndex + " win_arr.p=" + symbol
                            + " is not on the board; StartEliminate creates no holes";
                }
                eliminated += count;
            }
            int scatter = 0;
            for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
                int columnScatter = 0;
                for (int row = 0; row < HotpotBoard.ROWS; row++) {
                    if (prop.get(col * HotpotBoard.ROWS + row).asInt() == HotpotResultUtil.SCATTER) {
                        columnScatter++;
                        scatter++;
                    }
                }
                if (columnScatter > HotpotResultUtil.MAX_SCATTER_PER_COLUMN) {
                    return "page " + pageIndex + " has " + columnScatter + " Scatter in API column " + col
                            + "; original client advances Scatter by column and can deadlock";
                }
            }
            boolean free = data.path("type").asInt(1) == 2;
            int scatterCap = free ? HotpotResultUtil.MAX_SCATTER_FREE_START_PAGE
                    : HotpotResultUtil.MAX_SCATTER_PAID_PAGE;
            if (scatter > scatterCap) return "page " + pageIndex + " exceeds Scatter page cap " + scatterCap;
            lastScatter = scatter;
            if (!last && eliminated == 0) {
                return "page " + pageIndex + " is not last and eliminate creates no holes; "
                        + "original client hangs in SCATTERMOVE/NEXTPAGE_ELIMINATE";
            }
            if (last && eliminated > 0) {
                return "last page " + pageIndex + " still has wins; original client requires a terminal no-win page";
            }
            if (!last) {
                String continuity = cascadeContinuityReason(pageIndex, prop, removed, props.get(pageIndex + 1));
                if (continuity != null) return continuity;
            }
        }
        boolean paid = data.path("type").asInt(1) == 1;
        if (paid && lastScatter >= HotpotResultUtil.PAID_SCATTER_TRIGGER && data.path("frees").path("st").asInt() <= 0) {
            return "last page has " + lastScatter + " Scatter but frees.st==0; GetFreeTimesView never opens and GAME_ENDED leaves the start button disabled";
        }
        if (!paid && lastScatter >= HotpotResultUtil.FREE_SCATTER_RETRIGGER) {
            return "last page has " + lastScatter + " Scatter during free; after GetFreeTimesView the original page enters ADDSCATTER/FreeSpinWon and remaining free spins stay stuck";
        }
        return null;
    }

    /** Mirrors Game1830ResultDisplay: API arrays are six column-major blocks, reversed for visual rows. */
    private static String cascadeContinuityReason(int pageIndex, JsonNode before, boolean[] removed, JsonNode nextPage) {
        JsonNode after = nextPage == null ? null : nextPage.get("prop");
        if (after == null || !after.isArray() || after.size() != HotpotBoard.SIZE) {
            return "page " + (pageIndex + 1) + " prop is not 36 cells";
        }
        for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
            int[] survivors = new int[HotpotBoard.ROWS];
            int kept = 0;
            int base = col * HotpotBoard.ROWS;
            for (int row = 0; row < HotpotBoard.ROWS; row++) {
                int index = base + row;
                if (!removed[index]) survivors[kept++] = before.get(index).asInt();
            }
            int fill = HotpotBoard.ROWS - kept;
            for (int row = 0; row < kept; row++) {
                int actual = after.get(base + fill + row).asInt();
                if (actual != survivors[row]) {
                    return "page " + pageIndex + " -> " + (pageIndex + 1) + " breaks 6x6 column " + col
                            + " gravity at API row " + (fill + row) + "; original client would retain symbol "
                            + survivors[row] + " but response has " + actual;
                }
            }
        }
        return null;
    }

    public static void requirePlayable(JsonNode data) {
        String reason = hangReason(data);
        if (reason != null) throw new IllegalStateException("original page would hang: " + reason);
    }
}
