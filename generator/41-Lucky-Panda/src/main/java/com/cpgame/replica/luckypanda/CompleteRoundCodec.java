package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaRpxTracker;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaSymbol;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Headerless ASCII member: ordered rskl pages + per-page rpx.
 * Legacy lp1 envelopes remain readable; bets are supplied by the live request.
 * ResultUtil restores wmkl/wa/ss/fsn/nfsc/actualMultiplier. Not a JSON envelope.
 */
public final class CompleteRoundCodec {
    public static final String VERSION = "lp1";

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public String encode(CompleteRoundFact fact) { return encode(fact, true); }
    public String encodeFull(CompleteRoundFact fact) { return encode(fact, false); }

    private String encode(CompleteRoundFact fact, boolean compact) {
        MarkerContext context = new MarkerContext();
        StringBuilder out = new StringBuilder();
        if (!compact) {
            out.append(VERSION)
                    .append("|bs=").append(fact.betSize().toPlainString())
                    .append("|bl=").append(fact.betLevel()).append("|P=");
        }
        out.append(encodeSegment(fact.paid(), 0, context, compact));
        for (int i = 0; i < fact.freeSpins().size(); i++) {
            out.append(compact ? "|" : "|F=").append(encodeSegment(fact.freeSpins().get(i), i + 1, context, compact));
        }
        String member = out.toString();
        if (member.indexOf('{') >= 0 || member.indexOf('[') >= 0) {
            throw new IllegalStateException("member must not contain JSON");
        }
        return member;
    }

