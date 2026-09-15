package com.cpgame.batcha.g32;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class OriginalEvidenceAuditMain {
    public static void main(String[] args) throws Exception {
        List<OriginRound> rounds = load();
        ResultUtil util = new ResultUtil();
        BigDecimal bs = new BigDecimal("0.02");
        int bl = 10;
        int stepChecked = 0, stepMismatch = 0;
        List<OriginRound> clean = new ArrayList<>();
        for (OriginRound origin : rounds) {
            boolean consistent = true;
            for (OriginStep step : origin.steps) {
                ResultUtil.BoardResult got = util.evaluate(step.tokens, bs, bl, step.rpx);
                stepChecked++;
                if (got.winAmount().compareTo(step.wa) != 0) {
                    stepMismatch++;
                    if (stepMismatch <= 8) {
                        System.err.printf("WA %s rpx=%d want=%s have=%s%n", origin.id, step.rpx, step.wa, got.winAmount());
                    }
                }
            }
            if (isClean(origin)) clean.add(origin);
            else consistent = false;
        }
        int holdout = Math.min(100, clean.size());
        int materializeMismatch = 0;
        int materializeSteps = 0;
        for (int i = 0; i < holdout; i++) {
            OriginRound origin = clean.get(clean.size() - holdout + i);
            List<Step> facts = new ArrayList<>();
            BigDecimal paid = GameRuleCore.paidBet(bs, bl);
            for (int s = 0; s < origin.steps.size(); s++) {
                OriginStep step = origin.steps.get(s);
                facts.add(Step.fact(s, s == 0 ? paid : BigDecimal.ZERO, bs, bl, step.tokens, step.silver, step.gold));
            }
            try {
                CompleteRound got = GameRuleCore.materialize(paid, bs, bl, facts);
                for (int s = 0; s < origin.steps.size(); s++) {
                    OriginStep want = origin.steps.get(s);
                    Step have = got.steps().get(s);
                    materializeSteps++;
                    if (have.spinStatus() != want.ss || have.roundPayX() != want.rpx
                        || have.freeSpinNum() != want.fsn || have.nowFreeSpinCount() != want.nfsc
                        || have.smallGameType() != want.sgt
                        || have.winAmount().compareTo(want.wa) != 0
                        || have.roundWinAmount().compareTo(want.rwa) != 0) {
                        materializeMismatch++;
                        if (materializeMismatch <= 12) {
                            System.err.printf("STATE %s step %d want ss=%d rpx=%d fsn=%d nfsc=%d sgt=%d wa=%s rwa=%s have ss=%d rpx=%d fsn=%d nfsc=%d sgt=%d wa=%s rwa=%s%n",
                                origin.id, s, want.ss, want.rpx, want.fsn, want.nfsc, want.sgt, want.wa, want.rwa,
                                have.spinStatus(), have.roundPayX(), have.freeSpinNum(), have.nowFreeSpinCount(),
                                have.smallGameType(), have.winAmount(), have.roundWinAmount());
                        }
                    }
                }
            } catch (RuntimeException error) {
                materializeMismatch++;
                System.err.println("MATERIALIZE " + origin.id + " " + error.getMessage());
            }
        }
        System.out.printf("STEP_ORACLE checked=%d mismatches=%d CLEAN_ROUNDS=%d HOLDOUT=%d materializeSteps=%d materializeMismatch=%d%n",
            stepChecked, stepMismatch, clean.size(), holdout, materializeSteps, materializeMismatch);
        if (stepMismatch != 0 || holdout < 100 || materializeMismatch != 0) System.exit(1);
    }

    private static boolean isClean(OriginRound origin) {
        if (origin.steps.isEmpty()) return false;
        OriginStep first = origin.steps.getFirst();
        if (first.ba.compareTo(new BigDecimal("4")) != 0 || first.rpx != 1) return false;
        for (int i = 1; i < origin.steps.size(); i++) {
            OriginStep prev = origin.steps.get(i - 1);
            OriginStep cur = origin.steps.get(i);
            if (prev.ss == 0) {
                int expect = prev.rpx + (prev.sgt == 2 ? 2 : 1);
                if (cur.rpx != expect || cur.nfsc != prev.nfsc || cur.sgt != prev.sgt) return false;
            } else if (prev.fsn > prev.nfsc) {
                if (prev.nfsc == 0) {
                    if (cur.sgt != 2 || cur.nfsc != 1 || cur.rpx != 2) return false;
                } else if (cur.nfsc != prev.nfsc + 1 || cur.sgt != 2 || cur.rpx != prev.rpx) {
                    return false;
                }
            } else {
                return false;
            }
        }
        OriginStep last = origin.steps.getLast();
        return last.ss == 1 && last.fsn == last.nfsc;
    }

    static List<OriginRound> load() throws IOException {
        try (InputStream in = OriginalEvidenceAuditMain.class.getResourceAsStream("/origin-oracle.tsv")) {
            if (in == null) throw new IllegalStateException("missing origin-oracle.tsv");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<OriginRound> rounds = new ArrayList<>();
            OriginRound current = null;
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                String[] f = line.split("\t", -1);
                if (f[0].equals("R")) {
                    current = new OriginRound(f[4], new ArrayList<>());
                    rounds.add(current);
                } else if (f[0].equals("S") && current != null) {
                    current.steps.add(new OriginStep(
                        new BigDecimal(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                        Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                        new BigDecimal(f[7]), new BigDecimal(f[8]), new BigDecimal(f[9]),
                        coords(f[10]), coords(f[11]), tokens(f[12])));
                }
            }
            return rounds;
        }
    }

    private static List<Integer> coords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Integer> values = new ArrayList<>();
        for (String part : raw.split(",")) if (!part.isBlank()) values.add(Integer.parseInt(part));
        return values;
    }

    private static List<String> tokens(String raw) {
        return List.of(raw.split(","));
    }

    static final class OriginRound {
        final String id;
        final List<OriginStep> steps;
        OriginRound(String id, List<OriginStep> steps) { this.id = id; this.steps = steps; }
    }

    static final class OriginStep {
        final BigDecimal ba;
        final int rpx, ss, fsn, nfsc, sgt;
        final BigDecimal wa, rwa, frwa;
        final List<Integer> silver, gold;
        final List<String> tokens;
        OriginStep(BigDecimal ba, int rpx, int ss, int fsn, int nfsc, int sgt,
                   BigDecimal wa, BigDecimal rwa, BigDecimal frwa,
                   List<Integer> silver, List<Integer> gold, List<String> tokens) {
            this.ba = ba; this.rpx = rpx; this.ss = ss; this.fsn = fsn; this.nfsc = nfsc; this.sgt = sgt;
            this.wa = wa; this.rwa = rwa; this.frwa = frwa; this.silver = silver; this.gold = gold; this.tokens = tokens;
        }
    }
}
