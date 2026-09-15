package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

/** Fresh complete Rounds from measured dealing entries and the unique settlement core. */
public final class CompleteRoundFactory {
    private final ZeroLossSupport<List<String>> lossBoards;

    private final int maximumCascades;
    private final int maximumSpecialSpins;
    private final EmpiricalColumnModel model = EmpiricalColumnModel.instance();

    public CompleteRoundFactory(int maximumCascades, int maximumSpecialSpins) {
        if (maximumCascades < 1 || maximumSpecialSpins < 14)
            throw new IllegalArgumentException("Limits must allow evidenced 10/12/14 Free grants");
        this.maximumCascades = Math.min(18, maximumCascades);
        this.maximumSpecialSpins = Math.min(25, maximumSpecialSpins);
        SecureRandom defaultsRandom=new SecureRandom();
        lossBoards=new ZeroLossSupport<>(()->lossBoardCandidate(defaultsRandom),this::validLossBoard,List::copyOf);
    }

    public CompleteRound generate(RoundMode requested, SecureRandom random, BigDecimal betSize, int betLevel) {
        Objects.requireNonNull(random);
        GameRuleCore.validateBet(betSize, betLevel);
        if(requested==RoundMode.LOSS){
            List<String> board=lossBoards.generate(()->lossBoardCandidate(random),random::nextInt);
            BigDecimal paid=betSize.multiply(BigDecimal.valueOf(20L*betLevel));
            return GameRuleCore.materialize(paid,betSize,betLevel,List.of(Step.fact(0,paid,betSize,betLevel,board,1,0,0,0)));
        }
        if (requested == RoundMode.WIN)
            throw new IllegalArgumentException("EXCLUSIVE_ORDINARY_WIN_UNOBSERVED: paying base boards continue as small_game_type=1; no fabricated single-Step WIN");
        for (int attempt = 0; attempt < 20000; attempt++) {
            try {
                CompleteRound round = candidate(requested, random, betSize, betLevel);
                if (round.mode() == requested) return round;
            } catch (Rejected ignored) { /* Discard complete candidate, never patch its outcome. */ }
        }
        throw new IllegalStateException("Empirical complete-Round candidate limit exhausted for " + requested);
    }

    private CompleteRound candidate(RoundMode mode, SecureRandom random, BigDecimal bs, int bl) {
        BigDecimal paid = bs.multiply(BigDecimal.valueOf(20L * bl));
        List<Step> facts = new ArrayList<>();
        String entry = switch (mode) {
            case LOSS -> "PAID_LOSS";
            case MARY -> "PAID_CASCADE";
            case FREE -> "PAID_FREE";
            default -> throw new IllegalArgumentException("Unsupported mode");
        };
        List<String> board = initial(entry, random);
        int scatters = count(board, "Scat");
        boolean wins = wins(board, bs, bl);
        if (mode == RoundMode.LOSS) {
            if (wins || scatters >= 3) throw new Rejected();
            facts.add(Step.fact(0, paid, bs, bl, board, 1, 0, 0, 0));
        } else if (mode == RoundMode.MARY) {
            if (!wins || scatters >= 3) throw new Rejected();
            int cascade = 0;
            while (true) {
                boolean pays = wins(board, bs, bl);
                if (count(board, "Scat") >= 3) throw new Rejected();
                if (pays && ++cascade > maximumCascades) throw new Rejected();
                facts.add(Step.fact(facts.size(), facts.isEmpty() ? paid : BigDecimal.ZERO,
                    bs, bl, board, pays ? 0 : 1, 0, 0, facts.isEmpty() ? 0 : 1));
                if (!pays) break;
                board = refill("BASE_REFILL", board, bs, bl, random);
            }
        } else {
            if (wins || scatters < 3 || scatters > 5) throw new Rejected();
            int granted = 10 + (scatters - 3) * 2;
            facts.add(Step.fact(0, paid, bs, bl, board, 1, granted, 0, 2));
            for (int ordinal = 1; ordinal <= granted; ordinal++) {
                board = initial("FREE_INITIAL", random);
                int cascade = 0;
                while (true) {
                    int scat = count(board, "Scat");
                    if (scat > 2) throw new Rejected();
                    boolean pays = wins(board, bs, bl);
                    if (pays && ++cascade > maximumCascades) throw new Rejected();
                    if (!pays && scat == 2) {
                        granted += 5;
                        if (granted > maximumSpecialSpins) throw new Rejected();
                    }
                    facts.add(Step.fact(facts.size(), BigDecimal.ZERO, bs, bl, board,
                        pays ? 0 : 1, granted, ordinal, 2));
                    if (!pays) break;
                    board = refill("FREE_REFILL", board, bs, bl, random);
                }
            }
        }
        CompleteRound result = GameRuleCore.materialize(paid, bs, bl, facts);
        if (mode != RoundMode.LOSS && result.payout().signum() <= 0) throw new Rejected();
        return result;
    }

