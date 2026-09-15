package com.cpgame.coinmastergo.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Source-protocol card-material semantics.
 *
 * <p>The provider transmits Gold through {@code gfl}. On reels 2-4 every H1..H8
 * transport cell not present in {@code gfl} is the ordinary/internal Silver partition;
 * WILD, SC and the outer reels have no such material. The replica keeps that decoded
 * list internally, while the wire remains identical to the captured provider response.</p>
 */
public final class CardMaterialState {
    private CardMaterialState() { }

    public static boolean eligible(List<String> board, int reel, int transportRow) {
        return reel >= 1 && reel <= 3
                && transportRow >= 0 && transportRow < GameRules.TRANSPORT_ROWS
                && GameRules.PAYING_SYMBOLS.contains(
                board.get(reel * GameRules.TRANSPORT_ROWS + transportRow));
    }

    public static List<Integer> eligibleCoordinates(List<String> board) {
        List<Integer> coordinates = new ArrayList<>();
        for (int reel = 1; reel <= 3; reel++) {
            for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                if (eligible(board, reel, row)) coordinates.add(reel * 10 + row);
            }
        }
        return List.copyOf(coordinates);
    }

    /** Decodes the captured two-state partition without inventing a wire field. */
    public static List<Integer> decodeSilverCoordinates(List<String> board, List<Integer> goldCoordinates) {
        Set<Integer> gold = new HashSet<>(goldCoordinates);
        return eligibleCoordinates(board).stream().filter(coordinate -> !gold.contains(coordinate)).toList();
    }
}
