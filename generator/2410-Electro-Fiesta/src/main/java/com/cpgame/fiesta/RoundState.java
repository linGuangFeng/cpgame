package com.cpgame.fiesta;

import java.util.Arrays;

public final class RoundState {
    public enum Mode { NORMAL, RESPIN_UNTIL_WIN, MULTIPLIER_STICKY }
    private final Mode mode;
    private final int[] board;
    private final int[] multipliers;
    private final int[] addedPositions;
    private final int remaining;
    private final int respinColumn;
    private final int baseSymbol;

    public RoundState(Mode mode, int[] board, int[] multipliers, int[] addedPositions, int remaining, int respinColumn, int baseSymbol) {
        this.mode = mode; this.board = board.clone(); this.multipliers = multipliers.clone();
        this.addedPositions = addedPositions.clone(); this.remaining = remaining;
        this.respinColumn = respinColumn; this.baseSymbol = baseSymbol;
    }
    public Mode mode(){ return mode; }
    public int[] board(){ return board.clone(); }
    public int[] multipliers(){ return multipliers.clone(); }
    public int[] addedPositions(){ return addedPositions.clone(); }
    public int remaining(){ return remaining; }
    public int respinColumn(){ return respinColumn; }
    public int baseSymbol(){ return baseSymbol; }
    @Override public String toString(){ return mode+":"+Arrays.toString(board)+":"+remaining; }
}

