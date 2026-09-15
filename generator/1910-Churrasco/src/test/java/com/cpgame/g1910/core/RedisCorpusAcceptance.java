package com.cpgame.g1910.core;

/** Read-only/rotate-in-place acceptance for the complete-round Redis corpus. */
public final class RedisCorpusAcceptance {
    public static void main(String[] args) {
        try (var redis=new RedisClient("192.168.10.3",6379,"","",15,false,5000,5000)) {
            redis.ping();
            long normal=verifyIndex(redis,"PerKeyList_000001910",false);
            long special=verifyIndex(redis,"MaryKeyList_000001910",true);
            if(normal<400||special<60)throw new IllegalStateException("insufficient Redis corpus");
            System.out.printf("REDIS_CORPUS_PASS normalMembers=%d specialMembers=%d database=15 rulesHash=%s%n",normal,special,GameRuleCore.RULES_HASH);
        }
    }

    private static long verifyIndex(RedisClient redis,String index,boolean special) {
        long total=0;
        var scores=redis.zrange(index,0,-1);
        if(scores.isEmpty())throw new IllegalStateException("empty index "+index);
        for(String raw:scores){
            int score=Integer.parseInt(raw);
            String key=special?String.format("MaryLog:%09d:%06d",1910,score):String.format("BetLog:0%08d:%06d",1910,score);
            long length=redis.llen(key);
            if(length<1)throw new IllegalStateException("empty bucket "+key);
            for(long i=0;i<length;i++){
                String fact=redis.lpop(key);
                if(fact==null)throw new IllegalStateException("short bucket "+key);
                try {
                    var round=MinimalRoundFactCodec.decode(fact);
                    var result=GameRuleCore.evaluate(round);
                    if(result.totalUnits()!=score)throw new IllegalStateException("score mismatch "+key);
                    if(special!=(round.mode()==GameRuleCore.Mode.FREE_REWARD))throw new IllegalStateException("pool mismatch "+key);
                } finally { redis.rpush(key,fact); }
            }
            total+=length;
        }
        return total;
    }
}
