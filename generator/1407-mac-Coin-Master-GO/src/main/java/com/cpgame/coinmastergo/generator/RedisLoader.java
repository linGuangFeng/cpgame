package com.cpgame.coinmastergo.generator;

/** 保持历史主类名兼容，正式实现由 RedisDirectLoader 承担。 */
public final class RedisLoader {
    private RedisLoader() { }
    public static void main(String[] args) throws Exception {
        RedisDirectLoader.main(args);
    }
}
