package com.cpgame.monsterslayer.core;

import java.util.ArrayList;
import java.util.List;

/** The only executable rule contract shared by the generator and Controller. */
public final class GameRuleCore {
    public static final int GAME_ID = 2300;
    public static final int COLS = 5;
    public static final int ROWS = 3;
    public static final int CELLS = COLS * ROWS;
    public static final int SCATTER = 100;
    public static final int FEATURE_PLACEHOLDER = 0;
    public static final String RULES_VERSION = "2300-monster-slayer-v3-buy-buckets";
    public static final String RULES_HASH = "df686c42f1c1891704b88742db554a38644ff719c46a6de3f436857647a3b781";

    private static final int[][] PAY = {
            {}, {50,100,750}, {40,80,500}, {35,50,250}, {30,40,200}, {30,40,150},
            {25,35,150}, {20,30,125}, {20,30,100}, {15,25,75}, {10,25,50}
    };

    private GameRuleCore() { }

    public enum RoundClass { ORDINARY_LOSS, ORDINARY_WIN, MONSTER_FEATURE, BUY_FEATURE }

    public record FeatureFacts(int[] hearts, int[] locCell, int[] locId, int[] bl, int[] iu, int[] t, int[] rbs, String roles) {
        public static final FeatureFacts EMPTY = new FeatureFacts(new int[0], new int[0], new int[0], new int[0], new int[0], new int[0], new int[0], "");
        public FeatureFacts {
            hearts = hearts.clone();
            locCell = locCell.clone();
            locId = locId.clone();
            bl = bl.clone();
            iu = iu.clone();
            t = t.clone();
            rbs = rbs.clone();
            if (roles == null) roles = "";
            if (roles.indexOf('|') >= 0 || roles.indexOf('/') >= 0) throw new IllegalArgumentException("roles payload contains splitter");
            if (locCell.length != locId.length) throw new IllegalArgumentException("loc length mismatch");
            if (bl.length != iu.length || bl.length != t.length) throw new IllegalArgumentException("animal length mismatch");
        }
        @Override public int[] hearts() { return hearts.clone(); }
        @Override public int[] locCell() { return locCell.clone(); }
        @Override public int[] locId() { return locId.clone(); }
        @Override public int[] bl() { return bl.clone(); }
        @Override public int[] iu() { return iu.clone(); }
        @Override public int[] t() { return t.clone(); }
        @Override public int[] rbs() { return rbs.clone(); }
        public String roles() { return roles; }
        public boolean empty() { return this == EMPTY || (hearts.length == 0 && locCell.length == 0 && bl.length == 0 && rbs.length == 0 && roles.isEmpty()); }
    }

    public record Step(int[] board, int gameType, int nextType, FeatureFacts feature) {
        public Step(int[] board, int gameType, int nextType) {
            this(board, gameType, nextType, FeatureFacts.EMPTY);
        }
        public Step {
            board = board.clone();
            if (feature == null) feature = FeatureFacts.EMPTY;
            if (board.length != CELLS) throw new IllegalArgumentException("res.ps must contain 15 cells");
            if (gameType < 0 || gameType > 4 || nextType < 0 || nextType > 4)
                throw new IllegalArgumentException("unsupported feature type");
            for (int symbol : board) if (!isWireSymbol(symbol)) throw new IllegalArgumentException("unsupported symbol " + symbol);
        }
        @Override public int[] board() { return board.clone(); }
    }

    public record CompleteRound(boolean special, int buyType, List<Step> steps) {
        public CompleteRound(boolean special, List<Step> steps) {
            this(special, 0, steps);
        }
        public CompleteRound {
            steps = List.copyOf(steps);
            validate(special, buyType, steps);
        }
    }

    public static int payoutCenti(int symbol, int reels) {
        if (symbol < 1 || symbol > 10 || reels < 3 || reels > 5) return 0;
        return PAY[symbol][reels - 3];
    }

    public static boolean connects(int previousCell, int nextCell) {
        int previousCol = previousCell / ROWS, nextCol = nextCell / ROWS;
        return nextCol == previousCol + 1 && Math.abs(previousCell % ROWS - nextCell % ROWS) <= 1;
    }

    public static boolean matches(int wireSymbol, int payingSymbol, boolean featureStep) {
        return wireSymbol == payingSymbol || (featureStep && wireSymbol == FEATURE_PLACEHOLDER);
    }

    public static int buyMultiple(int buyType) {
        return switch (buyType) {
            case 3 -> 60;
            case 4 -> 500;
            case 5 -> 200;
            default -> 1;
        };
    }

    public static void validate(CompleteRound round) {
        validate(round.special(), round.buyType(), round.steps());
    }

    private static void validate(boolean special, int buyType, List<Step> steps) {
        if (steps.isEmpty()) throw new IllegalArgumentException("complete Round requires at least one Step");
        if (buyType != 0 && buyType != 3 && buyType != 4 && buyType != 5)
            throw new IllegalArgumentException("unsupported buy type");
        if (buyType != 0 && !special) throw new IllegalArgumentException("buy Round must be special");
        if (!special) {
            if (buyType != 0 || steps.size() != 1 || steps.get(0).gameType() != 0 || steps.get(0).nextType() != 0)
                throw new IllegalArgumentException("ordinary Round must be one terminal Step");
            int scatters = 0;
            int[] board = steps.get(0).board();
            for (int cell = 0; cell < CELLS; cell++) {
                if (board[cell] == SCATTER) {
                    if (cell < 3 || cell >= 12 || ++scatters > 1)
                        throw new IllegalArgumentException("ordinary Scatter position/count invalid");
                } else if (board[cell] < 1 || board[cell] > 10) {
                    throw new IllegalArgumentException("ordinary Round cannot contain feature placeholders");
                }
            }
            return;
        }
        if (steps.size() > 32) throw new IllegalArgumentException("feature exceeds stricter observed delivery cap");
        if (steps.size() < 2) throw new IllegalArgumentException("special Round requires trigger and continuation");
        Step first = steps.get(0);
        if (first.gameType() < 1 || first.gameType() > 4) throw new IllegalArgumentException("special trigger state invalid");
        if (steps.get(steps.size() - 1).nextType() != 0) throw new IllegalArgumentException("special Round must terminate at f.nt=0");
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).gameType() == 0 || steps.get(i - 1).nextType() != steps.get(i).gameType())
                throw new IllegalArgumentException("special continuation must match previous f.nt");
        }
    }

    public static List<Integer> scatterCells(int[] board) {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < board.length; i++) if (board[i] == SCATTER) cells.add(i);
        return List.copyOf(cells);
    }

    private static boolean isWireSymbol(int symbol) {
        return symbol == 0 || symbol == SCATTER || (symbol >= 1 && symbol <= 10);
    }
}
