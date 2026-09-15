package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single Jungle Kings rules core. Dealing uses the origin 30-stop reel strips from the
 * bundled Cocos config; settlement is always re-run by ResultUtil.
 */
public final class GameRuleCore {
    public static final int RAW_GAME_ID = 2;
    public static final String GAME_NAME = "Jungle Kings";
    public static final String SLUG = "jungle-kings";
    public static final String RULES_VERSION = "jk2-reelstrip-dualboard-20260909";
    public static final String RULES_HASH = "3c1e8d7a9b4f2c6e0a5d8b1f7e4c9a2d6b0e3f8c1a7d4b9e2f5c8a0d3b6e1f4";
    public static final String CB_TOP = "CB0002";
    public static final String CB_BOTTOM = "CB0003";
    public static final String PL_TOP = "PL0011";
    public static final String PL_BOTTOM = "PL0012";
    public static final String PL_V1_BOTTOM = "PL0014";
    public static final int REELS = 3;
    public static final int ROWS = 3;
    public static final int CELLS = 9;
    public static final int STRIP_LEN = 30;
    public static final int X2_WHEN_BOTH_WIN = 2;

    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("50"));
    public static final List<String> CHESSBOARDS = List.of(CB_TOP, CB_BOTTOM);
    /** 上 / 下 / 上下 — each is its own complete-round cache class. */
    public static final List<List<String>> LINE_LAYOUTS = List.of(
            List.of(CB_TOP),
            List.of(CB_BOTTOM),
            List.of(CB_TOP, CB_BOTTOM));
    public static final Set<String> BOARD_SYMBOLS = Set.of("S00011", "S00012", "S00013", "S00015");
    public static final Set<String> CONFIRMED_WIN_SYMBOLS = Set.of("S00011", "S00012");

    private static final Map<String, Integer> PAY_MULTIPLIER = paytable();
    private static final Map<String, String> PAYLINE_OF = Map.of(
            CB_TOP, PL_TOP,
            CB_BOTTOM, PL_BOTTOM);

    /**
     * Origin reel strips (RID0011-RID0016). S00014 is absent from every strip and is never drawn.
     * Evidence: publish/2-Jungle-Kings/2/assets/main/index.5891e.js OldConfigData.reel_items.
     */
    public static final String[][][] STRIPS = {
            {
                    strip("S00011,S00015,S00012,S00013,S00012,S00015,S00015,S00013,S00015,S00015,S00015,S00012,S00015,S00015,S00011,S00011,S00012,S00015,S00015,S00012,S00012,S00013,S00015,S00015,S00013,S00012,S00012,S00015,S00012,S00015"),
                    strip("S00011,S00015,S00012,S00013,S00011,S00015,S00015,S00012,S00011,S00015,S00015,S00015,S00015,S00012,S00015,S00011,S00015,S00015,S00015,S00012,S00012,S00015,S00012,S00013,S00015,S00013,S00015,S00015,S00011,S00013"),
                    strip("S00011,S00015,S00012,S00013,S00012,S00012,S00013,S00015,S00013,S00013,S00015,S00012,S00015,S00012,S00015,S00015,S00015,S00011,S00012,S00015,S00015,S00015,S00012,S00013,S00013,S00012,S00013,S00013,S00013,S00012")
            },
            {
                    strip("S00011,S00015,S00012,S00013,S00012,S00011,S00015,S00015,S00015,S00012,S00013,S00015,S00012,S00015,S00015,S00011,S00012,S00015,S00013,S00012,S00015,S00015,S00013,S00012,S00013,S00015,S00013,S00012,S00013,S00015"),
                    strip("S00011,S00015,S00012,S00013,S00015,S00013,S00012,S00013,S00015,S00015,S00012,S00015,S00011,S00015,S00011,S00012,S00015,S00015,S00011,S00015,S00015,S00012,S00015,S00013,S00015,S00012,S00015,S00011,S00015,S00013"),
                    strip("S00011,S00015,S00012,S00013,S00015,S00012,S00011,S00015,S00012,S00011,S00015,S00012,S00015,S00013,S00015,S00011,S00012,S00013,S00015,S00015,S00015,S00013,S00015,S00012,S00013,S00015,S00011,S00015,S00012,S00013")
            }
    };

    private GameRuleCore() { }

    public static Map<String, Integer> payMultipliers() {
        return PAY_MULTIPLIER;
    }

    public static int payMultiplier(String symbol) {
        Integer value = PAY_MULTIPLIER.get(symbol);
        if (value == null) throw new IllegalArgumentException("unknown symbol: " + symbol);
        return value;
    }

    public static boolean paying(String symbol) {
        return payMultiplier(symbol) > 0;
    }

    public static boolean confirmedWinSymbol(String symbol) {
        return CONFIRMED_WIN_SYMBOLS.contains(symbol);
    }

    public static void validateBet(BigDecimal betSize, int betLevel) {
        if (betSize == null || BET_SIZES.stream().noneMatch(value -> value.compareTo(betSize) == 0)) {
            throw new IllegalArgumentException("bet_size must be one of 0.5, 5, 50");
        }
        if (!BET_LEVELS.contains(betLevel)) throw new IllegalArgumentException("bet_level must be 1..10");
    }

    public static List<String> parseChessboards(String ckl) {
        if (ckl == null || ckl.isBlank()) return List.of(CB_TOP, CB_BOTTOM);
        List<String> keys = new ArrayList<>();
        for (String token : ckl.split(",")) {
            String key = token.strip();
            if (key.isEmpty()) continue;
            if (!CB_TOP.equals(key) && !CB_BOTTOM.equals(key)) {
                throw new IllegalArgumentException("unknown chessboard: " + key);
            }
            if (!keys.contains(key)) keys.add(key);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("ckl must activate at least one reel");
        keys.sort(java.util.Comparator.comparingInt(GameRuleCore::chessboardIndex));
        return List.copyOf(keys);
    }

    public static String layoutToken(List<String> chessboards) {
        return String.join("+", parseChessboards(String.join(",", chessboards)));
    }

    public static String paylineKey(String chessboard) {
        String key = PAYLINE_OF.get(chessboard);
        if (key == null) throw new IllegalArgumentException("unknown chessboard: " + chessboard);
        return key;
    }

    public static int chessboardIndex(String chessboard) {
        if (CB_TOP.equals(chessboard)) return 0;
        if (CB_BOTTOM.equals(chessboard)) return 1;
        throw new IllegalArgumentException("unknown chessboard: " + chessboard);
    }

    public static List<String> expandBoard(List<String> logicalReels) {
        if (logicalReels == null || logicalReels.size() != REELS) {
            throw new IllegalArgumentException("logical reels must contain 3 symbols");
        }
        List<String> board = new ArrayList<>(CELLS);
        for (String symbol : logicalReels) {
            requireBoardSymbol(symbol);
            for (int row = 0; row < ROWS; row++) board.add(symbol);
        }
        return List.copyOf(board);
    }

    public static List<String> logicalReels(List<String> board) {
        requireNine(board);
        List<String> reels = new ArrayList<>(REELS);
        for (int reel = 0; reel < REELS; reel++) {
            String first = board.get(reel * ROWS);
            if (!first.equals(board.get(reel * ROWS + 1)) || !first.equals(board.get(reel * ROWS + 2))) {
                throw new IllegalArgumentException("each reel window must repeat one stop symbol");
            }
            requireBoardSymbol(first);
            reels.add(first);
        }
        return List.copyOf(reels);
    }

    public static List<String> drawBoard(int chessboardIndex, SecureRandom random) {
        List<String> reels = new ArrayList<>(REELS);
        for (int reel = 0; reel < REELS; reel++) {
            String[] strip = STRIPS[chessboardIndex][reel];
            reels.add(strip[random.nextInt(strip.length)]);
        }
        return expandBoard(reels);
    }

    public static CompleteRound materialize(List<String> chessboards, List<List<String>> boards,
                                            BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        if (chessboards == null || chessboards.isEmpty() || boards == null
                || chessboards.size() != boards.size()) {
            throw new IllegalArgumentException("chessboards and boards must align");
        }
        ResultUtil.Evaluation evaluation = ResultUtil.evaluate(chessboards, boards, betSize, betLevel);
        return new CompleteRound(RAW_GAME_ID, evaluation.mode(), betSize, betLevel,
                evaluation.betAmount(), chessboards, boards,
                evaluation.winPaylineKeys(), evaluation.winSymbolKeys(),
                evaluation.winAmount(), evaluation.multiplier());
    }

    public static BigDecimal lineStake(BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        return money(betSize.multiply(BigDecimal.valueOf(betLevel)));
    }

    public static void requireBoardSymbol(String symbol) {
        if (symbol == null || !BOARD_SYMBOLS.contains(symbol)) {
            throw new IllegalArgumentException("unobserved or forbidden board symbol: " + symbol);
        }
    }

    public static void requireNine(List<String> board) {
        if (board == null || board.size() != CELLS) throw new IllegalArgumentException("board must contain 9 symbols");
    }

    public static void requireConfirmedWinBoundary(List<String> logicalReels) {
        if (logicalReels == null || logicalReels.size() != REELS) {
            throw new IllegalArgumentException("logical reels must contain 3 symbols");
        }
        for (String symbol : logicalReels) requireBoardSymbol(symbol);
        String first = logicalReels.get(0);
        boolean triple = first.equals(logicalReels.get(1)) && first.equals(logicalReels.get(2));
        if (triple && paying(first) && !confirmedWinSymbol(first)) {
            throw new IllegalArgumentException("unconfirmed win symbol must be rejected: " + first);
        }
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static Map<String, Integer> paytable() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("S00011", 100);
        map.put("S00012", 50);
        map.put("S00013", 25);
        map.put("S00014", 5);
        map.put("S00015", 0);
        return Map.copyOf(map);
    }

    private static String[] strip(String csv) {
        String[] values = csv.split(",");
        if (values.length != STRIP_LEN) throw new IllegalStateException("reel strip must contain 30 stops");
        for (String symbol : values) {
            if ("S00014".equals(symbol) || !BOARD_SYMBOLS.contains(symbol)) {
                throw new IllegalStateException("illegal strip symbol " + symbol);
            }
        }
        return values;
    }
}
