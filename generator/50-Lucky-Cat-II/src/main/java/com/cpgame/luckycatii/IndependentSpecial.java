package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Special round from Lucky prefix-WILD stacks and Wheel (one pay + WILD).
 * Capture R/M/K boards are 7-WILD family; that pattern is allowed when the rules produce it.
 */
public final class IndependentSpecial {
    private static final int[] WHEEL_RPX = {2, 3, 4, 5, 10};
    /** Matches generator.properties special-min / special-max; RedisLoader still filters. */
    static final int MIN_UNITS = 30;
    static final int MAX_UNITS = 1500;
    private static final List<RoundCandidate> LUCKY_DEFAULTS = defaults(true);
    private static final List<RoundCandidate> WHEEL_DEFAULTS = defaults(false);
    private final Map<String, Integer> specialWeights;
    private final Map<String, Integer> respinWeights;
    private final ReelPatterns patterns;

    public IndependentSpecial() {
        this(SymbolWeights.empiricalDefaults(), ReelPatterns.defaults());
    }

    public IndependentSpecial(SymbolWeights symbolWeights) {
        this(symbolWeights, ReelPatterns.defaults());
    }

    public IndependentSpecial(SymbolWeights symbolWeights, ReelPatterns patterns) {
        this.specialWeights = symbolWeights.special();
        this.respinWeights = symbolWeights.respin();
        this.patterns = patterns;
    }

    public RoundCandidate generate(RandomGenerator random) {
        return random.nextBoolean() ? lucky(random) : wheel(random);
    }

    public RoundCandidate lucky(RandomGenerator random) {
        return generateKind(random, true);
    }

    public RoundCandidate wheel(RandomGenerator random) {
        return generateKind(random, false);
    }

