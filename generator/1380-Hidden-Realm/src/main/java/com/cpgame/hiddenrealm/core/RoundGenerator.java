package com.cpgame.hiddenrealm.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** New complete rounds from empirical weights. Original captures are never replayed. */
public final class RoundGenerator {
    private final SecureRandom random;
    private final GameRuleCore rules;
    private final ZeroLossSupport<CompleteRound> losses;
    private final DealingModel model = new DealingModel();

    public RoundGenerator(SecureRandom random, GameRuleCore rules) {
        this.random = random;
        this.rules = rules;
        losses=new ZeroLossSupport<>(this::lossCandidate,r->!r.special()&&!r.win(),r->r);
    }

    public CompleteRound ordinary(boolean win) {
        if (win) return ordinaryWin();
        return losses.generate(this::lossCandidate,random::nextInt);
    }
    public CompleteRound lossCandidate(){
        return checked(new CompleteRound(List.of(new CompleteRound.Delivery(1,0,0,0,0,
                List.of(new CompleteRound.Page(model.lossBoard(random)))))));
    }

    /** Legal 4-9 collection win that never reaches Grass. Unobserved in 695 training paid originals. */
    private CompleteRound ordinaryWin() {
        for (int attempt = 0; attempt < 200000; attempt++) {
            try {
                int[][] board = model.initialBoard(random, false);
                GameRuleCore.Evaluation first = rules.evaluate(board);
                if (!first.scoring() || first.exploded() > 9) continue;
                int[][] dropped = rules.gravity(board, first.win());
                int[][] next = fill(dropped, false);
                if (rules.evaluate(next).scoring()) continue;
                List<CompleteRound.Page> pages = List.of(new CompleteRound.Page(board), new CompleteRound.Page(next));
                CompleteRound round = new CompleteRound(List.of(
                        new CompleteRound.Delivery(1, 0, 0, first.exploded(), 0, pages)));
                if (round.special()) continue;
                return checked(round);
            } catch (DealingModel.RejectedDeal ignored) {
            }
        }
        throw new IllegalStateException("ordinary win exhausted");
    }

    public CompleteRound special() { return special(1); }

    public CompleteRound special(int minPhase) {
        if (minPhase < 1 || minPhase > 4) throw new IllegalArgumentException("minPhase");
        for (int attempt = 0; attempt < 200000; attempt++) {
            try {
                CompleteRound round = build(true);
                if (round.maxPhase() >= minPhase) return checked(round);
            } catch (DealingModel.RejectedDeal ignored) {
            }
        }
        throw new IllegalStateException("special exhausted phase " + minPhase);
    }

    /**
     * Natural paid round. Original 795 complete paid rounds never produced collection 1-9,
     * so those boards are rejected here. Forced ordinary(true) still fills the Redis win pool.
     */
    public CompleteRound natural() {
        for (int attempt = 0; attempt < 200000; attempt++) {
            try {
                boolean specialEntry = random.nextInt(100) < 18;
                CompleteRound round = build(specialEntry);
                int collection = round.deliveries().get(round.deliveries().size() - 1).collection();
                if (collection > 0 && collection < GameRuleCore.GRASS_AT) continue;
                return checked(round);
            } catch (DealingModel.RejectedDeal ignored) {
            }
        }
        throw new IllegalStateException("natural exhausted");
    }

    private CompleteRound checked(CompleteRound round) {
        rules.validateRound(round);
        return round;
    }

    private CompleteRound build(boolean specialEntry) {
        List<CompleteRound.Delivery> deliveries = new ArrayList<>();
        int[][] board = model.initialBoard(random, specialEntry);
        int collection = 0;
        int phase = 0;
        List<CompleteRound.Page> pages = cascade(board, 0, false);
        collection += exploded(pages, 0);
        int max = rules.maxPhase(collection);
        deliveries.add(new CompleteRound.Delivery(1, 0, max, collection, 0, pages));
        board = last(pages);
        while (phase < max) {
            phase++;
            board = enterPhase(board, phase);
            pages = cascade(board, phase, true);
            collection += exploded(pages, phase);
            max = Math.min(4, Math.max(max, rules.maxPhase(collection)));
            deliveries.add(new CompleteRound.Delivery(2, phase, max, collection, 1, pages));
            board = last(pages);
            if (deliveries.size() > 8) throw new DealingModel.RejectedDeal("delivery cap");
        }
        return new CompleteRound(deliveries);
    }

    private int[][] enterPhase(int[][] board, int phase) {
        return switch (phase) {
            case 1 -> fill(rules.applyGrass(board), true);
            case 2 -> {
                int[][] water = rules.applyWater(board);
                if (!model.legalPage(water)) throw new DealingModel.RejectedDeal("water wild cap");
                yield water;
            }
            case 3 -> rules.applyFire(board, model.pickFire(random));
            case 4 -> board;
            default -> throw new IllegalStateException("phase");
        };
    }

    private List<CompleteRound.Page> cascade(int[][] start, int phase, boolean feature) {
        List<CompleteRound.Page> pages = new ArrayList<>();
        int[][] board = rules.copy(start);
        if (phase == 4 && rules.hasLow(board)) board = transformLows(board);
        for (int step = 0; step < 40; step++) {
            if (phase == 4 && rules.hasLow(board) && !pages.isEmpty()) {
                pages.add(new CompleteRound.Page(board));
                board = transformLows(board);
                continue;
            }
            GameRuleCore.Evaluation ev = rules.evaluatePage(board, phase);
            pages.add(new CompleteRound.Page(board));
            if (!ev.scoring()) return pages;
            int[][] dropped = rules.gravity(board, ev.win());
            board = fill(dropped, feature);
            if (pages.size() > 36) throw new DealingModel.RejectedDeal("page cap");
        }
        throw new DealingModel.RejectedDeal("cascade cap");
    }

    private int[][] transformLows(int[][] board) {
        int[][] out = rules.copy(board);
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++)
            if (out[c][r] <= 4) out[c][r] = model.pickLord(random);
        if (!model.legalPage(out)) throw new DealingModel.RejectedDeal("lord cap");
        return out;
    }

    private int[][] fill(int[][] board, boolean feature) {
        int[][] out = new int[5][5];
        for (int c = 0; c < 5; c++) out[c] = board[c].clone();
        model.fillEmpty(out, random, feature);
        rules.validateBoard(out);
        return out;
    }

    private int exploded(List<CompleteRound.Page> pages, int phase) {
        int n = 0;
        for (CompleteRound.Page page : pages) n += rules.evaluatePage(page.board(), phase).exploded();
        return n;
    }

    private int[][] last(List<CompleteRound.Page> pages) {
        return pages.get(pages.size() - 1).board();
    }
}
