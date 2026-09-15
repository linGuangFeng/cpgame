package com.cpgame.fiesta;

import java.util.*;

/** EF1：只保存无法重算的牌面、模式、倍率材质和重转列。 */
public final class RoundCodec {
    private static final int[] VALUES={2,3,5,10,20,50};
    private static char symbol(int v){ for(int i=0;i<VALUES.length;i++)if(VALUES[i]==v)return (char)('A'+i);throw new IllegalArgumentException(); }
    private static int symbol(char c){ int i=c-'A';if(i<0||i>=VALUES.length)throw new IllegalArgumentException("symbol code");return VALUES[i]; }
    private static char material(int v){ return switch(v){case 0->'0';case 2->'A';case 3->'B';case 5->'C';case 10->'D';default->throw new IllegalArgumentException("material");}; }
    private static int material(char c){ return switch(c){case '0'->0;case 'A'->2;case 'B'->3;case 'C'->5;case 'D'->10;default->throw new IllegalArgumentException("material code");}; }
    public String encode(GameRound round){
        return isIndependentLoss(round) ? "EF1:#" : encodeFull(round);
    }
    public String encodeFull(GameRound round){
        StringJoiner join=new StringJoiner(".","EF1:","");
        for(RoundState s:round.states()){
            StringBuilder b=new StringBuilder(21); b.append(s.mode()==RoundState.Mode.NORMAL?'N':s.mode()==RoundState.Mode.RESPIN_UNTIL_WIN?'R':'M');
            for(int v:s.board())b.append(symbol(v)); for(int v:s.multipliers())b.append(material(v)); b.append(Character.forDigit(s.respinColumn()+1,36)); join.add(b);
        } return join.toString();
    }
    public GameRound decode(String member){
        if ("EF1:#".equals(member)) return LossHolder.FACTORY.ordinary(false);
        if(member==null||!member.startsWith("EF1:"))throw new IllegalArgumentException("codec"); String[] parts=member.substring(4).split("\\.", -1); List<RoundState> states=new ArrayList<>();
        for(int i=0;i<parts.length;i++){String p=parts[i];if(p.length()!=20)throw new IllegalArgumentException("state width");RoundState.Mode mode=switch(p.charAt(0)){case'N'->RoundState.Mode.NORMAL;case'R'->RoundState.Mode.RESPIN_UNTIL_WIN;case'M'->RoundState.Mode.MULTIPLIER_STICKY;default->throw new IllegalArgumentException("mode");};int[] board=new int[9],mul=new int[9];for(int x=0;x<9;x++)board[x]=symbol(p.charAt(1+x));for(int x=0;x<9;x++)mul[x]=material(p.charAt(10+x));int col=Character.digit(p.charAt(19),36)-1;int[] added=i==0?new int[0]:added(states.get(i-1).multipliers(),mul);states.add(new RoundState(mode,board,mul,added,i==parts.length-1?0:1,col,board[0]));}
        return new GameRound(states);
    }
    private static boolean isIndependentLoss(GameRound round) {
        if (round.states().size() != 1) return false;
        RoundState state = round.states().get(0);
        return state.mode() == RoundState.Mode.NORMAL && state.remaining() == 0
                && state.respinColumn() == -1 && Arrays.stream(state.multipliers()).allMatch(v -> v == 0)
                && state.addedPositions().length == 0
                && !CandidateFactory.structuralFeature(state.board())
                && new GameRuleCore().payoutUnits(state.board()) == 0;
    }

    private static final class LossHolder {
        private static final CandidateFactory FACTORY = create();
        private static CandidateFactory create() {
            Properties weights = new Properties();
            for (int symbol : GameRuleCore.SYMBOLS) {
                weights.setProperty("generation.symbol.initial." + symbol, "1");
                weights.setProperty("generation.symbol.respin-reel." + symbol, "1");
            }
            for (int multiplier : new int[]{2, 3, 5, 10}) {
                weights.setProperty("generation.material.multiplier." + multiplier, "1");
            }
            return new CandidateFactory(new java.security.SecureRandom(), new GameRuleCore(), weights);
        }
    }

    private int[] added(int[] before,int[] after){return java.util.stream.IntStream.range(0,9).filter(i->before[i]==0&&after[i]>0).toArray();}
}
