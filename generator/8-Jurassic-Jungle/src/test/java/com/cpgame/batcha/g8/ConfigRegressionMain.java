package com.cpgame.batcha.g8;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import java.security.SecureRandom;

public final class ConfigRegressionMain {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static Path config(String body) throws Exception {
        Path p=Files.createTempFile("generator-config-", ".properties");
        Files.writeString(p,body);return p;
    }
    static void rejects(String body) throws Exception {
        Path p=config(body);
        try { LoaderConfig.load(p); throw new AssertionError("Accepted invalid config"); }
        catch(IllegalArgumentException expected) {} finally {Files.delete(p);}
    }
    public static void main(String[] args) throws Exception {
        LoaderConfig shipped=LoaderConfig.load(Path.of(args[0]));
        check(shipped.outputLimits().batchSize==100,"batch missing");
        check(shipped.outputLimits().specialCap==50,"custom retention changed");
        check(shipped.outputLimits().symbolWeights.size()==18,"weights missing");
        rejects("generation.batch-size=0\n");
        rejects("generation.normal-count=3\n");
        rejects("generation.normal-count=3\ngeneration.special-count=4\ngeneration.total-members=8\n");
        rejects("generation.symbol.BAD.normal-weight=2\n");
        rejects("generation.symbol.S2.normal-weight=-1\n");
        rejects("redis.ssl=perhaps\n");
        rejects("generation.seed=1\n");
        Properties props=new Properties();props.setProperty("generation.special-min-win-multiplier","50");
        props.setProperty("generation.special-max-win-multiplier","100");
        var limits=new LoaderLimits(props);
        check(!limits.accepts(true,new BigDecimal("49.9")),"range minimum ignored");
        check(limits.accepts(true,new BigDecimal("50")),"inclusive minimum rejected");
        check(!limits.accepts(true,new BigDecimal("100.1")),"range maximum ignored");
        String body="generation.modes=LOSS\ngeneration.total-members=7\ngeneration.normal-count=7\ngeneration.special-count=0\ngeneration.batch-size=3\ngeneration.max-candidates=20\nredis.username=test-acl\nredis.ssl=true\ngeneration.max-members-per-multiplier=2\n";
        Path p=config(body);LoaderConfig c=LoaderConfig.load(p);Files.delete(p);
        check(c.outputLimits().username.equals("test-acl") && c.outputLimits().ssl,"connection settings lost");
        Memory store=new Memory();var summary=LoaderMain.run(c,store);
        check(summary.written()==7 && summary.candidates()==7,"count mismatch");
        check(store.batches.equals(List.of(3,3,1)),"batch or remainder lost: "+store.batches);
        check(store.members.stream().allMatch(m->!m.special()&&m.ratio()==0&&m.maximumMembers()==2),"pool/retention mismatch");
        for(var member:store.members) new IndependentVerifier(new BigDecimal("20000"),47).verify(new MemberCodec().decode(member.member()));
        var random=new SecureRandom();var factory=new CompleteRoundFactory(47);
        var result=new ResultUtil();
        for(int i=0;i<1000;i++) check(result.evaluate(factory.lossBoardCandidate(random),new BigDecimal("0.05"),4).winAmount().signum()==0,"loss failed");
        for (RoundMode mode : shipped.modes()) for (int sample = 0; sample < 20; sample++) {
            var round = factory.generate(mode, random, new BigDecimal("0.05"), 4);
            check(round.mode() == mode, "wrong mode " + mode);
            new IndependentVerifier(new BigDecimal("20000"),47).verifyCodecRoundTrip(round, new MemberCodec());
        }
        Path separate = config("generation.modes=LOSS,DRAGON\ngeneration.total-members=5\ngeneration.normal-count=3\ngeneration.special-count=2\ngeneration.batch-size=2\n");
        Memory mixed = new Memory();
        LoaderMain.run(LoaderConfig.load(separate), mixed); Files.delete(separate);
        check(mixed.members.stream().filter(RedisRoundStore.PendingMember::special).count() == 2, "special quota ignored");
        check(mixed.members.stream().filter(m -> !m.special()).count() == 3, "normal quota ignored");
        check(mixed.batches.equals(List.of(2,2,1)), "mixed batches wrong");
        var adjusted=EmpiricalColumnModel.configured(Map.of("generation.symbol.S2.normal-weight",
                EmpiricalColumnModel.observedWeight("S2", false) * 4.0));
        int baseCount=0, adjustedCount=0;
        for(int i=0;i<10000;i++) {
            baseCount+=Collections.frequency(EmpiricalColumnModel.instance().draw("PAID_INITIAL",0,5,random),"S2");
            adjustedCount+=Collections.frequency(adjusted.draw("PAID_INITIAL",0,5,random),"S2");
        }
        check(adjustedCount>baseCount,"symbol weight has no effect: "+baseCount+" -> "+adjustedCount);
        System.out.println("PASS config, invalid values, connection options, batch 3/3/1, counts, retention, codec, 1000 losses, symbol weights "+baseCount+" -> "+adjustedCount);
    }
    static final class Memory implements RedisRoundStore {
        List<Integer> batches=new ArrayList<>();List<PendingMember> members=new ArrayList<>();
        public void writeBatch(List<PendingMember> items) {batches.add(items.size());members.addAll(items);}
        public void writeMember(boolean special,int ratio,byte[] member,int cap) {throw new AssertionError("not batched");}
        public List<String> ratios(boolean special) {return List.of();}
        public long listLength(boolean special,int ratio) {return 0;}
        public Optional<byte[]> popMember(boolean special,int ratio) {return Optional.empty();}
        public Optional<byte[]> readMember(boolean special,int ratio,int offset) {return Optional.empty();}
        public void close() {}
    }
}