    private RoundCandidate generateKind(RandomGenerator random, boolean lucky) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RoundCandidate candidate = lucky ? luckyCandidate(random) : wheelCandidate(random);
            if (valid(candidate, lucky)) return candidate;
        }
        List<RoundCandidate> defaults = lucky ? LUCKY_DEFAULTS : WHEEL_DEFAULTS;
        return defaults.get(random.nextInt(defaults.size()));
    }

    RoundCandidate luckyCandidate(RandomGenerator random) {
        int respin = random.nextInt(3);
        String target = luckyTarget(random);
        String[] paid = new String[9];
        String[] fin = new String[9];
        for (int reel = 0; reel < 3; reel++) {
            if (reel == respin) continue;
            Dealing.fillStack(paid, reel, random.nextInt(3), target);
            for (int row = 0; row < 3; row++) {
                int index = GameRules.cellIndex(reel, row);
                fin[index] = paid[index];
            }
        }
        Dealing.fillReel(paid, respin, patterns.pick(random), specialWeights, random);
        if (!ResultUtil.evaluatePaylines(Dealing.freeze(paid)).isEmpty()
                || ResultUtil.findLuckyTrigger(Dealing.freeze(paid)) == null) {
            Dealing.fillReel(paid, respin, ReelPatterns.Shape.ABC, specialWeights, random);
            for (int row = 0; row < 3; row++) {
                int index = GameRules.cellIndex(respin, row);
                if (target.equals(paid[index]) || "WILD".equals(paid[index])) {
                    paid[index] = Dealing.otherPay(target, specialWeights, random);
                }
            }
        }

        boolean asWheel = 2 * GameRules.PAYTABLE.get(target) < MIN_UNITS || random.nextInt(4) == 0;
        if (asWheel) {
            Dealing.fillStack(fin, respin, random.nextInt(2), target);
            int rpx = WHEEL_RPX[weightedRpx(random)];
            List<String> s01 = Dealing.freeze(paid);
            List<String> s02 = Dealing.freeze(fin);
            if (!ResultUtil.isWheelBoard(s02)) {
                for (int row = 0; row < 3; row++) fin[GameRules.cellIndex(respin, row)] = target;
                s02 = Dealing.freeze(fin);
            }
            return new RoundCandidate(s01, s02, rpx, true);
        }
        Dealing.fillReel(fin, respin, patterns.pick(random), respinWeights, random);
        int missRow = random.nextInt(3);
        for (int row = 0; row < 3; row++) {
            int index = GameRules.cellIndex(respin, row);
            if (row == missRow) {
                if (target.equals(fin[index]) || "WILD".equals(fin[index])) {
                    fin[index] = Dealing.otherPay(target, respinWeights, random);
                }
            } else {
                fin[index] = target;
            }
        }
        return new RoundCandidate(Dealing.freeze(paid), Dealing.freeze(fin), 1, true);
    }

    RoundCandidate wheelCandidate(RandomGenerator random) {
        String symbol = Dealing.pickPay(specialWeights, random);
        int rpx = WHEEL_RPX[weightedRpx(random)];
        String[] cells = new String[9];
        for (int reel = 0; reel < 3; reel++) {
            Dealing.fillWheelReel(cells, reel, patterns.pick(random), symbol, random);
        }
        List<String> board = Dealing.freeze(cells);
        if (!ResultUtil.isWheelBoard(board)) {
            for (int i = 0; i < 9; i++) if (!"WILD".equals(cells[i])) cells[i] = symbol;
            board = Dealing.freeze(cells);
        }
        int units = Dealing.lineUnits(board) * rpx;
        if (units < MIN_UNITS || units > MAX_UNITS) {
            rpx = units < MIN_UNITS ? 5 : 2;
            if (Dealing.lineUnits(board) * rpx < MIN_UNITS) {
                for (int reel = 0; reel < 3; reel++) Dealing.fillWheelReel(cells, reel, ReelPatterns.Shape.AAA, "S1", random);
                board = Dealing.freeze(cells);
                rpx = 3;
            }
        }
        return new RoundCandidate(board, board, rpx, false);
    }

    static boolean valid(RoundCandidate candidate, boolean expectLucky) {
        if (candidate.luckyRespin() != expectLucky) return false;
        if (!Dealing.legal(candidate.paidBoard()) || !Dealing.legal(candidate.finalBoard())) return false;
        if (expectLucky) {
            if (ResultUtil.findLuckyTrigger(candidate.paidBoard()) == null) return false;
            if (!ResultUtil.evaluatePaylines(candidate.paidBoard()).isEmpty()) return false;
            if (candidate.rpx() == 1 && ResultUtil.isWheelBoard(candidate.finalBoard())) return false;
            if (candidate.rpx() != 1 && !GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(candidate.rpx())) return false;
            if (candidate.rpx() != 1 && !ResultUtil.isWheelBoard(candidate.finalBoard())) return false;
        } else {
            if (!GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(candidate.rpx())) return false;
            if (!ResultUtil.isWheelBoard(candidate.finalBoard())) return false;
            if (ResultUtil.findLuckyTrigger(candidate.paidBoard()) != null) return false;
        }
        Map<Integer, String> wins = ResultUtil.evaluatePaylines(candidate.finalBoard());
        if (wins.isEmpty()) return false;
        int units = Dealing.lineUnits(candidate.finalBoard()) * candidate.rpx();
        return units >= MIN_UNITS && units <= MAX_UNITS;
    }

    private static String luckyTarget(RandomGenerator random) {
        int ticket = random.nextInt(10);
        if (ticket < 4) return "S3";
        if (ticket < 7) return "S2";
        if (ticket < 9) return "S1";
        return "S4";
    }

    private static int weightedRpx(RandomGenerator random) {
        int ticket = random.nextInt(10);
        if (ticket < 5) return 0;
        if (ticket < 8) return 1;
        if (ticket < 9) return 2;
        return 3 + random.nextInt(2);
    }

    private static List<RoundCandidate> defaults(boolean lucky) {
        IndependentSpecial generator = new IndependentSpecial();
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            RoundCandidate chosen = null;
            for (int n = 0; n < 40; n++) {
                RoundCandidate candidate = lucky ? generator.luckyCandidate(random) : generator.wheelCandidate(random);
                if (valid(candidate, lucky)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) throw new ExceptionInInitializerError("invalid constructed special");
            defaults.add(chosen);
        }
        return List.copyOf(defaults);
    }
}
