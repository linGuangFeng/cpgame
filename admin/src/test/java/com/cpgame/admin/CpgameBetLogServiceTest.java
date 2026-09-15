package com.cpgame.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpgameBetLogServiceTest {
    @Test void fatalCandidateMessageIsVisibleAndWinsOverCompletionMarker() {
        var result = CpgameBetLogService.classifyFinish(null,
                "LOAD_COMPLETE loaded=2\n[失败] Scatter exceeds PAID page cap\n[EXIT] FAILED\n");
        assertEquals("FAILED", result.state());
        assertEquals("runRedis 失败：Scatter exceeds PAID page cap", result.message());
    }

    @Test void discardedCandidateDoesNotMakeSuccessfulRunFail() {
        var result = CpgameBetLogService.classifyFinish(null,
                "discardedCandidateLimit=12\nLOAD_COMPLETE loaded=2\n");
        assertEquals("COMPLETED", result.state());
    }

    @Test
    void crashLogIsFailedEvenWhenExitCodeIsZero() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(0,
            "Exception in thread \"main\" java.lang.IllegalArgumentException: 未知或不允许的配置项：generation.special-max-members-per-multiplier\n");
        assertEquals("FAILED", result.state());
        assertEquals("runRedis 失败：Exception in thread \"main\" java.lang.IllegalArgumentException: 未知或不允许的配置项：generation.special-max-members-per-multiplier", result.message());
    }

    @Test
    void successfulGenerationIsCompleted() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(0,
            "批次已提交 batch=1 members=100\n生成完成 redisGameId=8001407 普通候选=100 特殊候选=100\n");
        assertEquals("COMPLETED", result.state());
        assertEquals("runRedis 已完成", result.message());
    }

    @Test
    void nonzeroExitWithoutCompleteLineIsFailed() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(1, "starting loader\n");
        assertEquals("FAILED", result.state());
        assertEquals("runRedis 失败，退出码 1", result.message());
    }

    @Test
    void deadProcessWithoutExitOrCompleteIsFailed() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(null, "");
        assertEquals("FAILED", result.state());
        assertEquals("生成进程已退出", result.message());
    }

    @Test
    void completeLineWithoutExitCodeIsCompleted() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(null, "生成完成 redisGameId=8001407\n");
        assertEquals("COMPLETED", result.state());
    }

    @Test
    void crashWithoutExitCodeIsFailed() {
        CpgameBetLogService.FinishClassification result = CpgameBetLogService.classifyFinish(null,
            "Exception in thread \"main\" java.lang.IllegalArgumentException: 未知或不允许的配置项：generation.special-max-members-per-multiplier\n");
        assertEquals("FAILED", result.state());
    }
}
