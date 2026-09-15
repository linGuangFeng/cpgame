package com.cpgame.beachfun.core;

import java.util.*;

/** BF2 carries visible symbol/gold facts in printable ASCII. Payouts and cell identities are recomputed. */
public final class MinimalRoundFactCodec {
    private final GameRuleCore rules=new GameRuleCore();
    public String encode(GameRuleCore.CompleteRound r){
        rules.validate(r);ResultUtil.verify(r);
        StringBuilder b=new StringBuilder("BF2|").append(r.factId()).append('|')
            .append(r.win()?'W':'L').append(r.freeFeature()?'F':'-').append(r.cascadeFeature()?'C':'-').append(r.goldFeature()?'G':'-')
            .append('|').append(r.totalUnits()).append('|');
        for(int di=0;di<r.deliveries().size();di++){
            if(di>0)b.append('/');var d=r.deliveries().get(di);
            b.append(d.free()?1:0).append(',').append(d.awardedFreeSpins()).append(',').append(d.remainingFreeSpins());
            for(var c:d.cascades()){
                b.append('~');for(int v:c.board())b.append(Character.forDigit(v,36));
                long bits=0;boolean[]g=c.gold();for(int p=0;p<20;p++)if(g[p])bits|=1L<<p;
                b.append('.').append(Long.toHexString(bits));
            }
        }
        String s=b.toString();for(char c:s.toCharArray())if(c<32||c>126)throw new IllegalArgumentException("ASCII member required");
        return s;
    }
    public GameRuleCore.CompleteRound decode(String s){
        try{
            if(s==null||s.length()>20000)throw new IllegalArgumentException("member size");
            for(char c:s.toCharArray())if(c<32||c>126)throw new IllegalArgumentException("member ASCII");
            String[]h=s.split("\\|",5);
            if(h.length!=5||!h[0].equals("BF2")||!h[1].matches("[a-zA-Z0-9-]{1,64}")||!h[2].matches("[WL][F-][C-][G-]"))throw new IllegalArgumentException("BF2 header");
            List<GameRuleCore.Delivery>ds=new ArrayList<>();
            for(String raw:h[4].split("/")){
                String[]parts=raw.split("~");String[]meta=parts[0].split(",");
                if(meta.length!=3||!(meta[0].equals("0")||meta[0].equals("1")))throw new IllegalArgumentException("delivery metadata");
                boolean free=meta[0].equals("1");List<GameRuleCore.Cascade>cs=new ArrayList<>();
                for(int ci=1;ci<parts.length;ci++){
                    String[]fact=parts[ci].split("\\.");
                    if(fact.length!=2||fact[0].length()!=20)throw new IllegalArgumentException("board shape");
                    int[]b=new int[20];boolean[]g=new boolean[20];long bits=Long.parseLong(fact[1],16);
                    if(bits<0||bits>0xfffff)throw new IllegalArgumentException("gold bits");
                    for(int p=0;p<20;p++){b[p]=Character.digit(fact[0].charAt(p),36);g[p]=(bits&(1L<<p))!=0;}
                    cs.add(new GameRuleCore.Cascade(b,g,rules.evaluate(b,rules.cascadeMultiplier(free,ci-1))));
                }
                ds.add(new GameRuleCore.Delivery(free,Integer.parseInt(meta[1]),Integer.parseInt(meta[2]),cs));
            }
            String f=h[2];var r=new GameRuleCore.CompleteRound(h[1],f.charAt(0)=='W',f.charAt(1)=='F',f.charAt(2)=='C',f.charAt(3)=='G',Long.parseLong(h[3]),ds);
            rules.validate(r);ResultUtil.verify(r);return r;
        }catch(RuntimeException e){throw new IllegalArgumentException("invalid BF2 member",e);}
    }
}
