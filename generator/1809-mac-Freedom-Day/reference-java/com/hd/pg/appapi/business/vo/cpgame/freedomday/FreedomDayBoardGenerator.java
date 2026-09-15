package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 独立的 Freedom Day 牌面随机生成器。本类只负责出牌，不包含任何判奖或金额逻辑。
 * Random 可注入，便于其他项目复用和固定种子测试。
 */
public final class FreedomDayBoardGenerator {
    public static final int SCATTER = 12;
    public static final int WILD = 13;

    private static final int[] NORMAL_WEIGHTS = {
            466, 4431, 4436, 4124, 4263, 4038, 4120, 4187, 4138, 4145, 4082, 754, 200};
    private static final int[] FREE_WEIGHTS = {
            746, 7736, 7706, 7598, 7524, 7702, 7796, 7545, 7449, 7688, 7675, 1573, 482};

    private final Random random;

    public FreedomDayBoardGenerator() { this(new SecureRandom()); }

    public FreedomDayBoardGenerator(Random random) {
        if (random == null) throw new IllegalArgumentException("random is required");
        this.random = random;
    }

    public FreedomDayBoard generate(boolean freeMode) {
        int[] prop = new int[FreedomDayBoard.MAIN_SIZE];
        int[] trl = new int[FreedomDayBoard.TOP_SIZE];
        for (int i = 0; i < prop.length; i++) prop[i] = nextSymbol(freeMode);
        for (int i = 0; i < trl.length; i++) trl[i] = nextSymbol(freeMode);
        return new FreedomDayBoard(prop, trl);
    }