    private List<String> initial(String entry, SecureRandom random) {
        List<String> board = new ArrayList<>(36);
        for (int c = 0; c < 6; c++) board.addAll(model.draw(entry, c, 6, random));
        if (!EmpiricalColumnModel.legalSpecials(board)) throw new Rejected();
        return board;
    }

    private List<String> refill(String entry, List<String> previous, BigDecimal bs, int bl, SecureRandom random) {
        boolean[] removed = new boolean[36];
        GameRuleCore.evaluateBoard(previous, bs, bl).matches().forEach(m ->
            m.indices().forEach(position -> removed[GameRuleCore.boardIndex(position)] = true));
        List<String> board = new ArrayList<>(36);
        for (int c = 0; c < 6; c++) {
            List<String> retained = new ArrayList<>();
            for (int r = 0; r < 6; r++) if (!removed[c * 6 + r]) retained.add(previous.get(c * 6 + r));
            board.addAll(model.draw(entry, c, 6 - retained.size(), random));
            board.addAll(retained);
        }
        if (!EmpiricalColumnModel.legalSpecials(board)) throw new Rejected();
        return board;
    }

    private static boolean wins(List<String> board, BigDecimal bs, int bl) {
        return GameRuleCore.evaluateBoard(board, bs, bl).winAmount().signum() > 0;
    }
    private static int count(List<String> board, String symbol) {
        return (int) board.stream().filter(symbol::equals).count();
    }
    private static final class Rejected extends RuntimeException {
        private Rejected() { super(null, null, false, false); }
    }

    public List<String> lossBoardCandidate(SecureRandom random){
        List<String> raw=new ArrayList<>();for(int c=0;c<6;c++)raw.addAll(model.draw("PAID_LOSS",c,6,random));
        int[] counts=new int[GameRuleCore.PAYING_SYMBOLS.size()];int scat=0,x=0;
        for(int c=0;c<6;c++){
            int colScat=0,colX=0;
            for(int r=0;r<6;r++){
                int i=c*6+r;String symbol=raw.get(i);int at=GameRuleCore.PAYING_SYMBOLS.indexOf(symbol);
                boolean unsafe=at>=0&&counts[at]>=7 || symbol.equals("Scat")&&(scat>=2||colScat>=1)
                        || GameRuleCore.isMultiplier(symbol)&&(x>=4||colX>=2);
                if(unsafe){
                    List<String> allowed=new ArrayList<>();for(int k=0;k<counts.length;k++)if(counts[k]<7)allowed.add(GameRuleCore.PAYING_SYMBOLS.get(k));
                    symbol=allowed.get(random.nextInt(allowed.size()));raw.set(i,symbol);at=GameRuleCore.PAYING_SYMBOLS.indexOf(symbol);
                }
                if(at>=0)counts[at]++;if(symbol.equals("Scat")){scat++;colScat++;}if(GameRuleCore.isMultiplier(symbol)){x++;colX++;}
            }
        }
        return List.copyOf(raw);
    }
    private boolean validLossBoard(List<String> b){return EmpiricalColumnModel.legalSpecials(b)&&count(b,"Scat")<3
        &&GameRuleCore.evaluateBoard(b,new BigDecimal("0.05"),1).winAmount().signum()==0;}
}
