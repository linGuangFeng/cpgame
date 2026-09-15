package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaFrameAssigner;

import java.math.BigDecimal;
import java.util.List;

/** Non-recomputable facts of one paid complete Round. Awards are not stored. */
public record CompleteRoundFact(BigDecimal betSize, int betLevel, List<PageFact> paid,
                                List<List<PageFact>> freeSpins) {
    public CompleteRoundFact {
        if (betSize == null || betSize.signum() <= 0 || betLevel < 1) {
            throw new IllegalArgumentException("bet is required");
        }
        if (paid == null || paid.isEmpty()) throw new IllegalArgumentException("paid segment is required");
        paid = List.copyOf(paid);
        if (freeSpins == null) freeSpins = List.of();
        else freeSpins = freeSpins.stream().map(List::copyOf).toList();
        if (!freeSpins.isEmpty() && freeSpins.size() < 10) {
            throw new IllegalArgumentException("free spins must be omitted or at least 10");
        }
    }

    public record PageFact(LuckyPandaBoard board, int rpx, List<Integer> gfl, List<Integer> sfl) {
        public PageFact {
            if (board == null) throw new IllegalArgumentException("page board is required");
            if (rpx < 0) throw new IllegalArgumentException("rpx must be >= 0");
            gfl = gfl == null ? List.of() : List.copyOf(gfl);
            sfl = sfl == null ? List.of() : List.copyOf(sfl);
            LuckyPandaFrameAssigner.validate(board, gfl, sfl);
        }

        public PageFact(LuckyPandaBoard board, int rpx) {
            this(board, rpx, List.of(), List.of());
        }
    }
}