    /**
     * 构造一张独立无奖候选盘。Ways 按列内任意位置匹配，不要求同一行。
     * 第三列不能出现同时可被第一、二列匹配的符号；Wild 可匹配 1~11。
     * Scatter 最多保留3个，防止无奖盘触发免费模式。
     */
    public FreedomDayBoard generateIndependentLossCandidate(boolean freeMode) {
        FreedomDayBoard randomBoard = generate(freeMode);
        int[] prop = randomBoard.getProp();
        int[] trl = randomBoard.getTrl();
        limitScatterCount(prop, trl, freeMode);
        replaceWildsOnFirstTwoReels(prop, trl, freeMode);

        boolean[] firstMatches = matchingSymbolsOnReel(prop, trl, 0);
        boolean[] secondMatches = matchingSymbolsOnReel(prop, trl, 1);
        boolean[] forbiddenOnThird = new boolean[12];
        boolean hasForbidden = false;
        for (int symbol = 1; symbol <= 11; symbol++) {
            forbiddenOnThird[symbol] = firstMatches[symbol] && secondMatches[symbol];
            hasForbidden |= forbiddenOnThird[symbol];
        }
        int thirdOffset = 2 * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            int index = thirdOffset + row;
            prop[index] = safeThirdReelSymbol(prop[index], forbiddenOnThird, hasForbidden);
        }
        trl[1] = safeThirdReelSymbol(trl[1], forbiddenOnThird, hasForbidden);
        return new FreedomDayBoard(prop, trl);
    }

    /** 购买模式的入口盘：先随机出牌，再随机放置恰好 4 个 Scatter。 */
    public FreedomDayBoard generateFeatureTrigger() {
        FreedomDayBoard board = generate(false);
        int[] prop = board.getProp();
        int[] trl = board.getTrl();
        // 清除自然 Scatter，保证免费次数可预测，再在全部 34 格中无放回选 4 格。
        for (int i = 0; i < prop.length; i++) if (prop[i] == SCATTER) prop[i] = 2 + random.nextInt(10);
        for (int i = 0; i < trl.length; i++) if (trl[i] == SCATTER) trl[i] = 2 + random.nextInt(10);
        boolean[] used = new boolean[FreedomDayBoard.MAIN_SIZE + FreedomDayBoard.TOP_SIZE];
        for (int count = 0; count < 4; count++) {
            int position;
            do { position = random.nextInt(used.length); } while (used[position]);
            used[position] = true;
            if (position < prop.length) prop[position] = SCATTER;
            else trl[position - prop.length] = SCATTER;
        }
        return new FreedomDayBoard(prop, trl);
    }

    public int nextSymbol(boolean freeMode) {
        int[] weights = freeMode ? FREE_WEIGHTS : NORMAL_WEIGHTS;
        int total = 0;
        for (int weight : weights) total += weight;
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            value -= weights[i];
            if (value < 0) return i + 1;
        }
        return 11;
    }

    private boolean[] matchingSymbolsOnReel(int[] prop, int[] trl, int reel) {
        boolean[] matches = new boolean[12];
        boolean wild = false;
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            int symbol = prop[offset + row];
            if (symbol == WILD) wild = true;
            else if (symbol >= 1 && symbol <= 11) matches[symbol] = true;
        }
        if (reel >= 1 && reel <= 4) {
            int symbol = trl[reel - 1];
            if (symbol == WILD) wild = true;
            else if (symbol >= 1 && symbol <= 11) matches[symbol] = true;
        }
        if (wild) for (int symbol = 1; symbol <= 11; symbol++) matches[symbol] = true;
        return matches;
    }

    private int safeThirdReelSymbol(int current, boolean[] forbidden, boolean hasForbidden) {
        if ((current >= 1 && current <= 11 && forbidden[current]) || (current == WILD && hasForbidden)) {
            int allowed = 0;
            for (int symbol = 1; symbol <= 11; symbol++) if (!forbidden[symbol]) allowed++;
            if (allowed == 0) return SCATTER;
            int selected = random.nextInt(allowed);
            for (int symbol = 1; symbol <= 11; symbol++) {
                if (forbidden[symbol]) continue;
                if (selected-- == 0) return symbol;
            }
        }
        return current;
    }

    private void replaceWildsOnFirstTwoReels(int[] prop, int[] trl, boolean freeMode) {
        for (int reel = 0; reel <= 1; reel++) {
            int offset = reel * FreedomDayBoard.ROW_COUNT;
            for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                int index = offset + row;
                if (prop[index] == WILD) prop[index] = nextNonScatterNonWildSymbol(freeMode);
            }
        }
        if (trl[0] == WILD) trl[0] = nextNonScatterNonWildSymbol(freeMode);
    }

    private void limitScatterCount(int[] prop, int[] trl, boolean freeMode) {
        int kept = 0;
        for (int i = 0; i < prop.length; i++) {
            if (prop[i] != SCATTER) continue;
            if (++kept > 3) prop[i] = nextNonScatterNonWildSymbol(freeMode);
        }
        for (int i = 0; i < trl.length; i++) {
            if (trl[i] != SCATTER) continue;
            if (++kept > 3) trl[i] = nextNonScatterNonWildSymbol(freeMode);
        }
    }

    private int nextNonScatterNonWildSymbol(boolean freeMode) {
        int symbol;
        do { symbol = nextSymbol(freeMode); } while (symbol == SCATTER || symbol == WILD);
        return symbol;
    }

    /**
     * 根据判奖位置生成下一消除页：竖盘幸存符号向下落，横排幸存符号向左移，空位再随机补牌。
     * 判奖仍由 ResultUtil 完成，本方法只做牌面状态迁移。
     */
    public FreedomDayBoard cascade(FreedomDayBoard board, FreedomDayEvaluation evaluation, boolean freeMode) {
        Set<Integer> removedMain = new HashSet<>();
        Set<Integer> removedTop = new HashSet<>();
        for (FreedomDayWin win : evaluation.getWins()) {
            removedMain.addAll(win.getMainPositions());
            removedTop.addAll(win.getTopPositions());
        }

        int[] oldProp = board.getProp();
        int[] nextProp = new int[FreedomDayBoard.MAIN_SIZE];
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            List<Integer> survivors = new ArrayList<>();
            for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                int index = reel * FreedomDayBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivors.add(oldProp[index]);
            }
            int fill = FreedomDayBoard.ROW_COUNT - survivors.size();
            for (int row = 0; row < fill; row++) nextProp[reel * FreedomDayBoard.ROW_COUNT + row] = nextSymbol(freeMode);
            for (int i = 0; i < survivors.size(); i++) {
                nextProp[reel * FreedomDayBoard.ROW_COUNT + fill + i] = survivors.get(i);
            }
        }

        int[] oldTop = board.getTrl();
        int[] nextTop = new int[FreedomDayBoard.TOP_SIZE];
        List<Integer> topSurvivors = new ArrayList<>();
        for (int i = 0; i < oldTop.length; i++) if (!removedTop.contains(i)) topSurvivors.add(oldTop[i]);
        int i = 0;
        for (; i < topSurvivors.size(); i++) nextTop[i] = topSurvivors.get(i);
        for (; i < nextTop.length; i++) nextTop[i] = nextSymbol(freeMode);
        return new FreedomDayBoard(nextProp, nextTop);
    }
}
