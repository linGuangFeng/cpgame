package com.hd.pg.appapi.business.vo.cpgame.edmmania;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 独立的 EDM Mania 牌面随机生成器。本类只负责出牌，不包含任何判奖或金额逻辑。
 * Random 可注入，便于其他项目复用和固定种子测试。
 */
public final class EdmManiaBoardGenerator {
    public static final int SCATTER = 12;
    public static final int WILD = 13;
    public static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;

    /**
     * 389 个付费起点第一页（30 prop + 4 trl）逐格计数，合计 13226 格。
     * 来源 fixtures/2010-EDM-Mania/spin，不含免费续局。样本频率，不是原厂 RTP。
     */
    public static final int[] DEFAULT_NORMAL_WEIGHTS = {
            109, 1334, 1379, 1294, 1216, 1184, 1308, 1248, 1276, 1265, 1264, 275, 74};
    /**
     * 免费入口样本不足（仅 11 个 type=2 终局第一页）。
     * 使用与付费起点相同的逐格计数，避免用 n=11 扭曲稀有符号。
     */
    public static final int[] DEFAULT_FREE_WEIGHTS = {
            109, 1334, 1379, 1294, 1216, 1184, 1308, 1248, 1276, 1265, 1264, 275, 74};

    private final Random random;
    private final int[] normalWeights;
    private final int[] freeWeights;

    public EdmManiaBoardGenerator() { this(new SecureRandom()); }

    public EdmManiaBoardGenerator(Random random) {
        this(random, DEFAULT_NORMAL_WEIGHTS, DEFAULT_FREE_WEIGHTS);
    }

    public EdmManiaBoardGenerator(Random random, int[] normalWeights, int[] freeWeights) {
        if (random == null) throw new IllegalArgumentException("random is required");
        this.random = random;
        this.normalWeights = validatedWeights(normalWeights, "normal");
        this.freeWeights = validatedWeights(freeWeights, "Mary");
    }

    public static int[] defaultNormalWeights() { return DEFAULT_NORMAL_WEIGHTS.clone(); }
    public static int[] defaultFreeWeights() { return DEFAULT_FREE_WEIGHTS.clone(); }