    public CompleteRoundFact decode(String member) {
        if (member == null || member.isBlank()) throw new IllegalArgumentException("empty member");
        if (member.charAt(0) == '{' || member.charAt(0) == '[') {
            throw new IllegalArgumentException("JSON members are not accepted");
        }
        String[] parts = member.split("\\|", -1);
        if (!VERSION.equals(parts[0])) {
            // Segment position determines paid/free mode. Unit stake is only for
            // verification; SpinProjector uses the actual request's bs/bl.
            MarkerContext context = new MarkerContext();
            List<CompleteRoundFact.PageFact> paidPages = decodeSegment(parts[0], 0, context);
            List<List<CompleteRoundFact.PageFact>> freePages = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                freePages.add(decodeSegment(parts[i], i, context));
            }
            return new CompleteRoundFact(BigDecimal.ONE, 1, paidPages, freePages);
        }
        if (parts.length < 4) throw new IllegalArgumentException("incomplete legacy member");
        BigDecimal betSize = null;
        int betLevel = -1;
        String paid = null;
        List<String> free = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("bs=")) betSize = new BigDecimal(part.substring(3));
            else if (part.startsWith("bl=")) betLevel = Integer.parseInt(part.substring(3));
            else if (part.startsWith("P=")) paid = part.substring(2);
            else if (part.startsWith("F=")) free.add(part.substring(2));
            else throw new IllegalArgumentException("unknown member field: " + part);
        }
        if (betSize == null || betLevel < 1 || paid == null) {
            throw new IllegalArgumentException("member missing bs/bl/P");
        }
        MarkerContext context = new MarkerContext();
        List<CompleteRoundFact.PageFact> paidPages = decodeSegment(paid, 0, context);
        List<List<CompleteRoundFact.PageFact>> freePages = new ArrayList<>();
        for (int i = 0; i < free.size(); i++) freePages.add(decodeSegment(free.get(i), i + 1, context));
        return new CompleteRoundFact(betSize, betLevel, paidPages, freePages);
    }

    public RoundVerification verify(String member, int maxConsecutiveWins) {
        return verify(decode(member), maxConsecutiveWins);
    }

    public RoundVerification verify(CompleteRoundFact fact, int maxConsecutiveWins) {
        BigDecimal rwa = BigDecimal.ZERO;
        int maxWins = 0;
        int consecutive = 0;
        int fsn = 0;
        int nfsc = 0;
        LuckyPandaEvaluation last = null;
        for (int i = 0; i < fact.paid().size(); i++) {
            CompleteRoundFact.PageFact page = fact.paid().get(i);
            if (!GameRuleCore.withinCapturedCaps(page.board())) {
                throw new IllegalStateException("member page exceeds captured Scat/Wild caps");
            }
            LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(
                    page.board(), fact.betSize(), fact.betLevel(), page.rpx());
            rwa = rwa.add(evaluation.wa());
            last = evaluation;
            if (evaluation.hasWaysWin()) {
                consecutive++;
                maxWins = Math.max(maxWins, consecutive);
                if (evaluation.ss() != 0) throw new IllegalStateException("winning paid page must have ss=0");
            } else {
                consecutive = 0;
                if (evaluation.ss() != 1) throw new IllegalStateException("terminal paid page must have ss=1");
            }
        }
        if (last == null) throw new IllegalStateException("empty paid segment");
        boolean scatterFree = LuckyPandaResultUtil.scatterFreeTrigger(last, 0);
        if (scatterFree) {
            fsn = LuckyPandaResultUtil.scatterFreeAwarded(last.scatterTokens());
            for (List<CompleteRoundFact.PageFact> spin : fact.freeSpins()) {
                CompleteRoundFact.PageFact term = spin.get(spin.size() - 1);
                LuckyPandaEvaluation termEval = LuckyPandaResultUtil.evaluate(
                        term.board(), fact.betSize(), fact.betLevel(), term.rpx());
                if (LuckyPandaResultUtil.scatterRetrigger(termEval)) {
                    fsn += LuckyPandaResultUtil.scatterFreeAwarded(termEval.scatterTokens());
                }
            }
            if (fact.freeSpins().size() != fsn) {
                throw new IllegalStateException("scatter-free round must contain " + fsn + " free spins");
            }
        }
        if (!scatterFree && !fact.freeSpins().isEmpty()) {
            throw new IllegalStateException("ordinary round must not contain free spins");
        }
        for (int spin = 0; spin < fact.freeSpins().size(); spin++) {
            nfsc = spin + 1;
            consecutive = 0;
            LuckyPandaEvaluation freeLast = null;
            for (CompleteRoundFact.PageFact page : fact.freeSpins().get(spin)) {
                if (!GameRuleCore.withinCapturedCaps(page.board())) {
                    throw new IllegalStateException("member page exceeds captured Scat/Wild caps");
                }
                LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(
                        page.board(), fact.betSize(), fact.betLevel(), page.rpx());
                rwa = rwa.add(evaluation.wa());
                freeLast = evaluation;
                last = evaluation;
                if (evaluation.hasWaysWin()) {
                    consecutive++;
                    maxWins = Math.max(maxWins, consecutive);
                } else {
                    consecutive = 0;
                }
            }
            if (freeLast == null || freeLast.ss() != 1) {
                throw new IllegalStateException("free spin must end on ss=1");
            }
        }
        if (maxWins > maxConsecutiveWins) {
            throw new IllegalStateException("consecutive wins exceed cap");
        }
        if (!LuckyPandaResultUtil.roundTerminal(last.ss(), fsn, nfsc)) {
            throw new IllegalStateException("member is not a complete Round");
        }
        int multiplier = LuckyPandaResultUtil.integerMultiplier(rwa, fact.betSize(), fact.betLevel());
        RoundClass roundClass = GameRuleCore.classifyRound(fsn, rwa);
        return new RoundVerification(rwa, multiplier, roundClass, fact.paid().size(),
                fact.freeSpins().size(), maxWins, GameRuleCore.isSpecialPool(roundClass));
    }

    private String encodeSegment(List<CompleteRoundFact.PageFact> pages, int nfsc,
                                 MarkerContext context, boolean compact) {
        StringJoiner joiner = new StringJoiner(";");
        for (CompleteRoundFact.PageFact page : pages) {
            int expected = context.next(page.board(), nfsc);
            int pans = page.board().tokens(LuckyPandaSymbol.PAN);
            boolean independent = pages.size() == 1 && page.rpx() == expected
                    && pans <= LuckyPandaIndependentLossGenerator.MAX_MARKER_PANS
                    && GameRuleCore.withinCapturedCaps(page.board())
                    && page.board().scatterTokens() < LuckyPandaResultUtil.SCATTER_TRIGGER_TOKENS
                    && !LuckyPandaResultUtil.evaluate(page.board(), BigDecimal.ONE, 1, page.rpx()).hasWaysWin();
            joiner.add(compact && independent ? "#" + pans : encodePage(page, compact));
            context.restore(page.rpx(), nfsc);
        }
        return joiner.toString();
    }

    private static final class MarkerContext {
        private int rpx;
        private int nfsc;
        int next(LuckyPandaBoard board, int spin) {
            return new LuckyPandaRpxTracker(rpx, nfsc).next(board, spin);
        }
        void restore(int multiplier, int spin) { rpx = multiplier; nfsc = spin; }
    }

    private static String encodePage(CompleteRoundFact.PageFact page, boolean compact) {
        StringBuilder out = new StringBuilder();
        out.append(compact ? CompactBoardCodec.encode(page.board()) : String.join(",", page.board().toRskl()))
                .append('@').append(page.rpx());
        if (!page.gfl().isEmpty()) {
            out.append("#G");
            for (int i = 0; i < page.gfl().size(); i++) {
                if (i > 0) out.append('.');
                out.append(page.gfl().get(i));
            }
        }
        if (!page.sfl().isEmpty()) {
            out.append("#S");
            for (int i = 0; i < page.sfl().size(); i++) {
                if (i > 0) out.append('.');
                out.append(page.sfl().get(i));
            }
        }
        return out.toString();
    }

    private List<CompleteRoundFact.PageFact> decodeSegment(String encoded, int nfsc, MarkerContext context) {
        if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException("empty segment");
        if (encoded.matches("#(?:0|[1-9][0-9]?)")) {
            int pans = Integer.parseInt(encoded.substring(1));
            LuckyPandaBoard board = LuckyPandaIndependentLossGenerator.generateWithPanCount(RANDOM, pans);
            int rpx = context.next(board, nfsc);
            context.restore(rpx, nfsc);
            return List.of(new CompleteRoundFact.PageFact(board, rpx, List.of(), List.of()));
        }
        String[] pages = encoded.split(";", -1);
        List<CompleteRoundFact.PageFact> out = new ArrayList<>(pages.length);
        for (String page : pages) {
            int at = page.lastIndexOf('@');
            if (at <= 0) throw new IllegalArgumentException("page missing rpx");
            String boardText = page.substring(0, at);
            LuckyPandaBoard board = boardText.indexOf(',') >= 0
                    ? LuckyPandaBoard.fromRskl(List.of(boardText.split("\\,", -1)))
                    : CompactBoardCodec.decode(boardText);
            String rest = page.substring(at + 1);
            int hash = rest.indexOf('#');
            int rpx;
            List<Integer> gfl = List.of();
            List<Integer> sfl = List.of();
            if (hash < 0) {
                rpx = Integer.parseInt(rest);
            } else {
                rpx = Integer.parseInt(rest.substring(0, hash));
                for (String part : rest.substring(hash).split("#")) {
                    if (part.isEmpty()) continue;
                    if (part.charAt(0) == 'G') gfl = parseCoords(part.substring(1));
                    else if (part.charAt(0) == 'S') sfl = parseCoords(part.substring(1));
                    else throw new IllegalArgumentException("unknown page overlay: " + part);
                }
            }
            out.add(new CompleteRoundFact.PageFact(
                    board, rpx, gfl, sfl));
            context.restore(rpx, nfsc);
        }
        return List.copyOf(out);
    }

    private static List<Integer> parseCoords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("\\.", -1);
        List<Integer> coords = new ArrayList<>(parts.length);
        for (String part : parts) coords.add(Integer.parseInt(part));
        return List.copyOf(coords);
    }
}
