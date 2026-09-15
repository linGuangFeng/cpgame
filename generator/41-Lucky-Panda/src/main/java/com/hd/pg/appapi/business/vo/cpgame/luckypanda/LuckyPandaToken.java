package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

/** One rskl RLE token. Inner reel first token is the extra top cell (height always 1). */
public final class LuckyPandaToken {
    private final LuckyPandaSymbol symbol;
    private final int height;
    private final int coord;
    private final boolean top;
    private final int reel;

    public LuckyPandaToken(LuckyPandaSymbol symbol, int height, int coord, boolean top, int reel) {
        if (symbol == null) throw new IllegalArgumentException("symbol is required");
        if (height < 1 || height > 4) throw new IllegalArgumentException("token height must be 1..4");
        if (top && height != 1) throw new IllegalArgumentException("inner top token height must be 1");
        if ((reel == 0 || reel == 5) && height != 1) {
            throw new IllegalArgumentException("outer reel token height must be 1");
        }
        this.symbol = symbol;
        this.height = height;
        this.coord = coord;
        this.top = top;
        this.reel = reel;
    }

    public LuckyPandaSymbol symbol() { return symbol; }
    public int height() { return height; }
    public int coord() { return coord; }
    public boolean top() { return top; }
    public int reel() { return reel; }

    public String wire() { return height + symbol.wireName(); }
}
