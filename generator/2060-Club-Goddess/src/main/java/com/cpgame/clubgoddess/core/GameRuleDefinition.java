package com.cpgame.clubgoddess.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, evidenced rule facts transcribed from game-capabilities.json.
 * Generation and verification may share these facts, but not their evaluation algorithms.
 */
final class GameRuleDefinition {
    static final String RULES_VERSION = "2060-protocol-r3";
    static final String RULES_HASH = "sha256:cd9e5bac75dcaee42e78e1298a4e896b3a2b01db98c28027a50d7d3df771016b";
    static final int COLUMNS = 5;
    static final int ROWS = 3;
    static final int CELL_COUNT = COLUMNS * ROWS;
    static final int SCATTER = 9;
    static final int WILD = 10;
    static final int BASE_BET_FACTOR = 30;
    static final int MINIMUM_WIN_REELS = 3;
    static final int FREE_INITIAL_MULTIPLIER = 2;
    static final int FREE_WILDS_PER_INCREASE = 3;
    static final int FREE_MULTIPLIER_INCREASE = 2;
    static final int FREE_MAX_MULTIPLIER = 20;
    static final Set<Integer> REGULAR_SYMBOLS = Set.of(1, 2, 3, 4, 5, 6, 7, 8);
    static final Set<Integer> WILD_REELS_ZERO_BASED = Set.of(1, 2, 3);
    static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.01"), new BigDecimal("0.04"), new BigDecimal("0.2"));
    static final BigDecimal MINIMUM_EVIDENCED_TOTAL_STAKE = new BigDecimal("3");
    static final Map<Integer, List<Integer>> PAYTABLE = Map.of(
            1, List.of(30, 60, 150),
            2, List.of(20, 45, 90),
            3, List.of(15, 30, 60),
            4, List.of(10, 15, 45),
            5, List.of(8, 10, 30),
            6, List.of(8, 10, 30),
            7, List.of(5, 8, 15),
            8, List.of(5, 8, 15));

    private GameRuleDefinition() {}

    static int odd(int symbol, int matchedReels) {
        List<Integer> odds = PAYTABLE.get(symbol);
        if (odds == null || matchedReels < MINIMUM_WIN_REELS || matchedReels > COLUMNS) {
            throw new IllegalArgumentException("No evidenced paytable entry");
        }
        return odds.get(matchedReels - MINIMUM_WIN_REELS);
    }

    static void validateBet(BigDecimal bet, int level) {
        if (BET_SIZES.stream().noneMatch(candidate -> candidate.compareTo(bet) == 0)) {
            throw new IllegalArgumentException("Unsupported bet size");
        }
        if (level < 1 || level > 10) {
            throw new IllegalArgumentException("Unsupported level");
        }
        if (stake(bet, level).compareTo(MINIMUM_EVIDENCED_TOTAL_STAKE) < 0) {
            throw new IllegalArgumentException("Only evidenced total stake >= 3 is enabled");
        }
    }

    static BigDecimal stake(BigDecimal bet, int level) {
        return ResultMath.money(bet.multiply(BigDecimal.valueOf((long) level * BASE_BET_FACTOR)));
    }

    static int awardedFreeSpins(int scatterCount) {
        return switch (scatterCount) {
            case 3 -> 12;
            case 4 -> 15;
            case 5 -> 20;
            default -> throw new IllegalArgumentException("Free Spins require 3, 4 or 5 Scatters");
        };
    }

    static int freeMultiplier(int cumulativeWilds) {
        return Math.min(FREE_MAX_MULTIPLIER, FREE_INITIAL_MULTIPLIER
                + (cumulativeWilds / FREE_WILDS_PER_INCREASE) * FREE_MULTIPLIER_INCREASE);
    }
}
