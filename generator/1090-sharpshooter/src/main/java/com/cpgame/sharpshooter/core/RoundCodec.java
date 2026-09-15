package com.cpgame.sharpshooter.core;

import java.util.*;

/** SS1 stores only state facts that cannot be recomputed; every member is printable ASCII. */
public final class RoundCodec {
    public String encode(CompleteRound round){
        StringJoiner spins=new StringJoiner(";","SS1|","");
        for(CompleteRound.Spin spin:round.spins()){
            StringJoiner cascades=new StringJoiner(".");for(CompleteRound.Cascade c:spin.cascades())cascades.add(board(c));
            spins.add((spin.paid()?"P":"F")+","+spin.freeTotal()+","+spin.freeRemaining()+","+spin.newFree()+","+cascades);
        }return spins.toString();
    }
    public CompleteRound decode(String member){
        if(member==null||!member.startsWith("SS1|"))throw new IllegalArgumentException("SS1 member required");List<CompleteRound.Spin> spins=new ArrayList<>();
        for(String raw:member.substring(4).split(";")){String[] p=raw.split(",",5);if(p.length!=5)throw new IllegalArgumentException("spin width");List<CompleteRound.Cascade> cascades=new ArrayList<>();for(String c:p[4].split("\\."))cascades.add(parseBoard(c));spins.add(new CompleteRound.Spin(p[0].equals("P"),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),cascades));}
        return new CompleteRound(spins);
    }
    private String board(CompleteRound.Cascade c){StringBuilder b=new StringBuilder(26);for(int s:c.symbols())b.append((char)(s==10?'A':'0'+s));int mask=0;boolean[] gold=c.gold();for(int i=0;i<gold.length;i++)if(gold[i])mask|=1<<i;return b.append('~').append(String.format(Locale.ROOT,"%05X",mask)).toString();}
    private CompleteRound.Cascade parseBoard(String raw){if(raw.length()!=26||raw.charAt(20)!='~')throw new IllegalArgumentException("board width");int[] symbols=new int[20];for(int i=0;i<20;i++){char c=raw.charAt(i);symbols[i]=c=='A'?10:c-'0';}int mask=Integer.parseInt(raw.substring(21),16);boolean[] gold=new boolean[20];for(int i=0;i<20;i++)gold[i]=(mask&(1<<i))!=0;return new CompleteRound.Cascade(symbols,gold);}
}
