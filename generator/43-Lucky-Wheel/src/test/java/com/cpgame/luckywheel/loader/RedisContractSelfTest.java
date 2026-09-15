package com.cpgame.luckywheel.loader;

import com.cpgame.luckywheel.core.MinimalFactCodec;
import com.cpgame.luckywheel.core.ResultAnalysis;
import com.cpgame.luckywheel.core.ResultUtil;
import com.cpgame.luckywheel.core.RoundFacts;

import java.nio.charset.StandardCharsets;

/** 对真实 Redis LPOP 取得的 member 执行独立解码与 ResultUtil 反推。 */
public final class RedisContractSelfTest {
    private RedisContractSelfTest() { }

    public static void main(String[] args) {
        if (args.length == 2 && "KEY".equals(args[0])) {
            long gameId = Long.parseLong(args[1]);
            requireEquals("PerKeyList_000000043", RedisKeyContract.normalIndex(gameId, 1));
            requireEquals("PerKeyList_100000043", RedisKeyContract.normalIndex(gameId, 5));
            requireEquals("MaryKeyList_000000043", RedisKeyContract.specialIndex(gameId, 1));
            requireEquals("MaryKeyList_100000043", RedisKeyContract.specialIndex(gameId, 5));
            requireEquals("BetLog:000000043:000050", RedisKeyContract.normalList(gameId, 1, 50));
            requireEquals("BetLog:100000043:000050", RedisKeyContract.normalList(gameId, 5, 50));
            requireEquals("MaryLog:000000043:000050", RedisKeyContract.specialList(gameId, 1, 50));
            requireEquals("MaryLog:100000043:000050", RedisKeyContract.specialList(gameId, 5, 50));
            requireEquals(0, RedisKeyContract.unlockDigit(1));
            requireEquals(1, RedisKeyContract.unlockDigit(5));
            System.out.println("KEY_CONTRACT_PASS");
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("用法：RedisContractSelfTest KEY_OR_CODEC MEMBER");
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundFacts facts = codec.decodeRedisMember(args[1].getBytes(StandardCharsets.US_ASCII));
        ResultAnalysis analysis = ResultUtil.analyze(facts);
        System.out.printf("DECODE_PASS mode=%d outcome=%s multiplier=%s member=%s%n",
                facts.mode(), analysis.outcome(), analysis.totalAward().stripTrailingZeros().toPlainString(), args[1]);
    }

    private static void requireEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("key 合同错误: expected=" + expected + " actual=" + actual);
        }
    }
}
