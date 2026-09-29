package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_LEVEL;
import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_SIZE;
import static com.cpgame.batchc.cybergo.CyberGoRules.PAYING_SYMBOLS;
import static com.cpgame.batchc.cybergo.CyberGoRules.SCATTER;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Constructed 0-win 15-cell board. Reel 0 and reel 1 use disjoint paying sets, so 243-ways
 * cannot connect three consecutive reels. Optional 0..2 scatters stay below the free trigger.
 */
final class IndependentLoss {
    private IndependentLoss() { }

    static List<String> payingBoard(RandomGenerator random) {
        List<String> pays = new ArrayList<>(PAYING_SYMBOLS);
        shuffle(pays, random);
        List<String> first = pays.subList(0, 4);
        List<String> second = pays.subList(4, pays.size());
        String[] cells = new String[15];
        for (int row = 0; row < 3; row++) cells[row] = first.get(random.nextInt(first.size()));
        for (int row = 0; row < 3; row++) cells[3 + row] = second.get(random.nextInt(second.size()));
        for (int reel = 2; reel < 5; reel++) {
            for (int row = 0; row < 3; row++) cells[reel * 3 + row] = pays.get(random.nextInt(pays.size()));
        }
        return board(cells);
    }

    static List<String> candidate(RandomGenerator random) {
        String[] cells = payingBoard(random).toArray(String[]::new);
        int scatters = random.nextInt(10) == 0 ? 2 : random.nextInt(5) == 0 ? 1 : 0;
        boolean[] used = new boolean[5];
        for (int n = 0; n < scatters; n++) {
            int reel = random.nextInt(5);
            int guard = 0;
            while (used[reel] && guard++ < 8) reel = random.nextInt(5);
            used[reel] = true;
            cells[reel * 3 + random.nextInt(3)] = SCATTER;
        }
        return board(cells);
    }

    static boolean isLoss(List<String> board) {
        return ResultUtil.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE).isLoss();
    }

    static List<String> board(String[] cells) {
        return List.of(cells[0], cells[1], cells[2], cells[3], cells[4],
                cells[5], cells[6], cells[7], cells[8], cells[9],
                cells[10], cells[11], cells[12], cells[13], cells[14]);
    }

    static void shuffle(List<String> values, RandomGenerator random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }
}
