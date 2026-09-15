package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CompleteRoundFactory {
    private final ZeroLossSupport<List<String>> lossBoards;

    private final int maximumCascades;
    private final EmpiricalColumnModel model = EmpiricalColumnModel.instance();

    public CompleteRoundFactory(int maximumCascades, int maximumSpecialSpins) {
        if (maximumCascades < 1) throw new IllegalArgumentException("cascade limit");
        this.maximumCascades = Math.min(GameRuleCore.MAX_CASCADES, maximumCascades);
        if (maximumSpecialSpins < GameRuleCore.FREE_GRANT) {
            throw new IllegalArgumentException("special spin limit must allow 10 free spins");
        }
        SecureRandom defaultsRandom=new SecureRandom();
        lossBoards=new ZeroLossSupport<>(()->lossBoardCandidate(defaultsRandom),this::validLossBoard,List::copyOf);
    }

    public CompleteRound generate(RoundMode requested, SecureRandom random, BigDecimal betSize, int betLevel) {
        Objects.requireNonNull(random);
        GameRuleCore.validateBet(betSize, betLevel);
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
        for (int c = 0; c < 6; c++) rskl.addAll(model.drawReel(entry, c, random));
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
        Cascade.Board next = Cascade.next(previous, result, random, model);
        if (!GameRuleCore.legalSpecials(next.tokens())) throw new Rejected();
        GameRuleCore.parse(next.tokens());
        return next;
    }

    private static final class Rejected extends RuntimeException {
        private Rejected() { super(null, null, false, false); }
    }

    public List<String> lossBoardCandidate(SecureRandom random){
        List<String> raw=new ArrayList<>();for(int c=0;c<6;c++)raw.addAll(model.drawReel("PAID_INITIAL",c,random));
        List<Token> tokens=GameRuleCore.parse(raw);int scat=0,wild=0,h1=0;
        for(int i=0;i<tokens.size();i++){
            Token t=tokens.get(i);String symbol=t.symbol();
            boolean replace=symbol.equals("Scat")&&++scat>3 || symbol.equals("Wild")&&(t.reel()<2||++wild>2)
                || symbol.equals("H1")&&++h1>2;
            if(replace)raw.set(i,t.height()+GameRuleCore.TRANSFORM_SYMBOLS.get(random.nextInt(GameRuleCore.TRANSFORM_SYMBOLS.size())));
        }
        tokens=GameRuleCore.parse(raw);java.util.Set<String> first=new java.util.HashSet<>();
        for(Token t:tokens)if(t.reel()==0)first.add(t.symbol());
        List<String> allowed=new ArrayList<>();for(String v:GameRuleCore.TRANSFORM_SYMBOLS)if(!first.contains(v))allowed.add(v);
        for(int i=0;i<tokens.size();i++){Token t=tokens.get(i);if(t.reel()==1&&first.contains(t.symbol()))raw.set(i,t.height()+allowed.get(random.nextInt(allowed.size())));}
        return List.copyOf(raw);
    }
    private boolean validLossBoard(List<String> b){return GameRuleCore.legalSpecials(b)
        &&GameRuleCore.evaluateBoard(b,new BigDecimal("0.02"),1,1).winAmount().signum()==0;}
}
