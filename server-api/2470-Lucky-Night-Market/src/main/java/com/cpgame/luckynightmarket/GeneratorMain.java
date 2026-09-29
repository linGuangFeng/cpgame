package com.cpgame.luckynightmarket;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 自包含的 Windows/Linux/macOS Java Loader，正式随机源为 SecureRandom。 */
public final class GeneratorMain {
    private static void gameId(int gid){if(gid!=8002470)throw new IllegalArgumentException("This Loader only writes game 2470 keys");}
    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
    }

    public enum Pool { NORMAL, FEATURE, WHEEL }

    public static Pool pool(RoundFact.Mode mode){
        return switch(mode){
            case ORDINARY_LOSS, ORDINARY_WIN -> Pool.NORMAL;
            case LUCKY_FEATURE -> Pool.FEATURE;
            case LUCKY_WHEEL -> Pool.WHEEL;
        };
    }

    public static Pool poolOfKey(String key){
        if(key.startsWith("BetLog:108002470:")) return Pool.WHEEL;
        if(key.startsWith("MaryLog:008002470:")) return Pool.FEATURE;
        if(key.startsWith("BetLog:008002470:")) return Pool.NORMAL;
        throw new IllegalArgumentException("Unrecognized Redis list key: "+key);
    }

    public static String index(Pool pool,int gid){
        gameId(gid);
        return switch(pool){
            case NORMAL -> "PerKeyList_008002470";
            case FEATURE -> "MaryKeyList_008002470";
            case WHEEL -> "PerKeyList_108002470";
        };
    }

    public static String key(Pool pool,int gid,long units){
        gameId(gid);
        if(units<0||units>999999)throw new IllegalArgumentException("Payout index must fit six nonnegative integer digits");
        return switch(pool){
            case NORMAL -> String.format(Locale.ROOT,"BetLog:008002470:%06d",units);
            case FEATURE -> String.format(Locale.ROOT,"MaryLog:008002470:%06d",units);
            case WHEEL -> String.format(Locale.ROOT,"BetLog:108002470:%06d",units);
        };
    }

    public static String index(boolean special,int gid){return index(special?Pool.FEATURE:Pool.NORMAL,gid);}
    public static String key(boolean special,int gid,long units){return key(special?Pool.FEATURE:Pool.NORMAL,gid,units);}

    static int retention(Properties p,Pool pool){return retention(p,pool==Pool.FEATURE);}
    static int retention(Properties p,boolean special){
        int retain=Integer.parseInt(p.getProperty(special?"retention.special-per-multiplier":"retention.normal-per-multiplier",special?"50":"300"));
        if(retain<1)throw new IllegalArgumentException("Invalid pool retention");
        return retain;
    }

    static void validateConfig(Properties p){
        if(p.containsKey("seed")||p.containsKey("random.seed"))throw new IllegalArgumentException("Formal Loader does not accept deterministic seeds");
        gameId(Integer.parseInt(p.getProperty("redis.game-id","8002470")));
        int count=Integer.parseInt(p.getProperty("generation.count","10000")),batch=Integer.parseInt(p.getProperty("generation.batch-size","100"));
        if(count<1||batch<1||batch>1000)throw new IllegalArgumentException("Invalid count/batch size");
        for(boolean special:new boolean[]{false,true}){
            retention(p,special);
            long min=Long.parseLong(p.getProperty(special?"range.special-min":"range.normal-min",special?"25":"1"));
            long max=Long.parseLong(p.getProperty(special?"range.special-max":"range.normal-max",special?"500":"375"));
            if(min<0||max<min||max>999999)throw new IllegalArgumentException("Invalid complete-round payout range");
        }
    }

    static void verifyTransaction(List<List<String>> commands,List<Object> replies)throws IOException{
        if(commands.size()<3||!commands.get(0).equals(List.of("MULTI"))||!commands.get(commands.size()-1).equals(List.of("EXEC")))throw new IllegalArgumentException("Expected bounded MULTI/EXEC batch");
        if(replies.size()!=commands.size()||!"OK".equals(replies.get(0)))throw new IOException("Redis transaction did not start");
        for(int i=1;i<replies.size()-1;i++)if(!"QUEUED".equals(replies.get(i)))throw new IOException("Redis command was not queued: "+commands.get(i).get(0));
        if(!(replies.get(replies.size()-1) instanceof List<?> results)||results.size()!=commands.size()-2)throw new IOException("Redis EXEC aborted or returned an incomplete batch");
        for(int i=0;i<results.size();i++){
            String verb=commands.get(i+1).get(0);Object result=results.get(i);
            if(verb.equals("LTRIM")?!"OK".equals(result):!(result instanceof Long n)||n<0)throw new IOException("Unexpected Redis transaction result for "+verb);
        }
    }

    public static void main(String[] args)throws Exception{
        Map<String,String> options=new HashMap<>();
        for(int i=0;i<args.length;i++){
            String arg=args[i];
            if(arg.startsWith("--config=")){options.put("--config",arg.substring(9));continue;}
            if(!arg.startsWith("--")){options.put("--config",arg);continue;}
            if(arg.equals("--no-pause"))continue;
            if(arg.equals("--validate")){options.put(arg,"true");continue;}
            if(!Set.of("--config","--count","--facts","--report").contains(arg)||i+1>=args.length||args[i+1].startsWith("--"))throw new IllegalArgumentException("Unexpected or incomplete argument: "+arg);
            options.put(arg,args[++i]);
        }

        Properties p=new Properties();
        Path file=Path.of(options.getOrDefault("--config","generator.properties"));
        try(Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);LoaderLimits.checkKeys(p);}
        validateConfig(p);
        int gid=Integer.parseInt(p.getProperty("redis.game-id","8002470"));
        int count=Integer.parseInt(options.getOrDefault("--count",p.getProperty("generation.count","10000")));
        int batchSize=Integer.parseInt(p.getProperty("generation.batch-size","100"));
        if(count<1||batchSize<1||batchSize>1000)throw new IllegalArgumentException("Invalid count/batch size");

        DealingModel model=new DealingModel(p);
        Map<String,Integer> acceptedModes=new LinkedHashMap<>(),scenarioAttempts=new LinkedHashMap<>();
        Map<String,Integer> phaseAttempts=new LinkedHashMap<>(),phaseAccepted=new LinkedHashMap<>();
        Map<String,Integer> wheelPrizes=new TreeMap<>(),featureWinSteps=new TreeMap<>();
        Map<String,Set<Long>> payoutValues=new TreeMap<>();
        Map<String,Long> payoutMinimums=new TreeMap<>(),payoutMaximums=new TreeMap<>();
        Set<String> buckets=new TreeSet<>();
        boolean validate=options.containsKey("--validate");
        Set<String> uniqueFacts=validate?new HashSet<>():null;
        Path facts=options.containsKey("--facts")?Path.of(options.get("--facts")):null;
        int attemptedCount=0,validCount=0,redisCommittedCount=0,completedBatches=0;

        try(BufferedWriter writer=facts==null?null:Files.newBufferedWriter(facts,StandardCharsets.UTF_8);
            RedisClient redis=validate?null:new RedisClient(p)){
            List<List<String>> transaction=new ArrayList<>();transaction.add(List.of("MULTI"));
            for(int i=0;i<count;i++){
                model.useBatch(i/batchSize);
                String phase=model.symbolWeightProfile();
                phaseAttempts.merge(phase,1,Integer::sum);
                DealingModel.Attempt attempt=model.attempt();
                attemptedCount++;
                scenarioAttempts.merge(attempt.scenario().name(),1,Integer::sum);

                if(attempt.accepted()){
                    RoundFact round=attempt.round();long units=attempt.units();String member=RoundCodec.encode(round);
                    RoundCodec.verifyEquivalent(round,RoundCodec.decode(member));
                    Pool pool=pool(round.mode());String listKey=key(pool,gid,units);
                    buckets.add(listKey);acceptedModes.merge(round.mode().name(),1,Integer::sum);phaseAccepted.merge(phase,1,Integer::sum);
                    payoutValues.computeIfAbsent(round.mode().name(),ignored->new TreeSet<>()).add(units);
                    payoutMinimums.merge(round.mode().name(),units,Math::min);payoutMaximums.merge(round.mode().name(),units,Math::max);
                    if(round.mode()==RoundFact.Mode.LUCKY_WHEEL)wheelPrizes.merge(Integer.toString(round.steps().get(0).wheelMultiplier()),1,Integer::sum);
                    if(round.mode()==RoundFact.Mode.LUCKY_FEATURE)featureWinSteps.merge(Integer.toString(RuleBasedBoardGenerator.winningStepCount(round)),1,Integer::sum);
                    if(uniqueFacts!=null)uniqueFacts.add(member);
                    validCount++;

                    if(writer!=null){
                        List<Object> steps=new ArrayList<>();
                        for(var step:round.steps())steps.add(Json.map("ps",step.ps(),"muls",step.muls(),"wem",step.wheelMultiplier(),"units",ResultUtil.evaluate(step,round.feature()).units()));
                        writer.write(Json.stringify(Json.map("attempt",i+1,"weightPhase",phase,"scenario",attempt.scenario().name(),"mode",round.mode().name(),"units",units,"steps",steps,"member",member)));writer.newLine();
                    }
                    if(redis!=null){
                        int retain=retention(p,pool);
                        transaction.add(List.of("ZADD",index(pool,gid),Long.toString(units),Long.toString(units)));
                        transaction.add(List.of("RPUSH",listKey,member));
                        transaction.add(List.of("LTRIM",listKey,"-"+retain,"-1"));
                    }
                }

                boolean batchEnd=(i+1)%batchSize==0||i+1==count;
                if(batchEnd){
                    completedBatches++;
                    if(redis!=null&&transaction.size()>1){
                        transaction.add(List.of("EXEC"));
                        try{
                            verifyTransaction(transaction,redis.batch(transaction));
                            redisCommittedCount=validCount;
                        }catch(IOException redisError){
                            if(i>0&&redisProgressStop(redisError)){
                                System.out.println("[warn] Redis stopped after "+(i+1)+" attempts: "+redisError.getMessage());
                                break;
                            }
                            throw redisError;
                        }
                    }else if(redis!=null){
                        redisCommittedCount=validCount;
                    }
                    transaction=new ArrayList<>();transaction.add(List.of("MULTI"));
                }
                if((i+1)%1000==0)System.out.println("Completed "+(i+1)+" attempts; valid="+validCount+"; symbol profile="+phase);
            }
        }

        Map<String,Integer> distinctPayouts=new TreeMap<>();
        for(var entry:payoutValues.entrySet())distinctPayouts.put(entry.getKey(),entry.getValue().size());
        Map<String,Object> report=Json.map(
                "gameId",gid,
                "generator","SecureRandom / 按单牌权重随机完整事实 / Java 核心结算后分类",
                "requestedAttempts",count,
                "attempted",attemptedCount,
                "valid",validCount,
                "rejected",attemptedCount-validCount,
                "redisCommittedValid",redisCommittedCount,
                "validationOnly",validate,
                "batchSize",batchSize,
                "completedBatches",completedBatches,
                "batchCommitPolicy","每批尝试结束即提交本批全部有效结果，不补足批次数量",
                "failurePolicy","候选不合法或超出配置范围即丢弃，本次尝试不重抽",
                "sampleBoardCandidates",0,
                "sampleMultiplierTriples",0,
                "scenarioAttempts",scenarioAttempts,
                "acceptedModes",acceptedModes,
                "rejectionReasons",model.rejectionCounts(),
                "weightPhaseAttempts",phaseAttempts,
                "weightPhaseAccepted",phaseAccepted,
                "bucketCount",buckets.size(),
                "distinctPayoutsByMode",distinctPayouts,
                "payoutMinimums",payoutMinimums,
                "payoutMaximums",payoutMaximums,
                "wheelPrizeDistribution",wheelPrizes,
                "featureWinningStepDistribution",featureWinSteps,
                "normalIndex",index(Pool.NORMAL,gid),
                "featureIndex",index(Pool.FEATURE,gid),
                "wheelIndex",index(Pool.WHEEL,gid),
                "configSha256",sha256(file),
                "runtimeArtifact",runtimeArtifact(),
                "runtimeArtifactSha256",runtimeArtifactSha256(),
                "reachableRange",model.metadata());
        if(uniqueFacts!=null){
            report.put("uniqueCompleteFacts",uniqueFacts.size());
            report.put("duplicateCompleteFacts",validCount-uniqueFacts.size());
        }
        Path reportPath=Path.of(options.getOrDefault("--report",validate?"generation-run.json":"loader-run.json"));
        Files.writeString(reportPath,Json.stringify(report)+"\n");
        System.out.println(Json.stringify(report));
    }

    private static String runtimeArtifact(){
        try{return Path.of(new URI(GeneratorMain.class.getProtectionDomain().getCodeSource().getLocation().toString())).toAbsolutePath().toString();}
        catch(Exception ignored){return "UNKNOWN";}
    }

    private static String runtimeArtifactSha256(){
        try{Path path=Path.of(new URI(GeneratorMain.class.getProtectionDomain().getCodeSource().getLocation().toString()));return Files.isRegularFile(path)?sha256(path):"DIRECTORY";}
        catch(Exception ignored){return "UNKNOWN";}
    }

    private static String sha256(Path path)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[8192];for(int read;(read=in.read(buffer))>=0;)digest.update(buffer,0,read);}
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b));return result.toString();
    }
}
