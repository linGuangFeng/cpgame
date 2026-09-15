package com.cpgame.clubgoddess.codec;

import com.cpgame.clubgoddess.core.GameModels.*;
import com.cpgame.clubgoddess.core.GameRuleCore;
import com.cpgame.clubgoddess.core.RoundVerifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compact ASCII minimum fact: version, rules hash, round key and all delivery boards. */
public final class MinimalRoundFactCodec {
    private static final BigDecimal BET=new BigDecimal("0.01"),BALANCE=new BigDecimal("1000000");private static final String PREFIX="CG2~";
    public String encode(RoundBundle round){RoundVerifier.verify(round);StringBuilder b=new StringBuilder(PREFIX).append(GameRuleCore.RULES_HASH.substring(7)).append('~').append(round.roundKey()).append('~');for(int i=0;i<round.deliveries().size();i++){if(i>0)b.append('.');for(int v:round.deliveries().get(i).result().props().prop())b.append(Character.forDigit(v,36));}String out=b.toString().toUpperCase(Locale.ROOT);for(byte value:out.getBytes(StandardCharsets.US_ASCII))if(value<33||value>126)throw new IllegalArgumentException("non ASCII member");return out;}
    public RoundFact decode(String member){if(member==null||member.startsWith("{")||member.startsWith("["))throw new IllegalArgumentException("member must be compact ASCII");String[]p=member.split("~",4);if(p.length!=4||!"CG2".equals(p[0])||!GameRuleCore.RULES_HASH.substring(7).equalsIgnoreCase(p[1])||p[2].isBlank())throw new IllegalArgumentException("member header");List<List<Integer>>boards=new ArrayList<>();for(String raw:p[3].split("\\.")){if(raw.length()!=15)throw new IllegalArgumentException("board length");List<Integer>board=new ArrayList<>();for(char c:raw.toCharArray()){int v=Character.digit(c,36);if(v<1||v>10)throw new IllegalArgumentException("symbol");board.add(v);}boards.add(List.copyOf(board));}return new RoundFact(p[2],List.copyOf(boards));}
    public RoundBundle rebuild(String member){RoundFact f=decode(member);return GameRuleCore.rebuildRound(f.roundKey(),f.boards(),BET,10,BALANCE);}public RoundBundle verify(String member){RoundBundle r=rebuild(member);RoundVerifier.verify(r);return r;}public record RoundFact(String roundKey,List<List<Integer>>boards){}
}
