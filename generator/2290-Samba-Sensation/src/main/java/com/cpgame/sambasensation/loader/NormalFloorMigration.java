package com.cpgame.sambasensation.loader;

import com.cpgame.sambasensation.core.MinimalRoundFactCodec;
import com.cpgame.sambasensation.generator.GeneratorConfig;
import com.cpgame.sambasensation.redis.RedisConnection;
import com.cpgame.sambasensation.redis.RedisKeyContract;
import java.nio.file.Path;
import java.util.List;

/** 将旧混层普通池逐member原子搬到楼层前缀，不重新生成奖励、不清空任何池。可重复执行。 */
public final class NormalFloorMigration {
    public static void main(String[] args) throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of(args[0]));
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        int moved = 0;
        String script = "if redis.call('LREM',KEYS[1],1,ARGV[1])==1 then "
                + "redis.call('RPUSH',KEYS[2],ARGV[1]);redis.call('ZADD',KEYS[3],ARGV[2],ARGV[2]);return 1 end;return 0";
        try (RedisConnection redis = RedisConnection.connect(config)) {
            for (Object bucket : (List<?>) redis.command("ZRANGE", RedisKeyContract.normalIndex(config.redisGameId), "0", "-1")) {
                int multiplier = Integer.parseInt(bucket.toString());
                String oldKey = RedisKeyContract.normalList(config.redisGameId, multiplier);
                for (Object raw : (List<?>) redis.command("LRANGE", oldKey, "0", "-1")) {
                    String member = raw.toString();
                    if (!codec.supports(member)) continue;
                    int floor = codec.decode(member).betType();
                    if (codec.verify(member).multiplier() != multiplier) throw new IllegalStateException("invalid cached multiplier");
                    if (floor == 1) continue;
                    Object result = redis.command("EVAL", script, "3", oldKey,
                            RedisKeyContract.normalList(config.redisGameId, multiplier, floor),
                            RedisKeyContract.normalIndex(config.redisGameId, floor), member, bucket.toString());
                    moved += Integer.parseInt(result.toString());
                }
            }
        }
        System.out.println("NORMAL_FLOOR_MIGRATION moved=" + moved);
    }
}
