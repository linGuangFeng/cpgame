package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Constructs an ordinary-loss board independently of Redis: no 3-reel ways hit and
 * fewer than 4 Scat tokens. Verified by ResultUtil. Used by the Loader to seed the
 * 0-multiplier pool so the first ordinary round can be 0x; Demo must not call this
 * at request time.
 */
public final class LuckyPandaIndependentLossGenerator {
    private static final int ATTEMPTS = 5;
    // A losing PAN way must miss at least one of the first three reels (at least 5 cells).
    public static final int MAX_MARKER_PANS = LuckyPandaBoard.VISIBLE_CELLS - 5;
    private static final java.util.concurrent.ConcurrentMap<Integer, List<LuckyPandaBoard>> PAN_DEFAULTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** #N stores PAN RLE blocks, not payout or the final accumulated multiplier. */
    public static LuckyPandaBoard generateWithPanCount(java.util.Random random, int pans) {
        if (random == null || pans < 0 || pans > MAX_MARKER_PANS) {
            throw new IllegalArgumentException("PAN count out of marker range");
        }
        List<LuckyPandaBoard> defaults = PAN_DEFAULTS.computeIfAbsent(pans, count -> {
            java.util.Random source = new java.security.SecureRandom();
            List<LuckyPandaBoard> pool = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                LuckyPandaBoard board = panLossCandidate(source, count);
                if (!validPanLoss(board, count)) throw new IllegalStateException("invalid PAN loss reserve");
                pool.add(board);
            }
            return List.copyOf(pool);
        });
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            LuckyPandaBoard board = panLossCandidate(random, pans);
            if (validPanLoss(board, pans)) return board;
        }
        return defaults.get(random.nextInt(defaults.size()));
    }

    private static boolean validPanLoss(LuckyPandaBoard board, int pans) {
        return board.tokens(LuckyPandaSymbol.PAN) == pans && board.scatterTokens() == 0
                && GameRuleCore.withinCapturedCaps(board)
                && !LuckyPandaResultUtil.evaluate(board, BigDecimal.ONE, 1, 0).hasWaysWin();
    }

    private static LuckyPandaBoard panLossCandidate(java.util.Random random, int pans) {
        // Disjoint ordinary symbols on reels 0 and 1 block every ordinary way.
        // PAN is absent from reel 0, so a specified number of PAN blocks cannot win either.
        List<LuckyPandaSymbol> pays = new ArrayList<>(GameRuleCore.payingSymbols());
        pays.remove(LuckyPandaSymbol.PAN);
        java.util.Collections.shuffle(pays, random);
        List<LuckyPandaSymbol> set0 = new ArrayList<>(pays.subList(0, 5));
        List<LuckyPandaSymbol> set1 = new ArrayList<>(pays.subList(5, pays.size()));
        List<List<Block>> reels = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        reels.add(outerBlocks(random, set0, 5));
        reels.add(innerBlocks(random, set1));
        reels.add(innerBlocks(random, pays));
        reels.add(innerBlocks(random, pays));
        reels.add(innerBlocks(random, pays));
        reels.add(outerBlocks(random, pays, 5));
        int convertible = 0;
        for (int reel = 1; reel < LuckyPandaBoard.REEL_COUNT; reel++) convertible += reels.get(reel).size();
        while (convertible < pans) {
            boolean split = false;
            for (int reel = 1; reel < LuckyPandaBoard.REEL_COUNT && convertible < pans; reel++) {
                List<Block> column = reels.get(reel);
                for (int i = column.size() - 1; i >= 0 && convertible < pans; i--) {
                    Block block = column.get(i);
                    if (block.height <= 1) continue;
                    column.set(i, new Block(1, block.symbol));
                    column.add(i + 1, new Block(block.height - 1, block.symbol));
                    convertible++;
                    split = true;
                    break;
                }
            }
            if (!split) throw new IllegalStateException("cannot split enough PAN slots");
        }
        List<int[]> slots = new ArrayList<>();
        for (int reel = 1; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            for (int i = 0; i < reels.get(reel).size(); i++) slots.add(new int[]{reel, i});
        }
        java.util.Collections.shuffle(slots, random);
        for (int i = 0; i < pans; i++) {
            int[] slot = slots.get(i);
            Block block = reels.get(slot[0]).get(slot[1]);
            reels.get(slot[0]).set(slot[1], new Block(block.height, LuckyPandaSymbol.PAN));
        }
        List<String> rskl = new ArrayList<>();
        for (List<Block> column : reels) {
            for (Block block : column) rskl.add(block.height + block.symbol.wireName());
        }
        return LuckyPandaBoard.fromRskl(rskl);
    }

    private static List<Block> outerBlocks(java.util.Random random, List<LuckyPandaSymbol> set, int count) {
        List<Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blocks.add(new Block(1, set.get(random.nextInt(set.size()))));
        return blocks;
    }

    private static List<Block> innerBlocks(java.util.Random random, List<LuckyPandaSymbol> set) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(1, set.get(random.nextInt(set.size()))));
        int filled = 1;
        while (filled < 6) {
            int remaining = 6 - filled;
            int height = nextLossHeight(random, remaining);
            blocks.add(new Block(height, set.get(random.nextInt(set.size()))));
            filled += height;
        }
        return blocks;
    }

    private static int nextLossHeight(java.util.Random random, int remaining) {
        if (remaining <= 1) return 1;
        int[] table = LuckyPandaHeightModel.innerMainHeights(WeightScene.PAID_START);
        int usable = Math.min(4, remaining);
        int total = 0;
        for (int i = 0; i < usable; i++) total += table[i];
        int pick = random.nextInt(total);
        for (int i = 0; i < usable; i++) {
            pick -= table[i];
            if (pick < 0) return i + 1;
        }
        return 1;
    }

    private record Block(int height, LuckyPandaSymbol symbol) { }


    private final LuckyPandaBoardGenerator boards;
    private final BigDecimal betSize;
    private final int betLevel;
    private final List<LuckyPandaBoard> defaults;
    private static final java.util.concurrent.ConcurrentMap<String,List<LuckyPandaBoard>> DEFAULT_POOLS = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.security.SecureRandom fallbackRandom = new java.security.SecureRandom();

    public LuckyPandaIndependentLossGenerator(LuckyPandaBoardGenerator boards, BigDecimal betSize, int betLevel) {
        this.boards = boards;
        this.betSize = betSize;
        this.betLevel = betLevel;
        defaults=DEFAULT_POOLS.computeIfAbsent(boards.lossConfigurationKey(),key->{
            LuckyPandaBoardGenerator source=boards.independentCopy();
            var pool=new ArrayList<LuckyPandaBoard>(10);
            for(int i=0;i<10;i++) {
                LuckyPandaBoard board=guaranteedLoss(WeightScene.PAID_START,source);
                if(!isOrdinaryLoss(board))throw new IllegalStateException("invalid loss default");
                pool.add(board);
            }
            return List.copyOf(pool);
        });
    }

    public LuckyPandaBoard generate(WeightScene scene) {
        return generateWithCandidates(() -> generateCandidate(scene));
    }

    LuckyPandaBoard generateWithCandidates(java.util.function.Supplier<LuckyPandaBoard> proposals) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            LuckyPandaBoard candidate = proposals.get();
            if (candidate != null && isOrdinaryLoss(candidate)) return candidate;
        }
        return defaults.get(fallbackRandom.nextInt(10));
    }

    public LuckyPandaBoard generateCandidate(WeightScene scene) {
        // Cap trigger symbols before blocking Ways; later replacement must not reopen a win.
        return stripThirdReel(boards.lossSeed(scene), scene);
    }

    private boolean isOrdinaryLoss(LuckyPandaBoard candidate) {
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(candidate, betSize, betLevel, 0);
        return !evaluation.hasWaysWin()
                && evaluation.scatterTokens() < LuckyPandaResultUtil.SCATTER_TRIGGER_TOKENS
                && GameRuleCore.withinCapturedCaps(candidate);
    }

    private LuckyPandaBoard stripThirdReel(LuckyPandaBoard board, WeightScene scene) {
        List<List<LuckyPandaToken>> reels = board.reels();
        boolean[] first = present(reels.get(0));
        boolean[] second = present(reels.get(1));
        boolean[] forbidden = new boolean[LuckyPandaSymbol.values().length];
        for (LuckyPandaSymbol symbol : GameRuleCore.payingSymbols()) {
            if (first[symbol.ordinal()] && second[symbol.ordinal()]) forbidden[symbol.ordinal()] = true;
        }
        List<String> rskl = new ArrayList<>();
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            for (LuckyPandaToken token : reels.get(reel)) {
                LuckyPandaSymbol symbol = token.symbol();
                if (reel <= 1 && symbol == LuckyPandaSymbol.WILD) {
                    symbol = boards.nextNonScatterNonWild(scene);
                } else if (reel == 2 && (symbol == LuckyPandaSymbol.WILD
                        || (symbol.paying() && forbidden[symbol.ordinal()]))) {
                    symbol = safePaying(forbidden, scene);
                }
                rskl.add(token.height() + symbol.wireName());
            }
        }
        return LuckyPandaBoard.fromRskl(rskl);
    }

    private LuckyPandaBoard capScatter(LuckyPandaBoard board, WeightScene scene) {
        List<List<LuckyPandaSymbol>> cells = new ArrayList<>();
        for (List<LuckyPandaSymbol> column : board.cells()) cells.add(new ArrayList<>(column));
        int tokens = 0;
        List<List<LuckyPandaToken>> reels = board.reels();
        for (int reel = 0; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            int row = 0;
            for (LuckyPandaToken token : reels.get(reel)) {
                if (token.symbol() == LuckyPandaSymbol.SCAT) {
                    tokens++;
                    if (tokens >= LuckyPandaResultUtil.SCATTER_TRIGGER_TOKENS) {
                        LuckyPandaSymbol replacement = boards.nextNonScatterNonWild(scene);
                        for (int i = 0; i < token.height(); i++) cells.get(reel).set(row + i, replacement);
                    }
                }
                row += token.height();
            }
        }
        return LuckyPandaBoard.fromCells(cells);
    }

    /**
     * First-shot 0x board: reels 0 and 1 have disjoint paying sets, so no 3-reel ways
     * can form. Scatter stays below the trigger. Used when sampling+strip cannot land
     * a loss in {@link #ATTEMPTS} tries.
     */
    private LuckyPandaBoard guaranteedLoss(WeightScene scene, LuckyPandaBoardGenerator source) {
        List<List<LuckyPandaSymbol>> cells = new ArrayList<>(LuckyPandaBoard.REEL_COUNT);
        cells.add(List.of(
                LuckyPandaSymbol.T, LuckyPandaSymbol.J, LuckyPandaSymbol.Q,
                LuckyPandaSymbol.K, LuckyPandaSymbol.A));
        cells.add(List.of(
                LuckyPandaSymbol.H1, LuckyPandaSymbol.H2, LuckyPandaSymbol.H3,
                LuckyPandaSymbol.H4, LuckyPandaSymbol.H5, LuckyPandaSymbol.H1));
        List<LuckyPandaSymbol> reel2 = new ArrayList<>();
        for (int i = 0; i < LuckyPandaBoard.ROW_COUNTS[2]; i++) {
            reel2.add(source.nextNonScatterNonWild(scene));
        }
        cells.add(reel2);
        for (int reel = 3; reel < LuckyPandaBoard.REEL_COUNT; reel++) {
            List<LuckyPandaSymbol> column = new ArrayList<>();
            for (int i = 0; i < LuckyPandaBoard.ROW_COUNTS[reel]; i++) {
                column.add(source.nextNonScatterNonWild(scene));
            }
            cells.add(column);
        }
        return capScatter(LuckyPandaBoard.fromCells(cells), scene);
    }

    private boolean[] present(List<LuckyPandaToken> column) {
        boolean[] flags = new boolean[LuckyPandaSymbol.values().length];
        boolean wild = false;
        for (LuckyPandaToken token : column) {
            if (token.symbol() == LuckyPandaSymbol.WILD) wild = true;
            else if (token.symbol().paying()) flags[token.symbol().ordinal()] = true;
        }
        if (wild) {
            for (LuckyPandaSymbol symbol : GameRuleCore.payingSymbols()) flags[symbol.ordinal()] = true;
        }
        return flags;
    }

    private LuckyPandaSymbol safePaying(boolean[] forbidden, WeightScene scene) {
        for (int i = 0; i < 16; i++) {
            LuckyPandaSymbol symbol = boards.nextNonScatterNonWild(scene);
            if (symbol.paying() && !forbidden[symbol.ordinal()]) return symbol;
        }
        for (LuckyPandaSymbol symbol : GameRuleCore.payingSymbols()) {
            if (!forbidden[symbol.ordinal()]) return symbol;
        }
        return LuckyPandaSymbol.T;
    }
}
