package com.cpgame.luckywheel.loader;

import java.nio.file.Path;

/** 正式 Redis Loader 入口；只接受 generator.properties 路径，不接受随机种子。 */
public final class LoaderMain {
    private LoaderMain() { }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("[失败] 用法：java -jar lucky-wheel-redis-loader.jar [generator.properties]");
            System.exit(2);
        }
        Path config = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
        try {
            RedisLoader.RunResult result = new RedisLoader().run(config);
            long gid = result.redisGameId();
            System.out.printf("[成功] Redis 生成完成：sourceGameId=%d redisGameId=%d normal=%d special=%d written=%d batches=%d%n",
                    result.sourceGameId(), gid, result.normalMembers(), result.specialMembers(),
                    result.writtenMembers(), result.batches());
            System.out.printf("[校验] maxConsecutiveWins=%d normalBuckets=%d specialBuckets=%d normalLossMembers=%d rulesHash=%s%n",
                    result.maxConsecutiveWinsObserved(), result.normalMultiplierBuckets(),
                    result.specialMultiplierBuckets(), result.normalLossMembers(), result.rulesHash());
            System.out.printf("[key] locked ordinary=%s unlocked ordinary=%s locked mary=%s unlocked mary=%s%n",
                    RedisKeyContract.normalIndex(gid, 1), RedisKeyContract.normalIndex(gid, 5),
                    RedisKeyContract.specialIndex(gid, 1), RedisKeyContract.specialIndex(gid, 5));
        } catch (Exception error) {
            System.err.println("[失败] Redis Loader 执行失败：" + error.getMessage());
            System.exit(2);
        }
    }
}
