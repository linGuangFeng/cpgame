package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.Cell;
import com.cpgame.saci.generator.model.ResultAnalysis;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.SpinStep;
import com.cpgame.saci.generator.model.StepFact;
import com.cpgame.saci.generator.model.SymbolWin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 独立开奖复核。Ways 与金额只从 rskl / spl / bl / bs 计算，不读取 RoundFactory 的中间结果。
 */
public final class ResultUtil {
    private ResultUtil() {}

    public static List<Cell> parseBoard(List<String> rskl) {
        if (rskl == null || rskl.size() != GameRules.BOARD_SIZE) {
            throw new IllegalArgumentException("rskl 必须恰好 15 格");
        }
        List<Cell> cells = new ArrayList<>(GameRules.BOARD_SIZE);
        for (String raw : rskl) cells.add(Cell.parse(raw));
        return cells;
    }

    public static void validateBoard(List<String> rskl, boolean paidStart) {
        List<Cell> cells = parseBoard(rskl);
        int scatter = 0;
        int wild = 0;
        int splits = 0;
        int[] scatterReel = new int[GameRules.REELS];
        int[] wildReel = new int[GameRules.REELS];
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (!GameRules.ORDINARY.contains(cell.symbol()) && !GameRules.WILD.equals(cell.symbol())
                    && !GameRules.SCATTER.equals(cell.symbol())) {
                throw new IllegalArgumentException("未知符号: " + cell.symbol());
            }
            if (cell.copies() < 1 || cell.copies() > 2) throw new IllegalArgumentException("符号份数超过抓包上限");
            if (cell.copies() == 2) splits++;
            int reel = i / GameRules.ROWS;
            if (cell.scatter()) {
                scatter++;
                scatterReel[reel]++;
            }
            if (cell.wild()) {
                wild++;
                wildReel[reel]++;
            }
        }
        if (paidStart) {
            if (scatter == 2 || scatter > GameRules.PAID_SCATTER_BOARD_MAX) {
                throw new IllegalArgumentException("付费起点 Scatter 数量不在抓包集合 {0,1,3}");
            }
            if (wild > GameRules.PAID_WILD_BOARD_MAX) throw new IllegalArgumentException("付费起点 Wild 超过抓包上限");
            if (splits > GameRules.PAID_SPLIT_BOARD_MAX) throw new IllegalArgumentException("付费起点分裂格超过抓包上限");
            for (int n : scatterReel) {
                if (n > GameRules.PAID_SCATTER_PER_REEL_MAX) throw new IllegalArgumentException("单轴 Scatter 超过抓包上限");
            }
            for (int n : wildReel) {
                if (n > GameRules.PAID_WILD_PER_REEL_MAX) throw new IllegalArgumentException("单轴 Wild 超过抓包上限");
            }
        }
    }

    public static int scatterCount(List<String> rskl) {
        int n = 0;
        for (Cell cell : parseBoard(rskl)) if (cell.scatter()) n++;
        return n;
    }

    public static List<SymbolWin> evaluateWays(List<String> rskl) {
        List<Cell> cells = parseBoard(rskl);
        List<SymbolWin> wins = new ArrayList<>();
        for (String target : List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")) {
            List<List<int[]>> reelMatches = new ArrayList<>();
            for (int reel = 0; reel < GameRules.REELS; reel++) {
                List<int[]> match = new ArrayList<>();
                for (int row = 0; row < GameRules.ROWS; row++) {
                    Cell cell = cells.get(GameRules.indexOf(reel, row));
                    boolean hit = GameRules.WILD.equals(target)
                            ? cell.wild()
                            : target.equals(cell.symbol()) || cell.wild();
                    if (hit && !cell.scatter()) match.add(new int[]{reel, row, GameRules.indexOf(reel, row)});
                }
                if (match.isEmpty()) break;
                reelMatches.add(match);
            }
            int reels = reelMatches.size();
            int minReels = GameRules.WILD.equals(target) ? 5 : 3;
            if (reels < minReels) continue;
            if (!GameRules.WILD.equals(target)) {
                boolean natural = false;
                for (List<int[]> match : reelMatches) {
                    for (int[] pos : match) {
                        if (target.equals(cells.get(pos[2]).symbol())) {
                            natural = true;
                            break;
                        }
                    }
                    if (natural) break;
                }
                if (!natural) continue;
            }
            Integer payout = GameRules.PAYTABLE.getOrDefault(target, Map.of()).get(reels);
            if (payout == null) continue;
            int copyWays = 1;
            int chestSum = 0;
            List<Integer> coords = new ArrayList<>();
            for (List<int[]> match : reelMatches) {
                int copies = 0;
                for (int[] pos : match) {
                    Cell cell = cells.get(pos[2]);
                    copies += cell.copies();
                    if (cell.multiplier() > 1) chestSum += cell.copies() * cell.multiplier();
                    coords.add(GameRules.coord(pos[0], pos[1]));
                }
                copyWays *= copies;
            }
            int cellFactor = chestSum > 0 ? chestSum : 1;
            coords.sort(Comparator.naturalOrder());
            wins.add(new SymbolWin(target, reels, copyWays, cellFactor, payout, coords));
        }
        return wins;
    }

    public static List<Integer> expectedWmkl(List<String> rskl) {
        TreeSet<Integer> coords = new TreeSet<>();
        for (SymbolWin win : evaluateWays(rskl)) coords.addAll(win.coords());
        return List.copyOf(coords);
    }

    public static BigDecimal expectedWa(List<String> rskl, int bl, BigDecimal bs) {
        BigDecimal unit = bs.multiply(BigDecimal.valueOf(bl));
        BigDecimal total = BigDecimal.ZERO;
        for (SymbolWin win : evaluateWays(rskl)) {
            total = total.add(unit.multiply(BigDecimal.valueOf(win.waysContribution())));
        }
        return scaleMoney(total);
    }

    public static void verifyStep(SpinStep step) {
        boolean paidStart = step.ba().signum() > 0;
        validateBoard(step.rskl(), paidStart);
        List<Integer> expectedCoords = expectedWmkl(step.rskl());
        if (!expectedCoords.equals(step.wmkl())) {
            throw new IllegalArgumentException("独立 wmkl 复核失败 expected=" + expectedCoords + " actual=" + step.wmkl());
        }
        BigDecimal expected = expectedWa(step.rskl(), step.bl(), step.bs());
        if (expected.compareTo(step.wa()) != 0) {
            throw new IllegalArgumentException("独立 wa 复核失败 expected=" + expected + " actual=" + step.wa());
        }
        if (paidStart) {
            if (step.ba().compareTo(GameRules.betAmount(step.bl(), step.bs())) != 0) {
                throw new IllegalArgumentException("付费 ba 必须等于 bl*bs*20");
            }
            if (step.ss() == 1 && step.fsn() == 0 && step.rsn() == 0 && step.wa().signum() == 0
                    && step.rwa().signum() != 0) {
                throw new IllegalArgumentException("独立 LOSS 的 rwa 必须为 0");
            }
        } else if (step.ba().signum() != 0) {
            throw new IllegalArgumentException("后续 Delivery 不得重复扣注");
        }
    }

    public static ResultAnalysis analyze(RoundResult round) {
        if (round == null || round.steps().isEmpty()) throw new IllegalArgumentException("完整 Round 不能为空");
        BigDecimal bet = GameRules.betAmount(round.bl(), round.bs());
        BigDecimal expectedBalance = round.startingBalance();
        for (int i = 0; i < round.steps().size(); i++) {
            SpinStep step = round.steps().get(i);
            if (step.bl() != round.bl() || step.bs().compareTo(round.bs()) != 0) {
                throw new IllegalArgumentException("Round 内下注参数漂移");
            }
            verifyStep(step);
            if (i == 0) {
                if (step.ba().compareTo(bet) != 0) throw new IllegalArgumentException("付费起点必须且只扣一次完整下注");
                expectedBalance = expectedBalance.subtract(step.ba());
            } else if (step.ba().signum() != 0) {
                throw new IllegalArgumentException("后续 Delivery 不得重复扣注");
            }
            boolean creditAnnounce = step.ss() == 1 && step.gm() == 1 && step.rsn() > 0 && step.nrsc() == 0;
            if (creditAnnounce || step.roundTerminal()) {
                expectedBalance = expectedBalance.add(step.rwa());
            }
            if (expectedBalance.compareTo(step.pb()) != 0) {
                throw new IllegalArgumentException("pb 余额链不连续 expected=" + expectedBalance + " actual=" + step.pb());
            }
        }
        RoundMode mode = inferMode(round.steps());
        if (mode != round.mode()) throw new IllegalArgumentException("Round 分类与独立反推不一致");
        BigDecimal totalWin = round.totalWin();
        int hundredths = totalWin.divide(bet, 2, RoundingMode.HALF_UP)
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return new ResultAnalysis(mode, bet, totalWin, hundredths, round.steps().size());
    }

    public static RoundMode inferMode(List<SpinStep> steps) {
        boolean free = false;
        boolean vortex = false;
        for (SpinStep step : steps) {
            if (step.fsn() > 0) free = true;
            if (step.rsn() > 0 || step.gm() == 3) vortex = true;
        }
        if (free) return RoundMode.FREE_SPINS;
        if (vortex) return RoundMode.WILD_VORTEX;
        BigDecimal win = steps.get(steps.size() - 1).rwa();
        return win.signum() > 0 ? RoundMode.ORDINARY_WIN : RoundMode.ORDINARY_LOSS;
    }

    public static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static int multiplierHundredths(BigDecimal totalWin, BigDecimal bet) {
        if (bet.signum() <= 0) throw new IllegalArgumentException("下注必须为正");
        return totalWin.divide(bet, 2, RoundingMode.HALF_UP)
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    public enum EnergyBranch { UNCHANGED, INCREMENT, FULL_TRIGGER }

    /** 会话绝对能量，只保存 0..5；满 6 由 UTIL 触发后复位为 0。 */
    public record EnergyState(int wn) {
        public EnergyState {
            if (wn < 0 || wn >= GameRules.WILD_ENERGY_CAP) {
                throw new IllegalArgumentException("energy must be 0.." + (GameRules.WILD_ENERGY_CAP - 1));
            }
        }
        public static EnergyState initial() { return new EnergyState(0); }
    }

    public record EnergyProjection(int displayedWn, boolean vortex, EnergyBranch branch, EnergyState nextState) {
        public EnergyProjection {
            if (nextState == null || branch == null) throw new IllegalArgumentException("energy projection incomplete");
            if (displayedWn < 0 || displayedWn > GameRules.WILD_ENERGY_CAP) {
                throw new IllegalArgumentException("displayed energy out of range");
            }
            if (vortex != (branch == EnergyBranch.FULL_TRIGGER)) {
                throw new IllegalArgumentException("vortex flag must match FULL_TRIGGER");
            }
        }
    }

    public static int wildEliminations(List<String> rskl) {
        List<Integer> wmkl = expectedWmkl(rskl);
        List<Cell> cells = parseBoard(rskl);
        int n = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (!cells.get(i).wild()) continue;
            if (wmkl.contains(GameRules.coord(i / GameRules.ROWS, i % GameRules.ROWS))) n++;
        }
        return n;
    }

    public static int energyDelta(List<StepFact> steps) {
        int delta = 0;
        for (StepFact step : steps) {
            if (step.fsn() > 0 || step.rsn() > 0 || step.gm() == 3) continue;
            delta += wildEliminations(step.rskl());
        }
        return delta;
    }

    /**
     * 入参是当前进度。是否进漩涡只由本方法决定，调用方不得自行改 wn。
     * 免费局不涨能量；已含漩涡的 member 不能再当普通入参。
     */
    public static EnergyProjection applyEnergyTransition(EnergyState previous, List<StepFact> ordinarySteps) {
        if (previous == null || ordinarySteps == null || ordinarySteps.isEmpty()) {
            throw new IllegalArgumentException("previous energy and ordinary steps required");
        }
        for (StepFact step : ordinarySteps) {
            if (step.rsn() > 0 || step.gm() == 3) {
                throw new IllegalArgumentException("ordinary fact must not already contain vortex");
            }
        }
        boolean free = false;
        for (StepFact step : ordinarySteps) if (step.fsn() > 0) free = true;
        int delta = free ? 0 : energyDelta(ordinarySteps);
        int sum = previous.wn() + delta;
        if (sum >= GameRules.WILD_ENERGY_CAP) {
            return new EnergyProjection(GameRules.WILD_ENERGY_CAP, true, EnergyBranch.FULL_TRIGGER, EnergyState.initial());
        }
        EnergyState next = new EnergyState(sum);
        return new EnergyProjection(sum, false, delta == 0 ? EnergyBranch.UNCHANGED : EnergyBranch.INCREMENT, next);
    }

    public static boolean canApplyEnergyTransition(EnergyState previous, List<StepFact> ordinarySteps) {
        try {
            applyEnergyTransition(previous, ordinarySteps);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static List<StepFact> vortexTail(List<StepFact> vortexMember) {
        if (vortexMember == null || vortexMember.isEmpty()) {
            throw new IllegalArgumentException("vortex cache member empty");
        }
        List<StepFact> tail = new ArrayList<>();
        for (StepFact step : vortexMember) {
            if (step.gm() == 3) tail.add(withWn(step, 0));
        }
        if (tail.isEmpty()) throw new IllegalArgumentException("vortex cache missing gm=3 tail");
        int lastNrsc = 0;
        for (StepFact step : tail) {
            if (step.rsn() != GameRules.VORTEX_COUNT) {
                throw new IllegalArgumentException("vortex tail rsn must be " + GameRules.VORTEX_COUNT);
            }
            if (step.nrsc() < 1 || step.nrsc() > GameRules.VORTEX_COUNT) {
                throw new IllegalArgumentException("vortex tail nrsc out of range");
            }
            if (step.nrsc() < lastNrsc) throw new IllegalArgumentException("vortex tail nrsc must not decrease");
            lastNrsc = step.nrsc();
        }
        StepFact end = tail.get(tail.size() - 1);
        if (!end.roundTerminal()) throw new IllegalArgumentException("vortex tail must terminate with rsn==nrsc");
        return List.copyOf(tail);
    }

    /**
     * 普通局事实 +（可选）漩涡尾部缓存拼成一条完整 Round 事实。
     * 触发时宣告盘沿用连消结束盘，三次漩涡用缓存新牌，不再扣注。
     */
    public static List<StepFact> stitch(List<StepFact> ordinarySteps, EnergyProjection projection,
                                        List<StepFact> vortexTailOrEmpty) {
        if (ordinarySteps == null || ordinarySteps.isEmpty() || projection == null) {
            throw new IllegalArgumentException("stitch requires ordinary steps and projection");
        }
        if (!projection.vortex()) {
            List<StepFact> rewritten = new ArrayList<>(ordinarySteps.size());
            for (StepFact step : ordinarySteps) rewritten.add(withWn(step, projection.nextState().wn()));
            return List.copyOf(rewritten);
        }
        if (vortexTailOrEmpty == null || vortexTailOrEmpty.isEmpty()) {
            throw new IllegalArgumentException("full trigger requires vortex tail cache");
        }
        List<StepFact> tail = vortexTail(vortexTailOrEmpty);
        StepFact last = ordinarySteps.get(ordinarySteps.size() - 1);
        if (last.ss() != 1 || last.fsn() != last.nfsc() || last.rsn() != 0) {
            throw new IllegalArgumentException("ordinary template must end before vortex announce");
        }
        List<StepFact> stitched = new ArrayList<>(ordinarySteps.size() + tail.size());
        for (int i = 0; i < ordinarySteps.size() - 1; i++) {
            stitched.add(withWn(ordinarySteps.get(i), GameRules.WILD_ENERGY_CAP));
        }
        stitched.add(announce(last));
        stitched.addAll(tail);
        return List.copyOf(stitched);
    }

    static StepFact withWn(StepFact step, int wn) {
        return new StepFact(step.rskl(), step.syxl(), step.wskl(), step.afnl(),
                step.smallGameType(), step.ss(), step.fsn(), step.nfsc(),
                step.rsn(), step.nrsc(), step.gt(), step.gm(), wn);
    }

    static StepFact announce(StepFact last) {
        return new StepFact(last.rskl(), last.syxl(), last.wskl(), last.afnl(),
                2, 1, 0, 0, GameRules.VORTEX_COUNT, 0, 1, 1, 0);
    }
}
