package com.cpgame.clubgoddess.core;

import java.util.List;

/** Generates a fresh board candidate without deciding its outcome or protocol projection. */
public interface BoardCandidateGenerator {
    List<Integer> nextBoard();
    default List<Integer> nextFreeBoard(int remainingWildBudget) { return nextBoard(); }

    default java.util.List<Integer> nextLossBoard(){return nextBoard();}
}
