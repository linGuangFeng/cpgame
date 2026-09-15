package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundResult;
import com.cpgame.luckycatii.model.RoundStep;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Builds complete-round truth without calling ResultUtil. */
public final class RoundFactory {
    private final IdentitySource identitySource;

    public RoundFactory() {
        this(new SecureIdentitySource());
    }

    public RoundFactory(IdentitySource identitySource) {
        this.identitySource = identitySource;
    }

    public RoundResult create(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        RoundIdentity identity = identitySource.next();
        return create(candidate, betSize, betLevel, identity.roundKey(), identity.createdAtEpochSecond());
    }

    public RoundResult restore(RoundFacts facts) {
        return create(new RoundCandidate(facts.paidBoard(), facts.finalBoard(), facts.rpx(), facts.luckyRespin()),
                facts.betSize(), facts.betLevel(), facts.roundKey(), facts.createdAtEpochSecond());
    }

    private RoundResult create(RoundCandidate candidate, BigDecimal betSize, int betLevel,
                               String roundKey, long createdAtEpochSecond) {
        if (!GameRules.legalBet(betSize, betLevel)) throw new IllegalArgumentException("非法下注档位");
        List<String> paid = List.copyOf(candidate.paidBoard());
        List<String> fin = List.copyOf(candidate.finalBoard());
        if (paid.size() != 9 || fin.size() != 9) throw new IllegalArgumentException("候选牌面必须为 3×3 九格");
        Map<Integer, String> paidWins = lineWins(paid);
        FactoryTrigger trigger = luckyTrigger(paid);
        boolean lucky = candidate.luckyRespin();
        if (lucky) {
            if (trigger == null) throw new IllegalArgumentException("Lucky 起点缺少前缀 WILD 堆叠");
            if (!paidWins.isEmpty()) throw new IllegalArgumentException("Lucky 起点已有线奖");
            for (int reel = 0; reel < 3; reel++) {
                if (reel != trigger.respinReel && !sameReel(paid, fin, reel)) {
                    throw new IllegalArgumentException("Lucky 非重转轴变化");
                }
            }
        } else if (!paid.equals(fin)) {
            throw new IllegalArgumentException("普通局两盘必须相同");
        }
        Map<Integer, String> wins = lineWins(fin);
        boolean wheel = wheelBoard(fin);
        int rpx = candidate.rpx();
        if (wheel) {
            if (!GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(rpx)) {
                throw new IllegalArgumentException("Wheel 倍率不在确认集合");
            }
        } else if (rpx != 1) {
            throw new IllegalArgumentException("非 Wheel 牌面 rpx 必须为 1");
        }
        BigDecimal betAmount = GameRules.betAmount(betSize, betLevel);
        BigDecimal award = lineAward(wins, betSize, betLevel, rpx);
        int gm = lucky ? 1 : 0;
        int rdri = lucky ? trigger.respinReel : 0;
        List<String> rdskl = lucky ? reel(paid, rdri) : List.of();
        List<RoundStep> steps = new ArrayList<>();
        if (lucky) {
            steps.add(new RoundStep("S01_PAID_SPIN_START", true, paid));
            steps.add(new RoundStep("S02_LUCKY_RESPIN_FINAL", false, fin));
        } else {
            steps.add(new RoundStep("S01_PAID_BOARD", true, fin));
        }
        return new RoundResult(roundKey, createdAtEpochSecond, betSize, betLevel, betAmount, gm, rdri, rdskl,
                rpx, paid, fin, Map.copyOf(wins), award, List.copyOf(steps));
    }

    private static Map<Integer, String> lineWins(List<String> board) {
        Map<Integer, String> wins = new LinkedHashMap<>();
        for (int line = 0; line < GameRules.PAYLINE_ROWS.length; line++) {
            String match = null;
            boolean allWild = true;
            for (int reel = 0; reel < 3; reel++) {
                String symbol = board.get(GameRules.cellIndex(reel, GameRules.PAYLINE_ROWS[line][reel]));
                if ("WILD".equals(symbol)) continue;
                allWild = false;
                if (match == null) match = symbol;
                else if (!match.equals(symbol)) {
                    match = null;
                    allWild = false;
                    match = "";
                    break;
                }
            }
            if ("".equals(match)) continue;
            if (allWild) wins.put(line + 1, "WILD");
            else if (match != null) wins.put(line + 1, match);
        }
        return wins;
    }

    private static BigDecimal lineAward(Map<Integer, String> wins, BigDecimal betSize, int betLevel, int rpx) {
        long units = 0L;
        for (String symbol : wins.values()) units += GameRules.PAYTABLE.get(symbol);
        return betSize.multiply(BigDecimal.valueOf(betLevel * units * (long) rpx)).stripTrailingZeros();
    }

    private static boolean wheelBoard(List<String> board) {
        String face = null;
        for (String symbol : board) {
            if ("WILD".equals(symbol)) continue;
            if (face == null) face = symbol;
            else if (!face.equals(symbol)) return false;
        }
        return true;
    }

    private static FactoryTrigger luckyTrigger(List<String> board) {
        if (!lineWins(board).isEmpty()) return null;
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 3; b++) {
                if (shareTarget(board, a, b)) return new FactoryTrigger(3 - a - b);
            }
        }
        return null;
    }

    private static boolean shareTarget(List<String> board, int left, int right) {
        boolean[] leftOk = stackTargets(board, left);
        boolean[] rightOk = stackTargets(board, right);
        for (int i = 0; i < leftOk.length; i++) if (leftOk[i] && rightOk[i]) return true;
        return false;
    }

    private static boolean[] stackTargets(List<String> board, int reel) {
        List<String> faces = List.of("S1", "S2", "S3", "S4", "S5", "S6");
        boolean[] ok = new boolean[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            String target = faces.get(i);
            boolean seen = false;
            boolean pass = true;
            for (int row = 0; row < 3; row++) {
                String cell = board.get(GameRules.cellIndex(reel, row));
                if (!seen && "WILD".equals(cell)) continue;
                if (target.equals(cell)) seen = true;
                else {
                    pass = false;
                    break;
                }
            }
            ok[i] = pass;
        }
        return ok;
    }

    private static boolean sameReel(List<String> left, List<String> right, int reel) {
        return reel(left, reel).equals(reel(right, reel));
    }

    private static List<String> reel(List<String> board, int reel) {
        return List.copyOf(board.subList(reel * 3, reel * 3 + 3));
    }

    public interface IdentitySource {
        RoundIdentity next();
    }

    public record RoundIdentity(String roundKey, long createdAtEpochSecond) {}

    private static final class SecureIdentitySource implements IdentitySource {
        private final SecureRandom random = new SecureRandom();
        @Override public RoundIdentity next() {
            return new RoundIdentity("lc50-" + hex(random), Instant.now().getEpochSecond());
        }
    }

    public static IdentitySource deterministicIdentitySource(long seed) {
        RandomGenerator random = new java.util.SplittableRandom(seed);
        return () -> new RoundIdentity("lc50-test-" + hex(random),
                1_700_000_000L + Math.floorMod(random.nextLong(), 10_000_000L));
    }

    private static String hex(RandomGenerator random) {
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }

    private record FactoryTrigger(int respinReel) {}
}
