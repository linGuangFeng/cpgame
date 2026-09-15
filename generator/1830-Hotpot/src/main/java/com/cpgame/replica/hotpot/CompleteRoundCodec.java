package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotIndependentLossGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotEvaluation;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotPageKind;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotWin;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Redis compact ASCII member: 36 symbol chars per page, pages concatenated, paid/free spins joined by '|'.
 * Symbols 1..23 use radix-24 digits. No JSON, no occupancy grids, no derived win fields.
 */
public final class CompleteRoundCodec {
    private static final int PAGE_CHARS = HotpotBoard.SIZE;
    private static final int RADIX = 24;

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public String encode(CompleteRoundFact fact) { return encode(fact, true); }
    public String encodeFull(CompleteRoundFact fact) { return encode(fact, false); }

    private String encode(CompleteRoundFact fact, boolean compact) {
        StringJoiner spins = new StringJoiner("|");
        for (int index = 0; index < fact.spins().size(); index++) {
            List<CompleteRoundFact.BoardFact> spin = fact.spins().get(index);
            HotpotSpinMode mode = index == 0 ? HotpotSpinMode.PAID : HotpotSpinMode.FREE;
            if (compact && spin.size() == 1
                    && HotpotIndependentLossGenerator.isIndependentLoss(spin.get(0).toBoard(), mode)) {
                spins.add("#");
                continue;
            }
            StringBuilder pages = new StringBuilder(spin.size() * PAGE_CHARS);
            for (CompleteRoundFact.BoardFact page : spin) {
                for (int symbol : page.prop()) {
                    char encoded = Character.toUpperCase(Character.forDigit(symbol, RADIX));
                    if (encoded == '\0') throw new IllegalArgumentException("symbol out of compact range: " + symbol);
                    pages.append(encoded);
                }
            }
            spins.add(pages);
        }
        String value = spins.toString();
        if (value.isEmpty() || value.charAt(0) == '{' || value.charAt(0) == '[') {
            throw new IllegalStateException("compact member must not be JSON");
        }
        return value;
    }

