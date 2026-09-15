package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

/**
 * Server-authoritative cascade multiplier (spin {@code rpx}). Frontend
 * {@code totalTimes = rpx || 1} drives the top ×N banner; Pan sprite {@code times=2}
 * is only first-collect animation.
 * <p>
 * Reconstructed from field-evidence-matrix + 3382 original-http pages (teacher-forced
 * 99.54% paid / 90.77% free). rpx never decreases. 0 means ×1.
 * Paid: {@code max(prev, 2 * Pan RLE blocks on this page)}.
 * First free page from rpx=0: {@code 2 + 2 * panBlocks}. Later free-spin starts add
 * {@code 2 * panBlocks}. Cascade pages inside a spin keep max().
 */
public final class LuckyPandaRpxTracker {
    private int rpx;
    private int lastNfsc;

    public LuckyPandaRpxTracker() {
        this(0, 0);
    }

    /** Resume from the preceding stored page when decoding a mixed explicit-page/marker round. */
    public LuckyPandaRpxTracker(int rpx, int lastNfsc) {
        if (rpx < 0 || lastNfsc < 0) throw new IllegalArgumentException("negative multiplier context");
        this.rpx = rpx;
        this.lastNfsc = lastNfsc;
    }

    public int next(LuckyPandaBoard board, int nfsc) {
        if (board == null) throw new IllegalArgumentException("board is required");
        if (nfsc < 0) throw new IllegalArgumentException("nfsc must be >= 0");
        int pans = board.tokens(LuckyPandaSymbol.PAN);
        if (nfsc == 0) {
            if (pans > 0) rpx = Math.max(rpx, 2 * pans);
            lastNfsc = 0;
            return rpx;
        }
        boolean newSpin = nfsc != lastNfsc;
        lastNfsc = nfsc;
        if (newSpin) {
            if (rpx == 0) rpx = 2 + 2 * pans;
            else rpx += 2 * pans;
            return rpx;
        }
        if (pans > 0) rpx = Math.max(rpx, 2 * pans);
        return rpx;
    }

    public int current() { return rpx; }
}
