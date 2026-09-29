package com.hd.cpgame.riocarnival.server.service;
import com.hd.cpgame.riocarnival.core.*;
import com.hd.cpgame.riocarnival.loader.RedisConnection;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
/** Select outcome first, then an existing integer 0.01x bucket, then atomically claim one complete ASCII member. */
@Service
public final class RedisRoundPool {
    private final SecureRandom random=new SecureRandom();
    private final RoundFactsCodec codec=new RoundFactsCodec();
    private final CompleteRoundFactory lossFactory=new CompleteRoundFactory(new RandomBoardCandidateGenerator(new SecureRoundRandom()), new SecureRoundRandom());
    private final String username,password,host;
    private final int port,database;
    @Value("${redis.game-id:8000045}") private long gameId;
    public RedisRoundPool(@Value("${redis.host:54.172.218.28}") String host,@Value("${redis.port:8016}") int port,
                          @Value("${redis.database:0}") int database,@Value("${redis.username:}") String username,
                          @Value("${redis.password:}") String password){
        boolean nas = "192.168.10.3".equals(host) && port == 6379;
        boolean aws = "18.234.101.161".equals(host) && port == 8021;
        boolean fd = "54.172.218.28".equals(host) && port == 8016;
        boolean local = "127.0.0.1".equals(host) && port == 6379;
        if((!nas && !aws && !fd && !local) || database < 0) throw new IllegalArgumentException("unauthorized Redis endpoint: " + host + ":" + port);
        this.host=host;this.port=port;this.database=database;this.username=username;this.password=password;
    }
    public GeneratedRound claim(BigDecimal bs,int bl) {
        if(bs==null||bs.signum()<=0||bl<1)throw new IllegalArgumentException("stake must be positive");
        boolean win=random.nextInt(1406)<253; // Outcome is chosen before any mode or multiplier.
        if(!win) return lossFactory.createIndependentLoss(bs,bl);
        try(RedisConnection redis=RedisConnection.connect(host,port,database,username,password)) {
            boolean special;
            long gameId=this.gameId;
            String list;
            if(win) {
                special=random.nextInt(253)<48;
            } else {
                long normal=llen(redis, false, gameId, 0);
                long free=llen(redis, true, gameId, 0);
                long total=Math.addExact(normal,free);
                if(total==0)throw new IOException("POOL_EMPTY: losing outcome");
                if(total>Integer.MAX_VALUE)throw new IOException("POOL_TOO_LARGE: losing outcome");
                special=random.nextInt((int)total)>=normal;
            }
            int ratio=0;
            if(win) {
                Object raw=redis.command("ZRANGE", special ? maryIndex(gameId) : normalIndex(gameId), "0", "-1");
                List<?> all=(List<?>)raw;
                List<Integer> ratios=new java.util.ArrayList<>();
                for(Object item:all){
                    int value=Integer.parseInt(item.toString());
                    if(value>0) ratios.add(value);
                }
                if(ratios.isEmpty())throw new IOException("POOL_EMPTY: "+(special?"MaryKeyList":"PerKeyList"));
                ratio=ratios.get(random.nextInt(ratios.size()));
            }
            list=special?maryList(gameId, ratio):normalList(gameId, ratio);
            long len=llen(redis, special, gameId, ratio);
            if(len<=0)throw new IOException("POOL_EMPTY: "+list);
            Object member=redis.command("LINDEX", list, Integer.toString(random.nextInt((int)Math.min(len, Integer.MAX_VALUE))));
            if(member==null)throw new IOException("POOL_EMPTY: "+list);
            GeneratedRound round=codec.decode(member.toString(),bs,bl);
            RoundResult result=RoundVerifier.verify(round);
            if(result.redisRatio(round)!=ratio)throw new IOException("POOL_INVALID_MULTIPLIER");
            if(win!=(result.totalAward.signum()>0))throw new IOException("POOL_INVALID_OUTCOME");
            if(special!="FREE_SPINS".equals(result.mode))throw new IOException("POOL_INVALID_MODE");
            return round;
        }catch(IOException e){throw new PoolException(e.getMessage(),e);}
    }
    private static String normalIndex(long gameId){return String.format("PerKeyList_%09d",gameId);}
    private static String maryIndex(long gameId){return String.format("MaryKeyList_%09d",gameId);}
    private static String normalList(long gameId,int ratio){return String.format("BetLog:0%08d:%06d",gameId,ratio);}
    private static String maryList(long gameId,int ratio){return String.format("MaryLog:%09d:%06d",gameId,ratio);}
    private static long llen(RedisConnection redis,boolean special,long gameId,int ratio)throws IOException{
        Object len=redis.command("LLEN", special?maryList(gameId,ratio):normalList(gameId,ratio));
        return len instanceof Number?((Number)len).longValue():Long.parseLong(String.valueOf(len));
    }
    public static final class PoolException extends RuntimeException {
        public PoolException(String message,Throwable cause){super(message,cause);}
    }
}
