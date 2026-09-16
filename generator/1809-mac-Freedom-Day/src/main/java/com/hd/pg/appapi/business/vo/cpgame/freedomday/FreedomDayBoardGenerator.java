package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.math.BigDecimal;
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
    private static final int BALL = FreedomDayResultUtil.BALL;
    public static final int SCATTER = 12;
    public static final int WILD = 13;
    public static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;

    /**
     * 1276 个自然付费起点第一页（30 prop + 4 trl）的逐格计数，合计 43384 格。
     * 排除购买/摆牌。这是抓包样本，不是原厂理论权重。
     */
    public static final int[] DEFAULT_NORMAL_WEIGHTS = {
            466, 4431, 4436, 4124, 4263, 4038, 4120, 4187, 4138, 4145, 4082, 754, 200};
    /** 2330 个免费 Spin 第一页逐格计数，合计 79220 格。不含连消续页。 */
    public static final int[] DEFAULT_FREE_WEIGHTS = {
            746, 7736, 7706, 7598, 7524, 7702, 7796, 7545, 7449, 7688, 7675, 1573, 482};

    private final Random random;
    private final int[] normalWeights;
    private final int[] freeWeights;

    public FreedomDayBoardGenerator() { this(new SecureRandom()); }

    public FreedomDayBoardGenerator(Random random) {
        this(random, DEFAULT_NORMAL_WEIGHTS, DEFAULT_FREE_WEIGHTS);
    }

    public FreedomDayBoardGenerator(Random random, int[] normalWeights, int[] freeWeights) {
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

    public FreedomDayBoard generate(boolean freeMode) {
        return generate(freeMode, false);
    }

    public FreedomDayBoard generate(boolean freeMode, boolean specialOpening) {
        int[] ordinary = freeMode ? freeWeights : normalWeights;
        int[] first = (!freeMode && specialOpening) ? specialEntryOpeningWeights(ordinary) : ordinary;
        int[] prop = new int[FreedomDayBoard.MAIN_SIZE];
        int[] trl = new int[FreedomDayBoard.TOP_SIZE];
        boolean[] seenTrigger = new boolean[FreedomDayBoard.REEL_COUNT];
        boolean topTrigger = false;
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            if (reel == 0 || reel == FreedomDayBoard.REEL_COUNT - 1) {
                for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                    int symbol = nextSymbol(seenTrigger[reel] ? ordinary : first, !seenTrigger[reel]);
                    if (symbol == SCATTER) seenTrigger[reel] = true;
                    prop[reel * FreedomDayBoard.ROW_COUNT + row] = symbol;
                }
            } else {
                fillStackedReel(prop, reel, first, ordinary, seenTrigger);
            }
        }
        for (int i = 0; i < trl.length; i++) {
            int reel = i + 1;
            boolean allowScatter = !seenTrigger[reel] && !topTrigger;
            int symbol = nextSymbol(seenTrigger[reel] ? ordinary : first, allowScatter);
            if (symbol == SCATTER) {
                seenTrigger[reel] = true;
                topTrigger = true;
            }
            trl[i] = symbol;
        }
        enforceTriggerCaps(prop, trl, freeMode, Set.of(), Set.of());
        FreedomDayBoard board = withMergedSymbols(prop, trl);
        if (FreedomDayResultUtil.countVisibleSymbol(board, SCATTER) >= 4) {
            board = stripWaysWinsKeepingScatter(board, freeMode);
        }
        return board;
    }

    private void fillStackedReel(int[] prop, int reel, int[] first, int[] ordinary, boolean[] seenTrigger) {
        int row = 0;
        while (row < FreedomDayBoard.ROW_COUNT) {
            int remaining = FreedomDayBoard.ROW_COUNT - row;
            int height = 1;
            if (remaining >= 2) {
                int roll = random.nextInt(100);
                if (roll < 30) height = 1;
                else if (roll < 72) height = 2;
                else if (roll < 90) height = Math.min(3, remaining);
                else height = Math.min(4, remaining);
            }
            int symbol = nextSymbol(seenTrigger[reel] ? ordinary : first, !seenTrigger[reel]);
            if (symbol == SCATTER) seenTrigger[reel] = true;
            if (!mergeable(symbol)) height = 1;
            for (int offset = 0; offset < height; offset++) {
                prop[reel * FreedomDayBoard.ROW_COUNT + row + offset] = symbol;
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
    public FreedomDayBoard generateIndependentLossCandidate(boolean freeMode) {
        FreedomDayBoard randomBoard = generate(freeMode);
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

        int thirdOffset = 2 * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            int index = thirdOffset + row;
            if (prop[index] == SCATTER) continue;
            prop[index] = safeThirdReelSymbol(prop[index], forbiddenOnThird, hasForbidden, freeMode);
        }
        // trl[1] 是第三列上方的额外格，也属于第三列 Ways 判定。
        if (trl[1] != SCATTER) {
            trl[1] = safeThirdReelSymbol(trl[1], forbiddenOnThird, hasForbidden, freeMode);
        }

        return withMergedSymbols(prop, trl);
    }

    /** 购买/触发入口盘：4 个可见 Scatter，同一列和顶轴各最多 1 个，且本盘不中奖。 */
    public FreedomDayBoard generateFeatureTrigger() {
        FreedomDayBoard board = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            board = placeFourTriggers();
            board = stripWaysWinsKeepingScatter(board, false);
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
            if (evaluation.getWins().isEmpty() && evaluation.getScatterCount() == 4) return board;
        }
        return stripWaysWinsKeepingScatter(board, false);
    }

    private FreedomDayBoard placeFourTriggers() {
        FreedomDayBoard board = generate(false);
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
        boolean placedTop = false;
        for (int i = 0; i < 4; i++) {
            int reel = reels[i];
            boolean onTop = !placedTop && reel >= 1 && reel <= 4 && random.nextBoolean();
            if (onTop) {
                trl[reel - 1] = SCATTER;
                placedTop = true;
            } else {
                prop[reel * FreedomDayBoard.ROW_COUNT + random.nextInt(FreedomDayBoard.ROW_COUNT)] = SCATTER;
            }
        }
        enforceTriggerCaps(prop, trl, false, Set.of(), Set.of());
        return withMergedSymbols(prop, trl);
    }

    public int nextSymbol(boolean freeMode) {
        return nextSymbol(freeMode ? freeWeights : normalWeights, true);
    }

    private int nextSymbol(int[] weights, boolean allowScatter) {
        int total = 0;
        for (int i = 0; i < weights.length; i++) {
            if (!allowScatter && i == SCATTER - 1) continue;
            total += weights[i];
        }
        if (total <= 0) return 2 + random.nextInt(10);
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            if (!allowScatter && i == SCATTER - 1) continue;
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

    private int safeThirdReelSymbol(int current, boolean[] forbidden, boolean hasForbidden, boolean freeMode) {
        if ((current >= 1 && current <= 11 && forbidden[current]) || (current == WILD && hasForbidden)) {
            int allowed = 0;
            boolean allowBall = (freeMode ? freeWeights : normalWeights)[BALL - 1] > 0;
            for (int symbol = 1; symbol <= 11; symbol++)
                if (!forbidden[symbol] && (symbol != BALL || allowBall)) allowed++;
            // 第一列最多5种自然符号，移除前两列Wild后，至少有6个普通符号可选。
            if (allowed == 0) return nextNonScatterNonWildSymbol(freeMode);
            int selected = random.nextInt(allowed);
            for (int symbol = 1; symbol <= 11; symbol++) {
                if (forbidden[symbol] || (symbol == BALL && !allowBall)) continue;
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
        // trl[0] 属于第二列。
        if (trl[0] == WILD) trl[0] = nextNonScatterNonWildSymbol(freeMode);
    }

    private void limitScatterCount(int[] prop, int[] trl, boolean freeMode) {
        int kept = 0;
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            if (!reelHasScatter(prop, trl, reel)) continue;
            if (++kept > 3) replaceAllScatterOnReel(prop, trl, reel, freeMode);
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
    public FreedomDayBoard cascade(FreedomDayBoard board, FreedomDayEvaluation evaluation, boolean freeMode) {
        Set<Integer> winMain = new HashSet<>();
        Set<Integer> removedTop = new HashSet<>();
        for (FreedomDayWin win : evaluation.getWins()) {
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
        int[] nextProp = new int[FreedomDayBoard.MAIN_SIZE];
        List<List<Integer>> nextGrids = new ArrayList<>();
        List<List<Integer>> nextGold = new ArrayList<>();
        List<List<Integer>> nextSilver = new ArrayList<>();
        Set<Integer> survivorCells = new HashSet<>();

        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            List<Integer> survivorIndexes = new ArrayList<>();
            for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                int index = reel * FreedomDayBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivorIndexes.add(index);
            }
            int fill = FreedomDayBoard.ROW_COUNT - survivorIndexes.size();
            int start = reel * FreedomDayBoard.ROW_COUNT + fill;
            boolean survivorScatter = false;
            for (int oldIndex : survivorIndexes) if (oldProp[oldIndex] == SCATTER) survivorScatter = true;
            int[] weights = freeMode ? freeWeights : normalWeights;
            for (int row = 0; row < fill; row++) {
                nextProp[reel * FreedomDayBoard.ROW_COUNT + row] = nextSymbol(weights, !survivorScatter);
            }
            for (int i = 0; i < survivorIndexes.size(); i++) {
                int oldIndex = survivorIndexes.get(i);
                int newIndex = start + i;
                nextProp[newIndex] = oldProp[oldIndex];
                survivorCells.add(newIndex);
            }
            for (List<Integer> oldGroup : board.getGrids()) {
                if (oldGroup.get(0) / FreedomDayBoard.ROW_COUNT != reel) continue;
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

        int[] oldTop = board.getTrl();
        int[] nextTop = new int[FreedomDayBoard.TOP_SIZE];
        List<Integer> topSurvivors = new ArrayList<>();
        for (int i = 0; i < oldTop.length; i++) if (!removedTop.contains(i)) topSurvivors.add(oldTop[i]);
        boolean topHasScatter = false;
        for (int symbol : topSurvivors) if (symbol == SCATTER) topHasScatter = true;
        int i = 0;
        for (; i < topSurvivors.size(); i++) nextTop[i] = topSurvivors.get(i);
        int[] weights = freeMode ? freeWeights : normalWeights;
        for (; i < nextTop.length; i++) {
            int reel = i + 1;
            boolean allowScatter = !topHasScatter && !reelHasMainScatter(nextProp, reel);
            int symbol = nextSymbol(weights, allowScatter);
            if (symbol == SCATTER) topHasScatter = true;
            nextTop[i] = symbol;
        }
        Set<Integer> protectedScatter = new HashSet<>();
        for (int index : survivorCells) if (nextProp[index] == SCATTER) protectedScatter.add(index);
        Set<Integer> protectedTop = new HashSet<>();
        for (int topIndex = 0; topIndex < topSurvivors.size(); topIndex++) {
            if (topSurvivors.get(topIndex) == SCATTER) protectedTop.add(topIndex);
        }
        enforceTriggerCaps(nextProp, nextTop, freeMode, protectedScatter, protectedTop);
        mergeReelRuns(nextProp, 1, 4, 0, FreedomDayBoard.ROW_COUNT, survivorCells, nextGrids, nextGold, nextSilver);
        return new FreedomDayBoard(nextProp, nextTop, nextGrids, nextGold, nextSilver);
    }

    private FreedomDayBoard withMergedSymbols(int[] prop, int[] trl) {
        List<List<Integer>> grids = new ArrayList<>();
        List<List<Integer>> gold = new ArrayList<>();
        List<List<Integer>> silver = new ArrayList<>();
        mergeReelRuns(prop, 1, 4, 0, FreedomDayBoard.ROW_COUNT, Set.of(), grids, gold, silver);
        return new FreedomDayBoard(prop, trl, grids, gold, silver);
    }

    private void mergeReelRuns(int[] prop, int reelFrom, int reelTo, int rowFrom, int rowTo,
                               Set<Integer> blocked, List<List<Integer>> grids,
                               List<List<Integer>> gold, List<List<Integer>> silver) {
        for (int reel = reelFrom; reel <= reelTo; reel++) {
            int start = reel * FreedomDayBoard.ROW_COUNT;
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
                    if (symbol != SCATTER) assignFrame(frozen, gold, silver);
                    row += height;
                } else {
                    row++;
                }
            }
        }
    }

    private void assignFrame(List<Integer> group, List<List<Integer>> gold, List<List<Integer>> silver) {
        int roll = random.nextInt(100);
        if (roll < 8) gold.add(group);
        else if (roll < 55) silver.add(group);
    }

    private static boolean mergeable(int symbol) {
        return FreedomDayGridRules.mergeable(symbol);
    }

    private FreedomDayBoard stripWaysWinsKeepingScatter(FreedomDayBoard board, boolean freeMode) {
        int[] prop = board.getProp();
        int[] trl = board.getTrl();
        FreedomDayBoard candidate = applyWaysBreak(prop, trl, freeMode);
        if (FreedomDayResultUtil.evaluate(candidate, BigDecimal.ONE, 1, 2).getWins().isEmpty()) return candidate;
        boolean[] firstMatches = matchingSymbolsOnReel(prop, trl, 0);
        boolean[] secondMatches = matchingSymbolsOnReel(prop, trl, 1);
        int safe = 11;
        for (int symbol = 2; symbol <= 11; symbol++) {
            if (!firstMatches[symbol] || !secondMatches[symbol]) {
                safe = symbol;
                break;
            }
        }
        int thirdOffset = 2 * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            if (prop[thirdOffset + row] != SCATTER) prop[thirdOffset + row] = safe;
        }
        if (trl[1] != SCATTER) trl[1] = safe;
        enforceTriggerCaps(prop, trl, freeMode, Set.of(), Set.of());
        return withMergedSymbols(prop, trl);
    }

    private FreedomDayBoard applyWaysBreak(int[] prop, int[] trl, boolean freeMode) {
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
            if (prop[index] == SCATTER) continue;
            prop[index] = safeThirdReelSymbol(prop[index], forbiddenOnThird, hasForbidden, freeMode);
        }
        if (trl[1] != SCATTER) {
            trl[1] = safeThirdReelSymbol(trl[1], forbiddenOnThird, hasForbidden, freeMode);
        }
        enforceTriggerCaps(prop, trl, freeMode, Set.of(), Set.of());
        return withMergedSymbols(prop, trl);
    }

    private void enforceTriggerCaps(int[] prop, int[] trl, boolean freeMode,
                                    Set<Integer> protectedMain, Set<Integer> protectedTop) {
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            List<int[]> runs = scatterRunsOnReel(prop, reel);
            if (runs.isEmpty()) continue;
            int[] keep = null;
            for (int[] run : runs) {
                if (runOverlapsProtected(reel, run, protectedMain)) {
                    keep = run;
                    break;
                }
            }
            if (keep == null) keep = runs.get(0);
            for (int[] run : runs) {
                if (run[0] == keep[0] && run[1] == keep[1]) continue;
                replaceScatterRun(prop, reel, run, freeMode, protectedMain);
            }
        }
        boolean topSeen = false;
        for (int i = 0; i < trl.length; i++) {
            if (trl[i] != SCATTER) continue;
            boolean protectedCell = protectedTop != null && protectedTop.contains(i);
            if (protectedCell) {
                topSeen = true;
                continue;
            }
            int reel = i + 1;
            if (topSeen || reelHasMainScatter(prop, reel)) {
                trl[i] = nextNonScatterNonWildSymbol(freeMode);
            } else {
                topSeen = true;
            }
        }
    }

    private static List<int[]> scatterRunsOnReel(int[] prop, int reel) {
        List<int[]> runs = new ArrayList<>();
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        int row = 0;
        while (row < FreedomDayBoard.ROW_COUNT) {
            if (prop[offset + row] != SCATTER) {
                row++;
                continue;
            }
            int start = row;
            while (row < FreedomDayBoard.ROW_COUNT && prop[offset + row] == SCATTER) row++;
            runs.add(new int[]{start, row});
        }
        return runs;
    }

    private static boolean runOverlapsProtected(int reel, int[] run, Set<Integer> protectedMain) {
        if (protectedMain == null || protectedMain.isEmpty()) return false;
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        for (int row = run[0]; row < run[1]; row++) {
            if (protectedMain.contains(offset + row)) return true;
        }
        return false;
    }

    private void replaceScatterRun(int[] prop, int reel, int[] run, boolean freeMode, Set<Integer> protectedMain) {
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        for (int row = run[0]; row < run[1]; row++) {
            int index = offset + row;
            if (protectedMain != null && protectedMain.contains(index)) continue;
            prop[offset + row] = nextNonScatterNonWildSymbol(freeMode);
        }
    }

    private void replaceAllScatterOnReel(int[] prop, int[] trl, int reel, boolean freeMode) {
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            if (prop[offset + row] == SCATTER) prop[offset + row] = nextNonScatterNonWildSymbol(freeMode);
        }
        if (reel >= 1 && reel <= 4 && trl[reel - 1] == SCATTER) {
            trl[reel - 1] = nextNonScatterNonWildSymbol(freeMode);
        }
    }

    private static boolean reelHasScatter(int[] prop, int[] trl, int reel) {
        if (reelHasMainScatter(prop, reel)) return true;
        return reel >= 1 && reel <= 4 && trl[reel - 1] == SCATTER;
    }

    private static boolean reelHasMainScatter(int[] prop, int reel) {
        int offset = reel * FreedomDayBoard.ROW_COUNT;
        for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
            if (prop[offset + row] == SCATTER) return true;
        }
        return false;
    }

    private int nextTransformSymbol(boolean freeMode) {
        int symbol;
        do {
            symbol = nextSymbol(freeMode);
        } while (symbol == 1 || symbol == SCATTER);
        return symbol;
    }
}