    public CompleteRoundFact decode(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("empty compact round");
        if (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[') {
            throw new IllegalArgumentException("JSON members are forbidden");
        }
        String[] encodedSpins = trimmed.split("\\|", -1);
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>(encodedSpins.length);
        for (String encodedSpin : encodedSpins) {
            if (encodedSpin.equals("#")) {
                HotpotSpinMode mode = spins.isEmpty() ? HotpotSpinMode.PAID : HotpotSpinMode.FREE;
                HotpotBoard board = new HotpotIndependentLossGenerator().generate(RANDOM, mode);
                spins.add(List.of(CompleteRoundFact.fromBoard(board)));
                continue;
            }
            if (encodedSpin.isEmpty() || encodedSpin.length() % PAGE_CHARS != 0) {
                throw new IllegalArgumentException("invalid compact spin length");
            }
            List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
            for (int offset = 0; offset < encodedSpin.length(); offset += PAGE_CHARS) {
                List<Integer> prop = new ArrayList<>(PAGE_CHARS);
                for (int i = 0; i < PAGE_CHARS; i++) {
                    prop.add(decodeSymbol(encodedSpin.charAt(offset + i)));
                }
                pages.add(new CompleteRoundFact.BoardFact(prop));
            }
            spins.add(List.copyOf(pages));
        }
        return new CompleteRoundFact(CompleteRoundFact.VERSION, spins);
    }

    public RoundVerification verify(String value, int maxConsecutiveWins, int maxFreeSpins) {
        return verify(decode(value), maxConsecutiveWins, maxFreeSpins);
    }

    public RoundVerification verify(CompleteRoundFact fact, int maxConsecutiveWins, int maxFreeSpins) {
        if (maxConsecutiveWins < 1) throw new IllegalArgumentException("max-consecutive-wins must be >= 1");
        if (maxFreeSpins < 1) throw new IllegalArgumentException("max-free-spins must be >= 1");
        int total = 0;
        int freeDelivered = Math.max(0, fact.spins().size() - 1);
        int freeTotal = 0;
        int maxObserved = 0;
        int pages = 0;
        for (int spinIndex = 0; spinIndex < fact.spins().size(); spinIndex++) {
            List<CompleteRoundFact.BoardFact> spin = fact.spins().get(spinIndex);
            HotpotSpinMode mode = spinIndex == 0 ? HotpotSpinMode.PAID : HotpotSpinMode.FREE;
            int consecutive = 0;
            List<HotpotBoard> boards = new ArrayList<>(spin.size());
            for (int pageIndex = 0; pageIndex < spin.size(); pageIndex++) {
                HotpotBoard board = spin.get(pageIndex).toBoard();
                boards.add(board);
                verifyMaterialLimits(board, mode);
                HotpotEvaluation evaluation = HotpotResultUtil.evaluate(board);
                pages++;
                boolean last = pageIndex == spin.size() - 1;
                switch (evaluation.getPageKind()) {
                    case TERMINAL_NO_WIN -> {
                        if (!last) throw new IllegalArgumentException("page after terminal no-win");
                    }
                    case WIN -> {
                        if (last) throw new IllegalArgumentException("winning spin has no terminal no-win page");
                        consecutive++;
                        verifyCascade(board, evaluation, spin.get(pageIndex + 1).toBoard());
                    }
                }
            }
            if (consecutive > maxConsecutiveWins) throw new CandidateLimitException("max consecutive wins exceeded");
            maxObserved = Math.max(maxObserved, consecutive);
            total = Math.addExact(total, HotpotResultUtil.spinIntegerMultiplier(boards));
            HotpotEvaluation lastPage = HotpotResultUtil.evaluate(boards.get(boards.size() - 1));
            int awarded = HotpotResultUtil.awardedFreeSpins(lastPage.getScatterCount(), mode);
            if (spinIndex == 0) freeTotal = awarded;
            else freeTotal += awarded;
            if (freeTotal > maxFreeSpins) throw new CandidateLimitException("max free spins exceeded");
        }
        if (freeDelivered != freeTotal) {
            throw new IllegalArgumentException("free spin count mismatch: expected=" + freeTotal
                    + ", actual=" + freeDelivered);
        }
        boolean scatter = freeTotal > 0;
        return new RoundVerification(total, fact.spins().size(), pages, maxObserved, scatter);
    }

    /** A structurally valid candidate exceeded a material or round length limit. */
    public static final class CandidateLimitException extends IllegalArgumentException {
        public CandidateLimitException(String message) { super(message); }
    }

    private void verifyMaterialLimits(HotpotBoard board, HotpotSpinMode mode) {
        int[] prop = board.getProp();
        int totalScatter = 0;
        for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
            int columnScatter = 0;
            for (int row = 0; row < HotpotBoard.ROWS; row++) {
                if (prop[col * HotpotBoard.ROWS + row] == HotpotResultUtil.SCATTER) {
                    columnScatter++;
                    totalScatter++;
                }
            }
            if (columnScatter > HotpotResultUtil.MAX_SCATTER_PER_COLUMN) {
                throw new CandidateLimitException("Scatter exceeds per-column cap at API column " + col);
            }
        }
        int pageCap = mode == HotpotSpinMode.FREE
                ? HotpotResultUtil.MAX_SCATTER_FREE_START_PAGE
                : HotpotResultUtil.MAX_SCATTER_PAID_PAGE;
        if (totalScatter > pageCap) {
            throw new CandidateLimitException("Scatter exceeds " + mode + " page cap");
        }
    }

    private void verifyCascade(HotpotBoard current, HotpotEvaluation evaluation, HotpotBoard next) {
        boolean[] removed = HotpotResultUtil.eliminatedMask(current, evaluation);
        int[] before = current.getProp();
        int[] after = next.getProp();
        for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
            int[] keep = new int[HotpotBoard.ROWS];
            int kept = 0;
            for (int row = 0; row < HotpotBoard.ROWS; row++) {
                int index = col * HotpotBoard.ROWS + row;
                if (!removed[index]) keep[kept++] = before[index];
            }
            int fill = HotpotBoard.ROWS - kept;
            for (int i = 0; i < kept; i++) {
                if (after[col * HotpotBoard.ROWS + fill + i] != keep[i]) {
                    throw new IllegalArgumentException("invalid cascade continuity");
                }
            }
        }
        for (HotpotWin win : evaluation.getWins()) {
            if (win.getCount() < HotpotResultUtil.MIN_MATCH) {
                throw new IllegalArgumentException("win below minimum count");
            }
        }
    }

    private int decodeSymbol(char encoded) {
        int symbol = Character.digit(encoded, RADIX);
        if (symbol < HotpotBoard.MIN_SYMBOL || symbol > HotpotBoard.MAX_SYMBOL) {
            throw new IllegalArgumentException("invalid compact symbol: " + encoded);
        }
        return symbol;
    }
}
