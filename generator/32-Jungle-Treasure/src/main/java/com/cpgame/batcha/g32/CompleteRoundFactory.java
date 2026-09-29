package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CompleteRoundFactory {
    private final ZeroLossSupport<List<String>> lossBoards;

    private final int maximumCascades;
    private EmpiricalColumnModel model;

    public CompleteRoundFactory(int maximumCascades, int maximumSpecialSpins) {
        if (maximumCascades < 1) throw new IllegalArgumentException("cascade limit");
        this.maximumCascades = Math.min(GameRuleCore.MAX_CASCADES, maximumCascades);
        if (maximumSpecialSpins < GameRuleCore.FREE_GRANT) {
            throw new IllegalArgumentException("special spin limit must allow 10 free spins");
        }
        SecureRandom defaultsRandom=new SecureRandom();
        lossBoards=new ZeroLossSupport<>(()->lossBoardCandidate(defaultsRandom),this::validLossBoard,List::copyOf);
    }

    public CompleteRoundFactory(int maximumCascades, int maximumSpecialSpins, EmpiricalColumnModel model) {
        this(maximumCascades, maximumSpecialSpins);
        this.model = Objects.requireNonNull(model);
    }

    private EmpiricalColumnModel model() {
        if (model == null) model = EmpiricalColumnModel.instance();
        return model;
    }

    public CompleteRound generate(RoundMode requested, SecureRandom random, BigDecimal betSize, int betLevel) {
        Objects.requireNonNull(random);
        if(requested==RoundMode.LOSS){
            List<String> board=lossBoards.generate(()->lossBoardCandidate(random),random::nextInt);
            BigDecimal paid=GameRuleCore.paidBet(betSize,betLevel);
            return GameRuleCore.materialize(paid,betSize,betLevel,List.of(Step.fact(0,paid,betSize,betLevel,board,List.of(),List.of())));
        }
        for (int attempt = 0; attempt < 40000; attempt++) {
            try {
                CompleteRound round = candidate(requested, random, betSize, betLevel);
                if (round.mode() == requested) return round;
            } catch (Rejected | IllegalArgumentException | IllegalStateException ignored) { }
        }
        throw new IllegalStateException("Empirical complete-Round candidate limit exhausted for " + requested);
    }

    private CompleteRound candidate(RoundMode mode, SecureRandom random, BigDecimal bs, int bl) {
        BigDecimal paid = GameRuleCore.paidBet(bs, bl);
        List<Step> facts = new ArrayList<>();
        Cascade.Board board = initial("PAID_INITIAL", random);
        int rpx = 1;
        int cascades = 0;
        boolean triggered = false;
        while (true) {
            GameRuleCore.BoardResult result = GameRuleCore.evaluateBoard(board.tokens(), bs, bl, rpx);
            int scat = GameRuleCore.scatterCount(result.tokens());
            facts.add(Step.fact(facts.size(), facts.isEmpty() ? paid : BigDecimal.ZERO, bs, bl,
                board.tokens(), board.silver(), board.gold()));
            if (result.winAmount().signum() > 0) {
                if (++cascades > maximumCascades) throw new Rejected();
                if (scat >= 4) triggered = true;
                rpx = Math.min(GameRuleCore.MAX_RPX, rpx + 1);
                board = refill(board, result, random);
            } else {
                if (scat >= 4) triggered = true;
                break;
            }
            if (facts.size() > 24) throw new Rejected();
        }
        if (mode == RoundMode.LOSS) {
            if (facts.size() != 1 || triggered || facts.getFirst().tokens().isEmpty()) throw new Rejected();
            GameRuleCore.BoardResult only = GameRuleCore.evaluateBoard(facts.getFirst().tokens(), bs, bl, 1);
            if (only.winAmount().signum() > 0) throw new Rejected();
        } else if (mode == RoundMode.WIN) {
            if (triggered || facts.size() < 2) throw new Rejected();
        } else {
            if (!triggered) throw new Rejected();
            rpx = 2;
            for (int n = 0; n < GameRuleCore.FREE_GRANT; n++) {
                board = initial("FREE_INITIAL", random);
                cascades = 0;
                while (true) {
                    GameRuleCore.BoardResult result = GameRuleCore.evaluateBoard(board.tokens(), bs, bl, rpx);
                    int scat = GameRuleCore.scatterCount(result.tokens());
                    if (scat >= 4) throw new Rejected();
                    facts.add(Step.fact(facts.size(), BigDecimal.ZERO, bs, bl,
                        board.tokens(), board.silver(), board.gold()));
                    if (result.winAmount().signum() <= 0) break;
                    if (++cascades > maximumCascades) throw new Rejected();
                    rpx = Math.min(GameRuleCore.MAX_RPX, rpx + 2);
                    board = refill(board, result, random);
                }
            }
        }
        CompleteRound result = GameRuleCore.materialize(paid, bs, bl, facts);
        if (mode != RoundMode.LOSS && result.payout().signum() <= 0) throw new Rejected();
        return result;
    }

    private Cascade.Board initial(String entry, SecureRandom random) {
        List<String> rskl = new ArrayList<>();
        for (int c = 0; c < 6; c++) rskl.addAll(model().drawReel(entry, c, random));
        if (!GameRuleCore.legalSpecials(rskl)) throw new Rejected();
        GameRuleCore.parse(rskl);
        List<Integer> silver = new ArrayList<>();
        for (Token token : GameRuleCore.parse(rskl)) {
            if (token.height() >= 2 && token.reel() > 0 && token.reel() < 5
                && !"Wild".equals(token.symbol()) && !"Scat".equals(token.symbol())
                && random.nextInt(959) < 364) silver.add(token.coord());
        }
        return new Cascade.Board(rskl, silver, List.of());
    }

    private Cascade.Board refill(Cascade.Board previous, GameRuleCore.BoardResult result, SecureRandom random) {
        Cascade.Board next = Cascade.next(previous, result, random, model());
        if (!GameRuleCore.legalSpecials(next.tokens())) throw new Rejected();
        GameRuleCore.parse(next.tokens());
        return next;
    }

    private static final class Rejected extends RuntimeException {
        private Rejected() { super(null, null, false, false); }
    }

    public List<String> lossBoardCandidate(SecureRandom random){
        List<String> raw=new ArrayList<>();
        java.util.Set<String> first=new java.util.HashSet<>();
        for(int i=0;i<5;i++){
            String symbol=GameRuleCore.TRANSFORM_SYMBOLS.get(random.nextInt(GameRuleCore.TRANSFORM_SYMBOLS.size()));
            first.add(symbol);
            raw.add("1"+symbol);
        }
        List<String> rest=new ArrayList<>();
        for(String symbol:GameRuleCore.TRANSFORM_SYMBOLS)if(!first.contains(symbol))rest.add(symbol);
        if(rest.isEmpty())rest.addAll(GameRuleCore.TRANSFORM_SYMBOLS);
        for(int reel=1;reel<6;reel++){
            int tokens=reel==5?5:6;
            List<String> pool=reel==1?rest:GameRuleCore.TRANSFORM_SYMBOLS;
            for(int i=0;i<tokens;i++)raw.add("1"+pool.get(random.nextInt(pool.size())));
        }
        return List.copyOf(raw);
    }
    private boolean validLossBoard(List<String> b){return GameRuleCore.legalSpecials(b)
        &&GameRuleCore.evaluateBoard(b,new BigDecimal("0.02"),1,1).winAmount().signum()==0;}
}
