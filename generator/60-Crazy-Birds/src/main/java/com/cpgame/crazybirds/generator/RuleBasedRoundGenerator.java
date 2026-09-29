package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 先按权重和牌阵约束生成事实，再交给唯一 Java 核心计奖与分类。 */
public final class RuleBasedRoundGenerator {
    private final GameRuleCore core = new GameRuleCore();

    public RoundResult generate(int bl, BigDecimal bs, BigDecimal start,
                                GenerationWeights.BatchWeights weights, RandomGenerator random) {
        List<List<String>> boards = new ArrayList<>();
        List<String> paid = board(false, weights, random);
        boards.add(paid);
        int scatterReels = ResultUtil.scatterReels(paid);
        if (scatterReels >= GameRules.SCATTER_TRIGGER_REELS) {
            int freeSpins = GameRules.freeSpinsForScatterReels(scatterReels);
            for (int i = 0; i < freeSpins; i++) {
                List<String> free = board(true, weights, random);
                if (ResultUtil.isScatterTrigger(free)) {
                    throw new CandidateRejectedException("FREE_RETRIGGER_DISABLED");
                }
                boards.add(free);
            }
        }
        return core.build(Long.toUnsignedString(random.nextLong(), 16), bl, bs, start, boards);
    }

    List<String> board(boolean freeStep, GenerationWeights.BatchWeights weights, RandomGenerator random) {
        Set<String> stageSymbols = freeStep ? GameRules.FREE_SYMBOLS : GameRules.PAID_SYMBOLS;
        List<String> board = new ArrayList<>(GameRules.BOARD_SIZE);
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            boolean scatterUsed = false;
            boolean wildUsed = false;
            for (int row = 0; row < GameRules.ROWS; row++) {
                final boolean noMoreScatter = scatterUsed;
                final boolean noMoreWild = wildUsed;
                final int reelIndex = reel;
                String symbol = weights.draw(freeStep, candidate -> stageSymbols.contains(candidate)
                        && !(noMoreScatter && "SC".equals(candidate))
                        && !(noMoreWild && GameRules.isWild(candidate))
                        && !(reelIndex == 0 && GameRules.isWild(candidate)), random);
                board.add(symbol);
                if ("SC".equals(symbol)) scatterUsed = true;
                if (GameRules.isWild(symbol)) wildUsed = true;
            }
        }
        List<String> result = List.copyOf(board);
        ResultUtil.validateBoardForStage(result, freeStep);
        return result;
    }

    public static final class CandidateRejectedException extends RuntimeException {
        private final String reason;
        CandidateRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }
        public String reason() { return reason; }
    }
}
