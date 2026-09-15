package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 6-reel RLE board with rowCounts [5,6,6,6,6,5].
 * Inner reels store one extra top token then five main cells; coords match the frontend
 * {@code ""+reel+tokenCounter} assignment, not raw cell rows.
 */
public final class LuckyPandaBoard {
    public static final int REEL_COUNT = 6;
    public static final int[] ROW_COUNTS = {5, 6, 6, 6, 6, 5};
    public static final int VISIBLE_CELLS = 34;
    private static final Pattern TOKEN = Pattern.compile("^(\\d+)(.+)$");

    private final List<List<LuckyPandaToken>> reels;

    public LuckyPandaBoard(List<List<LuckyPandaToken>> reels) {
        if (reels == null || reels.size() != REEL_COUNT) {
            throw new IllegalArgumentException("board must have 6 reels");
        }
        List<List<LuckyPandaToken>> copy = new ArrayList<>(REEL_COUNT);
        int cells = 0;
        for (int reel = 0; reel < REEL_COUNT; reel++) {
            List<LuckyPandaToken> tokens = List.copyOf(reels.get(reel));
            if (tokens.isEmpty()) throw new IllegalArgumentException("empty reel " + reel);
            int height = 0;
            int tops = 0;
            for (LuckyPandaToken token : tokens) {
                if (token.reel() != reel) throw new IllegalArgumentException("token reel mismatch");
                if (token.top()) tops++;
                height += token.height();
            }
            if (height != ROW_COUNTS[reel]) {
                throw new IllegalArgumentException("reel " + reel + " height " + height
                        + " != " + ROW_COUNTS[reel]);
            }
            if (reel == 0 || reel == 5) {
                if (tops != 0) throw new IllegalArgumentException("outer reel cannot have top tokens");
            } else if (tops != 1 || !tokens.get(0).top()) {
                throw new IllegalArgumentException("inner reel " + reel + " must start with one top token");
            }
            cells += height;
            copy.add(tokens);
        }
        if (cells != VISIBLE_CELLS) throw new IllegalArgumentException("visible cells must be 34");
        this.reels = List.copyOf(copy);
    }

    public List<List<LuckyPandaToken>> reels() { return reels; }
    public List<LuckyPandaToken> reel(int index) { return reels.get(index); }

    public List<String> toRskl() {
        List<String> tokens = new ArrayList<>();
        for (List<LuckyPandaToken> reel : reels) {
            for (LuckyPandaToken token : reel) tokens.add(token.wire());
        }
        return List.copyOf(tokens);
    }

    public int scatterTokens() {
        int count = 0;
        for (int reel = 0; reel < REEL_COUNT; reel++) count += scatterTokensOnReel(reel);
        return count;
    }

    public int scatterCells() {
        int count = 0;
        for (int reel = 0; reel < REEL_COUNT; reel++) count += scatterCellsOnReel(reel);
        return count;
    }

    public int scatterTokensOnReel(int reel) {
        int count = 0;
        for (LuckyPandaToken token : reels.get(reel)) {
            if (token.symbol() == LuckyPandaSymbol.SCAT) count++;
        }
        return count;
    }

    public int scatterCellsOnReel(int reel) {
        return cellsOnReel(reel, LuckyPandaSymbol.SCAT);
    }

    public int tokens(LuckyPandaSymbol symbol) {
        int count = 0;
        for (int reel = 0; reel < REEL_COUNT; reel++) count += tokensOnReel(reel, symbol);
        return count;
    }

    public int cells(LuckyPandaSymbol symbol) {
        int count = 0;
        for (int reel = 0; reel < REEL_COUNT; reel++) count += cellsOnReel(reel, symbol);
        return count;
    }

    public int tokensOnReel(int reel, LuckyPandaSymbol symbol) {
        int count = 0;
        for (LuckyPandaToken token : reels.get(reel)) {
            if (token.symbol() == symbol) count++;
        }
        return count;
    }

    public int cellsOnReel(int reel, LuckyPandaSymbol symbol) {
        int count = 0;
        for (LuckyPandaToken token : reels.get(reel)) {
            if (token.symbol() == symbol) count += token.height();
        }
        return count;
    }

    public LuckyPandaToken tokenAt(int coord) {
        for (List<LuckyPandaToken> reel : reels) {
            for (LuckyPandaToken token : reel) {
                if (token.coord() == coord) return token;
            }
        }
        return null;
    }

    public static LuckyPandaBoard fromRskl(List<String> rskl) {
        if (rskl == null || rskl.isEmpty()) throw new IllegalArgumentException("rskl is required");
        List<List<LuckyPandaToken>> reels = new ArrayList<>();
        for (int i = 0; i < REEL_COUNT; i++) reels.add(new ArrayList<>());
        int reel = 0;
        int tokenRow = 0;
        int filled = 0;
        for (String raw : rskl) {
            Matcher matcher = TOKEN.matcher(raw);
            if (!matcher.matches()) throw new IllegalArgumentException("bad rskl token: " + raw);
            int height = Integer.parseInt(matcher.group(1));
            LuckyPandaSymbol symbol = LuckyPandaSymbol.fromWire(matcher.group(2));
            int coord = Integer.parseInt("" + reel + tokenRow);
            boolean top = reel > 0 && reel < 5 && tokenRow == 0;
            LuckyPandaToken token = new LuckyPandaToken(symbol, height, coord, top, reel);
            filled += height;
            if (top) {
                reels.get(reel).add(token);
                tokenRow++;
                filled = 0;
            } else {
                reels.get(reel).add(token);
                tokenRow++;
                if (filled == 5) {
                    filled = 0;
                    reel++;
                    tokenRow = 0;
                }
            }
        }
        if (reel != REEL_COUNT) throw new IllegalArgumentException("rskl did not fill 6 reels");
        return new LuckyPandaBoard(reels);
    }

    /** Expand to per-reel cells, top first on inner reels. */
    public List<List<LuckyPandaSymbol>> cells() {
        List<List<LuckyPandaSymbol>> out = new ArrayList<>(REEL_COUNT);
        for (int reel = 0; reel < REEL_COUNT; reel++) {
            List<LuckyPandaSymbol> cells = new ArrayList<>(ROW_COUNTS[reel]);
            for (LuckyPandaToken token : reels.get(reel)) {
                for (int i = 0; i < token.height(); i++) cells.add(token.symbol());
            }
            out.add(List.copyOf(cells));
        }
        return List.copyOf(out);
    }

    public static LuckyPandaBoard fromCells(List<List<LuckyPandaSymbol>> cells) {
        if (cells == null || cells.size() != REEL_COUNT) {
            throw new IllegalArgumentException("cells must have 6 reels");
        }
        List<String> rskl = new ArrayList<>();
        for (int reel = 0; reel < REEL_COUNT; reel++) {
            List<LuckyPandaSymbol> column = cells.get(reel);
            if (column.size() != ROW_COUNTS[reel]) {
                throw new IllegalArgumentException("reel " + reel + " cell count");
            }
            if (reel == 0 || reel == 5) {
                for (LuckyPandaSymbol symbol : column) rskl.add("1" + symbol.wireName());
            } else {
                rskl.add("1" + column.get(0).wireName());
                int row = 1;
                while (row < column.size()) {
                    LuckyPandaSymbol symbol = column.get(row);
                    int height = 1;
                    while (row + height < column.size() && column.get(row + height) == symbol && height < 4) {
                        height++;
                    }
                    rskl.add(height + symbol.wireName());
                    row += height;
                }
            }
        }
        return fromRskl(rskl);
    }
}