    /** 特殊入口只放大付费首局 Scatter 概率 *10。连消/免费仍走原权重。 */
    public static int[] specialEntryOpeningWeights(int[] ordinary) {
        if (ordinary == null || ordinary.length != 13) {
            throw new IllegalArgumentException("ordinary opening weights must contain IDs 1..13");
        }
        int[] boosted = ordinary.clone();
        boosted[SCATTER - 1] = Math.multiplyExact(ordinary[SCATTER - 1], SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        return boosted;
    }

    public EdmManiaBoard generate(boolean freeMode) {
        return generate(freeMode, false);
    }

    public EdmManiaBoard generate(boolean freeMode, boolean specialOpening) {
        int[] weights = freeMode ? freeWeights
                : (specialOpening ? specialEntryOpeningWeights(normalWeights) : normalWeights);
        int[] prop = new int[EdmManiaBoard.MAIN_SIZE];
        int[] trl = new int[EdmManiaBoard.TOP_SIZE];
        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            if (reel == 0 || reel == EdmManiaBoard.REEL_COUNT - 1) {
                for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                    prop[reel * EdmManiaBoard.ROW_COUNT + row] = nextSymbol(weights);
                }
            } else {
                fillStackedReel(prop, reel, weights);
            }
        }
        for (int i = 0; i < trl.length; i++) trl[i] = nextSymbol(weights);
        enforceObservedCaps(prop, trl, freeMode);
        return withMergedSymbols(prop, trl);
    }

    private void fillStackedReel(int[] prop, int reel, int[] weights) {
        int row = 0;
        while (row < EdmManiaBoard.ROW_COUNT) {
            int remaining = EdmManiaBoard.ROW_COUNT - row;
            int height = 1;
            if (remaining >= 2) {
                int roll = random.nextInt(100);
                if (roll < 30) height = 1;
                else if (roll < 72) height = 2;
                else if (roll < 90) height = Math.min(3, remaining);
                else height = Math.min(4, remaining);
            }
            int symbol = nextSymbol(weights);
            if (!mergeable(symbol)) height = 1;
            for (int offset = 0; offset < height; offset++) {
                prop[reel * EdmManiaBoard.ROW_COUNT + row + offset] = symbol;
            }
            row += height;
        }
    }

    /**
     * 构造一张独立无奖候选盘。
     * Ways 只要求符号在相邻列出现，不要求处于同一行，因此先随机生成整盘，
     * 再把第三列中可同时被第一、二列匹配的符号改掉。Wild 可匹配 1~11。
     * 同时把 Scatter 控制在 3 个以内，避免无奖盘触发免费模式。
     */
    public EdmManiaBoard generateIndependentLossCandidate(boolean freeMode) {
        EdmManiaBoard randomBoard = generate(freeMode);
        int[] prop = randomBoard.getProp();
        int[] trl = randomBoard.getTrl();

        // 先处理特殊触发符号，之后再以最终的第一、二列计算第三列禁用集合。
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

        int thirdOffset = 2 * EdmManiaBoard.ROW_COUNT;
        for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
            int index = thirdOffset + row;
            prop[index] = safeThirdReelSymbol(prop[index], forbiddenOnThird, hasForbidden, freeMode);
        }
        // trl[1] 是第三列上方的额外格，也属于第三列 Ways 判定。
        trl[1] = safeThirdReelSymbol(trl[1], forbiddenOnThird, hasForbidden, freeMode);

        return withMergedSymbols(prop, trl);
    }

    /** 购买模式的入口盘：先随机出牌，再随机放置恰好 4 个 Scatter。 */
    public EdmManiaBoard generateFeatureTrigger() {
        EdmManiaBoard board = generate(false);
        int[] prop = board.getProp();
        int[] trl = board.getTrl();
        for (int i = 0; i < prop.length; i++) if (prop[i] == SCATTER) prop[i] = nextNonScatterNonWildSymbol(false);
        for (int i = 0; i < trl.length; i++) if (trl[i] == SCATTER) trl[i] = nextNonScatterNonWildSymbol(false);
        int[] reels = {0, 1, 2, 3, 4, 5};
        for (int i = reels.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = reels[i];
            reels[i] = reels[j];
            reels[j] = tmp;
        }
        for (int i = 0; i < 4; i++) {
            int reel = reels[i];
            int row = random.nextInt(EdmManiaBoard.ROW_COUNT);
            prop[reel * EdmManiaBoard.ROW_COUNT + row] = SCATTER;
        }
        return withMergedSymbols(prop, trl);
    }

    public int nextSymbol(boolean freeMode) {
        return nextSymbol(freeMode ? freeWeights : normalWeights);
    }

    private int nextSymbol(int[] weights) {
        int total = 0;
        for (int weight : weights) total += weight;
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            value -= weights[i];
            if (value < 0) return i + 1;
        }
        return 11;
    }

    private static int[] validatedWeights(int[] values, String mode) {
        if (values == null || values.length != 13) {
            throw new IllegalArgumentException(mode + " symbol weights must contain IDs 1..13");
        }
        int total = 0;
        int[] copy = values.clone();
        for (int value : copy) {
            if (value < 0) throw new IllegalArgumentException(mode + " symbol weight must be >= 0");
            total = Math.addExact(total, value);
        }
        if (total <= 0) throw new IllegalArgumentException(mode + " symbol weight total must be > 0");
        return copy;
    }

    private boolean[] matchingSymbolsOnReel(int[] prop, int[] trl, int reel) {
        boolean[] matches = new boolean[12];
        boolean wild = false;
        int offset = reel * EdmManiaBoard.ROW_COUNT;
        for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
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

    private int safeThirdReelSymbol(int current, boolean[] forbidden, boolean hasForbidden, boolean freeMode) {
        if ((current >= 1 && current <= 11 && forbidden[current]) || (current == WILD && hasForbidden)) {
            int allowed = 0;
            for (int symbol = 1; symbol <= 11; symbol++) if (!forbidden[symbol]) allowed++;
            // 第一列最多5种自然符号，移除前两列Wild后，至少有6个普通符号可选。
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
            int offset = reel * EdmManiaBoard.ROW_COUNT;
            for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                int index = offset + row;
                if (prop[index] == WILD) prop[index] = nextNonScatterNonWildSymbol(freeMode);
            }
        }
        // trl[0] 属于第二列。
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
        int guard = 0;
        do { symbol = nextSymbol(freeMode); } while ((symbol == SCATTER || symbol == WILD) && ++guard < 32);
        if (symbol == SCATTER || symbol == WILD) return 2 + random.nextInt(10);
        return symbol;
    }

    /**
     * 根据判奖位置生成下一消除页：竖盘幸存符号向下落，横排幸存符号向左移，空位再随机补牌。
     * 普通中奖合并组整组消除；金/银框中奖组按原协议变形（银变金、金去框并换符号）后随重力下落。
     */
    public EdmManiaBoard cascade(EdmManiaBoard board, EdmManiaEvaluation evaluation, boolean freeMode) {
        Set<Integer> winMain = new HashSet<>();
        Set<Integer> removedTop = new HashSet<>();
        for (EdmManiaWin win : evaluation.getWins()) {
            winMain.addAll(win.getMainPositions());
            removedTop.addAll(win.getTopPositions());
        }

        Set<Integer> transformedMain = new HashSet<>();
        for (List<Integer> frame : board.getGoldFrames()) {
            if (frame.stream().anyMatch(winMain::contains)) transformedMain.addAll(frame);
        }
        for (List<Integer> frame : board.getSilverFrames()) {
            if (frame.stream().anyMatch(winMain::contains)) transformedMain.addAll(frame);
        }

        Set<Integer> removedMain = new HashSet<>(winMain);
        for (List<Integer> group : board.getGrids()) {
            if (group.stream().anyMatch(winMain::contains) && !transformedMain.contains(group.get(0))) {
                removedMain.addAll(group);
            }
        }
        removedMain.removeAll(transformedMain);

        int[] oldProp = board.getProp();
        int[] nextProp = new int[EdmManiaBoard.MAIN_SIZE];
        List<List<Integer>> nextGrids = new ArrayList<>();
        List<List<Integer>> nextGold = new ArrayList<>();
        List<List<Integer>> nextSilver = new ArrayList<>();
        Set<Integer> survivorCells = new HashSet<>();

        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            List<Integer> survivorIndexes = new ArrayList<>();
            for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                int index = reel * EdmManiaBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivorIndexes.add(index);
            }
            int fill = EdmManiaBoard.ROW_COUNT - survivorIndexes.size();
            int start = reel * EdmManiaBoard.ROW_COUNT + fill;
            for (int row = 0; row < fill; row++) {
                nextProp[reel * EdmManiaBoard.ROW_COUNT + row] = nextSymbol(freeMode);
            }
            for (int i = 0; i < survivorIndexes.size(); i++) {
                int oldIndex = survivorIndexes.get(i);
                int newIndex = start + i;
                nextProp[newIndex] = oldProp[oldIndex];
                survivorCells.add(newIndex);
            }
            for (List<Integer> oldGroup : board.getGrids()) {
                if (oldGroup.get(0) / EdmManiaBoard.ROW_COUNT != reel) continue;
                if (oldGroup.stream().anyMatch(removedMain::contains)) continue;
                List<Integer> mapped = new ArrayList<>(oldGroup.size());
                for (int oldIndex : oldGroup) {
                    mapped.add(start + survivorIndexes.indexOf(oldIndex));
                }
                List<Integer> frozen = List.copyOf(mapped);
                boolean transformed = oldGroup.stream().anyMatch(transformedMain::contains);
                if (transformed) {
                    int replacement = nextTransformSymbol(freeMode);
                    for (int index : frozen) nextProp[index] = replacement;
                    nextGrids.add(frozen);
                    if (board.getSilverFrames().contains(oldGroup)) nextGold.add(frozen);
                    // gold 中奖后保留合并组，但去掉金框，与原站相邻页一致
                } else {
                    nextGrids.add(frozen);
                    if (board.getGoldFrames().contains(oldGroup)) nextGold.add(frozen);
                    if (board.getSilverFrames().contains(oldGroup)) nextSilver.add(frozen);
                }
            }
        }

        mergeReelRuns(nextProp, 1, 4, 0, EdmManiaBoard.ROW_COUNT, survivorCells, nextGrids, nextGold, nextSilver);

        int[] oldTop = board.getTrl();
        int[] nextTop = new int[EdmManiaBoard.TOP_SIZE];
        List<Integer> topSurvivors = new ArrayList<>();
        for (int i = 0; i < oldTop.length; i++) if (!removedTop.contains(i)) topSurvivors.add(oldTop[i]);
        int i = 0;
        for (; i < topSurvivors.size(); i++) nextTop[i] = topSurvivors.get(i);
        for (; i < nextTop.length; i++) nextTop[i] = nextSymbol(freeMode);
        return new EdmManiaBoard(nextProp, nextTop, nextGrids, nextGold, nextSilver);
    }

    private EdmManiaBoard withMergedSymbols(int[] prop, int[] trl) {
        List<List<Integer>> grids = new ArrayList<>();
        List<List<Integer>> gold = new ArrayList<>();
        List<List<Integer>> silver = new ArrayList<>();
        mergeReelRuns(prop, 1, 4, 0, EdmManiaBoard.ROW_COUNT, Set.of(), grids, gold, silver);
        return new EdmManiaBoard(prop, trl, grids, gold, silver);
    }

    private void mergeReelRuns(int[] prop, int reelFrom, int reelTo, int rowFrom, int rowTo,
                               Set<Integer> blocked, List<List<Integer>> grids,
                               List<List<Integer>> gold, List<List<Integer>> silver) {
        for (int reel = reelFrom; reel <= reelTo; reel++) {
            int start = reel * EdmManiaBoard.ROW_COUNT;
            int row = rowFrom;
            while (row < rowTo) {
                int index = start + row;
                if (blocked.contains(index)) {
                    row++;
                    continue;
                }
                int symbol = prop[index];
                int run = 1;
                while (row + run < rowTo) {
                    int next = start + row + run;
                    if (blocked.contains(next) || prop[next] != symbol) break;
                    run++;
                }
                if (mergeable(symbol) && run >= 2) {
                    int height = Math.min(run, 4);
                    List<Integer> group = new ArrayList<>(height);
                    for (int offset = 0; offset < height; offset++) group.add(start + row + offset);
                    List<Integer> frozen = List.copyOf(group);
                    grids.add(frozen);
                    assignFrame(frozen, gold, silver);
                    row += height;
                } else {
                    row++;
                }
            }
        }
    }

    private void assignFrame(List<Integer> group, List<List<Integer>> gold, List<List<Integer>> silver) {
        // 抓包 1989 个开局合并组：金框 0、银框 1161（58.4%）、无框 828。金框只来自银框中奖变形。
        if (random.nextInt(100) < 58) silver.add(group);
    }

    /** 抓包上限：边轴无 Wild、边轴 Scatter≤1、内轴 Scatter≤3、整盘 Scatter≤5、整盘 Wild≤4。 */
    private void enforceObservedCaps(int[] prop, int[] trl, boolean freeMode) {
        for (int reel : new int[]{0, EdmManiaBoard.REEL_COUNT - 1}) {
            int offset = reel * EdmManiaBoard.ROW_COUNT;
            for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                if (prop[offset + row] == WILD) prop[offset + row] = nextNonScatterNonWildSymbol(freeMode);
            }
        }
        int[] reelScatter = new int[EdmManiaBoard.REEL_COUNT];
        int[] reelWild = new int[EdmManiaBoard.REEL_COUNT];
        int scatter = 0, wild = 0;
        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            int offset = reel * EdmManiaBoard.ROW_COUNT;
            for (int row = 0; row < EdmManiaBoard.ROW_COUNT; row++) {
                int symbol = prop[offset + row];
                if (symbol == SCATTER) { reelScatter[reel]++; scatter++; }
                if (symbol == WILD) { reelWild[reel]++; wild++; }
            }
            if (reel >= 1 && reel <= 4) {
                int symbol = trl[reel - 1];
                if (symbol == SCATTER) { reelScatter[reel]++; scatter++; }
                if (symbol == WILD) { reelWild[reel]++; wild++; }
            }
        }
        for (int reel = 0; reel < EdmManiaBoard.REEL_COUNT; reel++) {
            int cap = (reel == 0 || reel == EdmManiaBoard.REEL_COUNT - 1) ? 1 : 3;
            while (reelScatter[reel] > cap) {
                replaceOneOnReel(prop, trl, reel, SCATTER, nextNonScatterNonWildSymbol(freeMode));
                reelScatter[reel]--;
                scatter--;
            }
            while (reelWild[reel] > 4) {
                replaceOneOnReel(prop, trl, reel, WILD, nextNonScatterNonWildSymbol(freeMode));
                reelWild[reel]--;
                wild--;
            }
        }
        while (scatter > 5) {
            if (replaceFirst(prop, trl, SCATTER, nextNonScatterNonWildSymbol(freeMode))) scatter--;
            else break;
        }
        while (wild > 4) {
            if (replaceFirst(prop, trl, WILD, nextNonScatterNonWildSymbol(freeMode))) wild--;
            else break;
        }
    }

    private static void replaceOneOnReel(int[] prop, int[] trl, int reel, int from, int to) {
        int offset = reel * EdmManiaBoard.ROW_COUNT;
        for (int row = EdmManiaBoard.ROW_COUNT - 1; row >= 0; row--) {
            if (prop[offset + row] == from) { prop[offset + row] = to; return; }
        }
        if (reel >= 1 && reel <= 4 && trl[reel - 1] == from) trl[reel - 1] = to;
    }

    private static boolean replaceFirst(int[] prop, int[] trl, int from, int to) {
        for (int i = 0; i < prop.length; i++) if (prop[i] == from) { prop[i] = to; return true; }
        for (int i = 0; i < trl.length; i++) if (trl[i] == from) { trl[i] = to; return true; }
        return false;
    }

    private static boolean mergeable(int symbol) {
        return EdmManiaGridRules.mergeable(symbol);
    }

    private int nextTransformSymbol(boolean freeMode) {
        int symbol;
        do {
            symbol = nextSymbol(freeMode);
        } while (symbol == 1 || symbol == SCATTER);
        return symbol;
    }
}
