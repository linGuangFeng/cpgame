package com.cpgame.batchd.treasurehunt.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class MinimalRoundFactCodec {
    private static final String PREFIX="TH40"; private static final Pattern BOARD=Pattern.compile("[1-7]{10}");
    private MinimalRoundFactCodec(){}
    public static String encode(GameRuleCore.CompleteRound round){char mode=switch(round.mode()){case ORDINARY->'N';case TREASURE_HUNT->'T';case ALL_REELS_MULTIPLIER->'A';};StringBuilder s=new StringBuilder(PREFIX).append(mode).append(':');for(int n=0;n<round.steps().size();n++){if(n>0)s.append('/');for(int v:round.steps().get(n).symbols())s.append(v);}return s.toString();}
    public static GameRuleCore.CompleteRound decode(String text){if(text==null||!text.startsWith(PREFIX)||text.length()<16||text.charAt(5)!=':')throw new IllegalArgumentException("invalid round fact");GameRuleCore.Mode mode=switch(text.charAt(4)){case'N'->GameRuleCore.Mode.ORDINARY;case'T'->GameRuleCore.Mode.TREASURE_HUNT;case'A'->GameRuleCore.Mode.ALL_REELS_MULTIPLIER;default->throw new IllegalArgumentException("invalid mode");};String[] raw=text.substring(6).split("/");List<GameRuleCore.Step>steps=new ArrayList<>();for(String board:raw){if(!BOARD.matcher(board).matches())throw new IllegalArgumentException("invalid board fact");int[]b=new int[10];for(int i=0;i<10;i++)b[i]=board.charAt(i)-'0';steps.add(new GameRuleCore.Step(b));}return new GameRuleCore.CompleteRound(mode,steps);}
}
