package com.hd.cpgame.magicscroll2.core;

/**
 * 单个随机候选在配置的有界尝试内无法形成合法结果。
 *
 * 只有这一异常允许正式 Loader 放弃当前候选并继续；规则、编解码和 Redis 异常不得转换为该类型。
 */
public final class CandidateGenerationExhaustedException extends RuntimeException {
    public CandidateGenerationExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
