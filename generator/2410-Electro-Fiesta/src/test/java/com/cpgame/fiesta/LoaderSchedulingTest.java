package com.cpgame.fiesta;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LoaderSchedulingTest {
    private Properties config() throws Exception {
        Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of("dist/generator.properties"))){p.load(r);}return p;
    }
    private String family(GameRound r){
        var states=r.states();var last=states.get(states.size()-1);
        if(last.mode()==RoundState.Mode.NORMAL)return "DIRECT";
        if(last.mode()==RoundState.Mode.RESPIN_UNTIL_WIN)return "RESPIN";
        return states.get(0).mode()==RoundState.Mode.RESPIN_UNTIL_WIN?"RESPIN_FULL":"FULL";
    }
    @Test void fullscreenGoesToMaryAndNormalKeepsConfiguredRespinShare() throws Exception {
        Properties p=config();p.setProperty("generation.max-members-per-multiplier","40");p.setProperty("generation.special-max-members-per-multiplier","12");
        Map<Integer,int[]> normal=new TreeMap<>();Map<Integer,Map<String,Integer>> mary=new TreeMap<>();
        var summary=RedisLoaderMain.generate(p,(r,a,special)->{
            assertTrue(RedisKeys.allowedInPool(special,a.outcome()));
            if(a.outcome()==RoundOutcome.MULTIPLIER_STICKY)assertTrue(special);
            if(a.outcome()==RoundOutcome.ORDINARY_WIN)assertFalse(special);
            if(special){
                assertTrue(new LoaderLimits(p).accepts(true,a.integerMultiplier()));
                mary.computeIfAbsent(a.integerMultiplier(),k->new HashMap<>()).merge(family(r),1,Integer::sum);
            }else{
                assertTrue(new LoaderLimits(p).accepts(false,a.integerMultiplier()));
                int[] c=normal.computeIfAbsent(a.integerMultiplier(),k->new int[2]);
                if("RESPIN".equals(family(r)))c[1]++;else c[0]++;
            }
        });
        assertTrue(summary.special()>0);
        assertTrue(mary.keySet().stream().anyMatch(n->n>200));
        assertTrue(normal.keySet().stream().noneMatch(n->n>200));
        assertTrue(mary.values().stream().anyMatch(m->m.containsKey("FULL")||m.containsKey("RESPIN_FULL")));
        double share=EnumerationLoader.floorWeightedShare(normal);
        assertEquals(0.19,share,0.04);
        int maryRespin=0,maryMixed=0;
        for(var m:mary.values()){
            int respin=m.getOrDefault("RESPIN",0);
            int full=m.getOrDefault("FULL",0)+m.getOrDefault("RESPIN_FULL",0);
            if(respin>0&&full>0){maryRespin+=respin;maryMixed+=respin+full;}
        }
        assertTrue(maryMixed>0);
        assertEquals(0.19,maryRespin/(double)maryMixed,0.08);
    }
    @Test void zeroMaryRespinShareWritesOnlyFullscreenToMary() throws Exception {
        Properties p=config();p.setProperty("generation.mary-respin-share","0");p.setProperty("generation.max-members-per-multiplier","8");p.setProperty("generation.special-max-members-per-multiplier","6");
        Set<String> maryFamilies=new HashSet<>();Set<String> normalFamilies=new HashSet<>();
        RedisLoaderMain.generate(p,(r,a,special)->{
            if(special){maryFamilies.add(family(r));assertNotEquals("RESPIN",family(r));assertEquals(RoundOutcome.MULTIPLIER_STICKY,a.outcome());}
            else normalFamilies.add(family(r));
        });
        assertEquals(Set.of("FULL","RESPIN_FULL"),maryFamilies);
        assertTrue(normalFamilies.contains("RESPIN"));
        assertTrue(normalFamilies.contains("DIRECT"));
    }
    @Test void specialRangeAppliesToMaryAndNormalRangeToDirectRespin() throws Exception {
        Properties p=config();p.setProperty("generation.normal-min-win-multiplier","100");p.setProperty("generation.normal-max-total-multiplier","100");
        p.setProperty("generation.special-min-win-multiplier","100");p.setProperty("generation.special-max-total-multiplier","100");
        p.setProperty("generation.max-members-per-multiplier","8");p.setProperty("generation.special-max-members-per-multiplier","8");
        Set<String> families=new HashSet<>();var summary=RedisLoaderMain.generate(p,(r,a,special)->{assertEquals(100,a.integerMultiplier());families.add(family(r));});
        assertTrue(summary.normal()>0);assertTrue(summary.special()>0);
        assertTrue(families.contains("DIRECT"));assertTrue(families.contains("RESPIN"));
        assertTrue(families.contains("FULL"));assertTrue(families.contains("RESPIN_FULL"));
    }
    @Test void unreachableRangeTerminates() throws Exception {
        Properties p=config();p.setProperty("generation.normal-min-win-multiplier","201");p.setProperty("generation.normal-max-total-multiplier","201");
        p.setProperty("generation.special-min-win-multiplier","201");p.setProperty("generation.special-max-total-multiplier","201");
        var summary=RedisLoaderMain.generate(p,(r,a,special)->fail());
        assertEquals(0,summary.normal());assertEquals(0,summary.special());
    }
    @Test void zeroRangeAndSinkFailure() throws Exception {
        Properties p=config();p.setProperty("generation.normal-min-win-multiplier","0");p.setProperty("generation.normal-max-total-multiplier","0");p.setProperty("generation.special-min-win-multiplier","0");p.setProperty("generation.special-max-total-multiplier","0");p.setProperty("generation.max-members-per-multiplier","3");
        assertEquals(3,RedisLoaderMain.generate(p,(r,a,special)->{assertFalse(special);assertEquals(0,a.integerMultiplier());}).normal());
        assertThrows(UnsupportedOperationException.class,()->RedisLoaderMain.generate(p,(r,a,special)->{throw new UnsupportedOperationException();}));
    }
    @Test void multiplierStickyIsMaryAndRespinMayBeEitherPool() {
        assertTrue(RedisKeys.special(RoundOutcome.MULTIPLIER_STICKY));
        assertFalse(RedisKeys.special(RoundOutcome.RESPIN_UNTIL_WIN));
        assertFalse(RedisKeys.special(RoundOutcome.ORDINARY_WIN));
        assertTrue(RedisKeys.allowedInPool(true,RoundOutcome.MULTIPLIER_STICKY));
        assertTrue(RedisKeys.allowedInPool(true,RoundOutcome.RESPIN_UNTIL_WIN));
        assertTrue(RedisKeys.allowedInPool(false,RoundOutcome.RESPIN_UNTIL_WIN));
        assertTrue(RedisKeys.allowedInPool(false,RoundOutcome.ORDINARY_WIN));
        assertFalse(RedisKeys.allowedInPool(false,RoundOutcome.MULTIPLIER_STICKY));
        assertFalse(RedisKeys.allowedInPool(true,RoundOutcome.ORDINARY_WIN));
    }
}
