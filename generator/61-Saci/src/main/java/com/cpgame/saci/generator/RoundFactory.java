package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.Cell;
import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundFacts;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.SpinStep;
import com.cpgame.saci.generator.model.StepFact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 完整 Round 真值工厂。Ways 计算写在本类，不调用 ResultUtil。 */
public final class RoundFactory {
    private final IdentitySource identitySource;

    public RoundFactory() {
        this(new SecureIdentitySource());
    }

    public RoundFactory(IdentitySource identitySource) {
        this.identitySource = identitySource;
    }

    public RoundResult create(RoundCandidate candidate, int bl, BigDecimal bs, BigDecimal startingBalance) {
        return create(identitySource.nextKey(), candidate, bl, bs, startingBalance);
    }

    public RoundResult restore(RoundFacts facts) {
        return create(facts.roundKey(), facts.candidate(), facts.betLevel(), facts.betSize(),
                GameRules.betAmount(facts.betLevel(), facts.betSize()).multiply(BigDecimal.TEN));
    }

    public RoundResult restore(RoundFacts facts, BigDecimal startingBalance) {
        return create(facts.roundKey(), facts.candidate(), facts.betLevel(), facts.betSize(), startingBalance);
    }

    private RoundResult create(String roundKey, RoundCandidate candidate, int bl, BigDecimal bs,
                               BigDecimal startingBalance) {
        BigDecimal bet = GameRules.betAmount(bl, bs);
        if (startingBalance.compareTo(bet) < 0) throw new IllegalArgumentException("余额不足");
        List<StepFact> facts = candidate.steps();
        long now = System.currentTimeMillis() / 1000;
        List<SpinStep> steps = new ArrayList<>(facts.size());
        BigDecimal balance = startingBalance.subtract(bet);
        BigDecimal baseRwa = BigDecimal.ZERO;
        BigDecimal featureRwa = BigDecimal.ZERO;
        boolean vortex = false;
        for (int i = 0; i < facts.size(); i++) {
            StepFact fact = facts.get(i);
            BigDecimal wa = generatedWa(fact.rskl(), bl, bs);
            List<Integer> wmkl = generatedWmkl(fact.rskl());
            if (fact.gm() == 3 && !vortex) {
                vortex = true;
                featureRwa = BigDecimal.ZERO;
            }
            BigDecimal rwa;
            BigDecimal frwa;
            if (fact.gm() == 2 && fact.fsn() > 0) {
                featureRwa = featureRwa.add(wa);
                rwa = featureRwa;
                frwa = featureRwa;
            } else if (fact.gm() == 3) {
                featureRwa = featureRwa.add(wa);
                rwa = featureRwa;
                frwa = BigDecimal.ZERO;
            } else {
                baseRwa = baseRwa.add(wa);
                rwa = baseRwa;
                frwa = BigDecimal.ZERO;
            }
            boolean creditAnnounce = fact.ss() == 1 && fact.gm() == 1 && fact.rsn() > 0 && fact.nrsc() == 0;
            boolean creditClose = fact.roundTerminal();
            if (creditAnnounce || creditClose) balance = balance.add(rwa);
            Map<String, Integer> wnl = SpinStep.energyMap(bet, fact.wn());
            steps.add(new SpinStep(
                    i == 0 ? bet : BigDecimal.ZERO, bl, bs, now + i, money(frwa), fact.fsn(), fact.gm(),
                    fact.gt(), fact.nfsc(), fact.nrsc(), money(balance), fact.rskl(), fact.rsn(), money(rwa),
                    fact.smallGameType(), fact.ss(), fact.syxl(), money(wa), wmkl, fact.wn(), wnl,
                    fact.wskl(), fact.afnl()));
        }
        return new RoundResult(roundKey, candidate.mode(), bl, bs, startingBalance, steps);
    }

    static BigDecimal generatedWa(List<String> rskl, int bl, BigDecimal bs) {
        BigDecimal unit = bs.multiply(BigDecimal.valueOf(bl));
        BigDecimal total = BigDecimal.ZERO;
        for (int[] win : generatedWins(rskl)) {
            total = total.add(unit.multiply(BigDecimal.valueOf(win[0])));
        }
        return money(total);
    }

    static List<Integer> generatedWmkl(List<String> rskl) {
        TreeSet<Integer> coords = new TreeSet<>();
        collectWins(rskl, coords, null);
        return List.copyOf(coords);
    }

    /** win[0] = copyWays * cellFactor * payout */
    private static List<int[]> generatedWins(List<String> rskl) {
        List<int[]> wins = new ArrayList<>();
        collectWins(rskl, null, wins);
        return wins;
    }

    private static void collectWins(List<String> rskl, TreeSet<Integer> coords, List<int[]> wins) {
        Cell[] cells = new Cell[GameRules.BOARD_SIZE];
        for (int i = 0; i < GameRules.BOARD_SIZE; i++) cells[i] = Cell.parse(rskl.get(i));
        for (String target : List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")) {
            List<List<Integer>> reelMatches = new ArrayList<>();
            for (int reel = 0; reel < GameRules.REELS; reel++) {
                List<Integer> match = new ArrayList<>();
                for (int row = 0; row < GameRules.ROWS; row++) {
                    int idx = GameRules.indexOf(reel, row);
                    Cell cell = cells[idx];
                    boolean hit = "9".equals(target) ? cell.wild() : target.equals(cell.symbol()) || cell.wild();
                    if (hit && !cell.scatter()) match.add(idx);
                }
                if (match.isEmpty()) break;
                reelMatches.add(match);
            }
            int reels = reelMatches.size();
            int minReels = "9".equals(target) ? 5 : 3;
            if (reels < minReels) continue;
            if (!"9".equals(target)) {
                boolean natural = false;
                for (List<Integer> match : reelMatches) {
                    for (int idx : match) if (target.equals(cells[idx].symbol())) natural = true;
                }
                if (!natural) continue;
            }
            Integer payout = GameRules.PAYTABLE.getOrDefault(target, Map.of()).get(reels);
            if (payout == null) continue;
            int copyWays = 1;
            int chest = 0;
            for (List<Integer> match : reelMatches) {
                int copies = 0;
                for (int idx : match) {
                    Cell cell = cells[idx];
                    copies += cell.copies();
                    if (cell.multiplier() > 1) chest += cell.copies() * cell.multiplier();
                    if (coords != null) coords.add(GameRules.coord(idx / 3, idx % 3));
                }
                copyWays *= copies;
            }
            if (wins != null) wins.add(new int[]{copyWays * (chest > 0 ? chest : 1) * payout});
        }
        if (coords != null) {
            List<Integer> sorted = new ArrayList<>(coords);
            sorted.sort(Comparator.naturalOrder());
            coords.clear();
            coords.addAll(sorted);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    interface IdentitySource {
        String nextKey();
    }

    static final class SecureIdentitySource implements IdentitySource {
        private final SecureRandom random = new SecureRandom();
        @Override public String nextKey() {
            return Long.toUnsignedString(random.nextLong());
        }
    }

    static IdentitySource deterministicIdentitySource(long seed) {
        return () -> Long.toUnsignedString(seed);
    }
}
