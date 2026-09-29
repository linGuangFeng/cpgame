package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaidMultiplierConfigTest {
    @TempDir Path temp;
    private Path config(String... values) throws Exception {
        StringBuilder s=new StringBuilder("redis.host=127.0.0.1\n");
        for(int i=0;i<values.length;i++)s.append(PaidMultiplierPolicy.key(i+1)).append('=').append(values[i]).append('\n');
        Path path=temp.resolve("generator.properties");Files.writeString(path,s);return path;
    }
    @Test void defaultIntegerWeightsLoadAndChooseAtBoundaries() throws Exception {
        var policy=RedisDirectLoader.LoaderConfig.load(config()).multiplierPolicy();
        double[] tickets={0,0.4999,0.5,0.7499,0.75,0.8749,0.875,0.9499,0.95,0.9999};
        int[] expected={1,1,2,2,3,3,4,4,5,5};
        for(int i=0;i<tickets.length;i++) {
            final double ticket=tickets[i];
            assertEquals(expected[i],policy.choose(new Random(){@Override public double nextDouble(){return ticket;}}));
        }
    }
    @Test void configuredPolicyReachesFactoryForOpeningAndCascade() throws Exception {
        int[] weights=new int[23];Arrays.fill(weights,0,10,100);weights[11]=300;
        for(int target : new int[]{1,5}) {
            String[] weightsConfig={"0","0","0","0","0"};weightsConfig[target-1]="100";
            var policy=RedisDirectLoader.LoaderConfig.load(config(weightsConfig)).multiplierPolicy();
            var factory=new CompleteRoundFactory(policy);var random=new Random(1830);int balls=0;
            for(int n=0;n<200;n++) {
                var r=factory.generate(random,10,30,weights,weights,weights);
                for(var spin:r.fact().spins())for(var page:spin)for(int symbol:page.prop()) {
                    if(symbol>=12) {balls++;assertEquals(15,symbol);assertEquals(5,target);}
                }
            }
            if(target==1)assertEquals(0,balls);else assertTrue(balls>0);
        }
    }
    @Test void malformedWeightsFailBeforeRedisConnection() throws Exception {
        for(String[] values:List.of(new String[]{"12.5"},
                new String[]{"-1","26","25","25","25"},new String[]{"NaN"},new String[]{"50%"},
                new String[]{"2147483648"},new String[]{"0","0","0","0","0"})) {
            Path path=config(values);
            assertThrows(IllegalArgumentException.class,()->RedisDirectLoader.LoaderConfig.load(path));
        }
    }
    @Test void arbitraryTotalAndLargeWeightsAccepted() throws Exception {
        var p=RedisDirectLoader.LoaderConfig.load(config("1","2","0","0","0")).multiplierPolicy();
        assertEquals(2,p.choose(new Random(){@Override public double nextDouble(){return 0.5;}}));
        var large=RedisDirectLoader.LoaderConfig.load(config("2147483647","2147483647","0","0","0")).multiplierPolicy();
        assertEquals(2,large.choose(new Random(){@Override public double nextDouble(){return 0.75;}}));
    }
}
