package com.hd.pg.appapi.business.model.cpgame.hotpot;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HotpotGenerationPolicyTest {
    private static HotpotBoard winner() {
        int[] p = new int[36];
        for (int i=0; i<36; i++) p[i] = i<8 ? 1 : 2+(i%9);
        return new HotpotBoard(p);
    }
    @Test void repeatedEliminationDecaysOncePerSymbolAndNewSpinResets() {
        int[] w = new int[23]; w[0]=100; w[1]=100;
        var g = new HotpotBoardGenerator(new Random(183021),w,w,w);
        var b = winner(); var e = HotpotResultUtil.evaluate(b);
        assertEquals(1,e.getWins().size());
        g.cascade(b,e,HotpotSymbolScene.PAID_CASCADE);
        assertShare(g, 1.0/6); // 20 : 100, not one reduction for each of 8 cells
        g.cascade(b,e,HotpotSymbolScene.PAID_CASCADE);
        assertShare(g, 1.0/26); // 4 : 100
        g.cascade(b,e,HotpotSymbolScene.PAID_CASCADE);
        assertShare(g, 1.0/101); // 0.8 clamped to 1
        g.cascade(b,e,HotpotSymbolScene.PAID_CASCADE);
        assertShare(g, 1.0/101); // repeated decay cannot go below 1
        g.generate(HotpotSymbolScene.FREE_START);
        assertShare(g, 0.5);
    }
    private static void assertShare(HotpotBoardGenerator g,double expected) {
        int hit=0,n=100000;
        for(int i=0;i<n;i++) if(g.nextSymbol(HotpotSymbolScene.PAID_CASCADE)==1)hit++;
        assertEquals(expected,hit/(double)n,0.006);
    }
    @Test void eachPaidMultiplierOpportunityChoosesExactlyOnce() {
        int[] w = new int[23]; w[0]=1; w[11]=1;
        // Alternate selecting the ordinary category and multiplier category.
        // On a multiplier opportunity enumerate all 200 second-stage tickets.
        var rng = new Random() {
            int draws, ticket;
            boolean choosingMultiplier;
            @Override public int nextInt(int bound) {
                assertEquals(2,bound);
                int category=(draws++)%2;
                choosingMultiplier=category==1;
                return category;
            }
            @Override public double nextDouble() {
                if (choosingMultiplier) { choosingMultiplier=false; return ((ticket++)%200)/200.0; }
                return 0.25;
            }
        };
        var g = new HotpotBoardGenerator(rng,w,w,w);
        int[] count = new int[24];
        for(int i=0;i<400;i++)count[g.nextSymbol(HotpotSymbolScene.PAID_START)]++;
        assertEquals(300,count[1]); // 200 ordinary + 100 x1 replacements
        assertEquals(50,count[12]); assertEquals(25,count[13]);
        assertEquals(15,count[14]); assertEquals(10,count[15]);
    }
    @Test void multipleBallsRemainPossibleAndSurviveCascade() {
        int[] w = new int[23]; w[0]=1; w[11]=10000;
        var g=new HotpotBoardGenerator(new Random(183022),w,w,w);
        int[] p=winner().getProp();p[30]=12;p[31]=15;
        var b=new HotpotBoard(p);
        int[] next=g.cascade(b,HotpotResultUtil.evaluate(b),HotpotSymbolScene.PAID_CASCADE).getProp();
        assertEquals(12,next[30]); assertEquals(15,next[31]);
        assertTrue(Arrays.stream(next).filter(x->x>=12).count()>2);
    }
    @Test void cascadeNeverAddsScatterInEitherMode() {
        int[] w = new int[23]; w[1]=1; w[10]=100000;
        var g=new HotpotBoardGenerator(new Random(183024),w,w,w);
        for(var scene: List.of(HotpotSymbolScene.PAID_CASCADE, HotpotSymbolScene.FREE_CASCADE)) {
            for(int i=0;i<10000;i++) assertNotEquals(11,g.nextSymbol(scene));
            var b=winner();
            assertEquals(0,Arrays.stream(g.cascade(b,HotpotResultUtil.evaluate(b),scene).getProp())
                    .filter(x->x==11).count());
        }
    }
    @Test void freeMultiplierRangeIsUnchanged() {
        int[] w=new int[23];w[0]=1;w[22]=10000;
        var g=new HotpotBoardGenerator(new Random(183023),w,w,w);
        for(int i=0;i<1000;i++) {
            int s=g.nextSymbol(HotpotSymbolScene.FREE_START);
            assertTrue(s==1 || s==23);
        }
    }
}
