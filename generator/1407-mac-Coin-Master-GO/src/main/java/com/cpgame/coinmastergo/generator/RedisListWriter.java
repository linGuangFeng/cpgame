package com.cpgame.coinmastergo.generator;

import java.util.List;

/** 平台 Redis 下游合同：一个批次在同一 MULTI/EXEC 中提交。 */
public interface RedisListWriter extends AutoCloseable {
    void appendBatchAtomically(List<RedisEntry> entries, int maxMembersPerMultiplier);

    record RedisEntry(String indexKey, String listKey, String multiplier, String member) {
        public RedisEntry {
            if (indexKey == null || indexKey.isBlank() || listKey == null || listKey.isBlank()
                    || multiplier == null || multiplier.isBlank() || member == null || member.isBlank()) {
                throw new IllegalArgumentException("Redis 条目字段不能为空");
            }
        }
    }

    @Override void close();
}
