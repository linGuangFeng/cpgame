package com.cpgame.curupira.api;

import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 仅测试使用：内存池。正式 Demo 必须走 Redis。 */
public final class InMemoryRoundSource implements RoundSource {
    private final Map<Kind, List<CompleteRoundFact>> pools = new EnumMap<>(Kind.class);

    public InMemoryRoundSource(GameRuleCore core) {
        for (Kind kind : Kind.values()) {
            if (kind == Kind.TRIGGER || kind == Kind.BUY_FE || kind == Kind.BUY_HS) continue;
            List<CompleteRoundFact> list = new ArrayList<>();
            if (kind == Kind.WIN) {
                int[][] bands = {{1, 9}, {10, 19}, {20, 29}, {30, 49}, {50, 10_000}};
                for (int[] band : bands) {
                    for (int i = 0; i < 4; i++) list.add(core.generateWinRange(band[0], band[1]));
                }
            } else {
                for (int i = 0; i < 8; i++) list.add(core.generateFact(kind));
            }
            pools.put(kind, list);
        }
    }

    @Override public synchronized CompleteRoundFact peekLoss() {
        return pools.get(Kind.LOSS).get(0);
    }

    private final Map<Kind, Integer> cursor = new EnumMap<>(Kind.class);

    @Override public synchronized CompleteRoundFact claim(Kind kind) {
        return claim(kind, 0, Integer.MAX_VALUE);
    }

    @Override public synchronized CompleteRoundFact claim(Kind kind, int minMultiplier, int maxMultiplier) {
        if (kind == Kind.TRIGGER) {
            throw new IllegalStateException("trigger boards are generated live and are not stored in cache");
        }
        if (kind == Kind.BUY_FE) kind = Kind.FREE_EW;
        if (kind == Kind.BUY_HS) kind = Kind.HOLD;
        List<CompleteRoundFact> list = pools.get(kind);
        if (list == null || list.isEmpty()) throw new IllegalStateException("in-memory pool empty: " + kind);
        List<CompleteRoundFact> matched = new ArrayList<>();
        for (CompleteRoundFact fact : list) {
            int o = fact.redisMultiplier();
            if (o >= minMultiplier && o <= maxMultiplier) matched.add(fact);
        }
        if (matched.isEmpty()) throw new IllegalStateException("in-memory pool empty: " + kind + " o=" + minMultiplier + ".." + maxMultiplier);
        int index = Math.floorMod(cursor.merge(kind, 1, Integer::sum) - 1, matched.size());
        return matched.get(index);
    }

    @Override public synchronized CompleteRoundFact claimBuy(int gameType) {
        return claim(gameType == 3 ? Kind.HOLD : Kind.FREE_EW);
    }
}
