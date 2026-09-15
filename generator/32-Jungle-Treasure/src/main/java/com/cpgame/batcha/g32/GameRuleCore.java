package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single Java rules core for Jungle Treasure raw gid 32. */
public final class GameRuleCore {
    public static final int RAW_GAME_ID = 32;
    public static final String GAME_NAME = "Jungle Treasure";
    public static final int COLUMNS = 6;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
        new BigDecimal("0.02"), new BigDecimal("0.1"), new BigDecimal("0.2"));
    public static final String RULES_VERSION = "jt32-empirical-2026-09-09-v1";
    public static final String RULES_HASH = "0ae93f542908a7391cffe77dc02327ace428c614333bc73d711bf8e66357e68f";
    public static final List<String> PAYING_SYMBOLS =
        List.of("A", "H1", "H2", "H3", "H4", "H5", "H6", "J", "K", "Q", "T");
    public static final List<String> TRANSFORM_SYMBOLS =
        List.of("T", "H6", "J", "H5", "Q", "K", "H4", "H3", "A", "H2");
    public static final Set<String> ALL_SYMBOLS = Set.of(
        "A", "H1", "H2", "H3", "H4", "H5", "H6", "J", "K", "Q", "T", "Scat", "Wild");
    public static final int MAX_SCATTERS = 4;
    public static final int MAX_WILDS = 2;
    public static final int MAX_H1 = 2;
    public static final int FREE_GRANT = 10;
    public static final int MAX_RPX = 30;
    public static final int MAX_CASCADES = 12;

    private static final Map<String, Map<Integer, Integer>> PAYTABLE = paytable();

    private GameRuleCore() { }

    public static Map<String, Map<Integer, Integer>> symbolPayTable() { return PAYTABLE; }

    public static void validateBet(BigDecimal betSize, int betLevel) {
        if (betSize == null || BET_SIZES.stream().noneMatch(value -> value.compareTo(betSize) == 0)) {
            throw new IllegalArgumentException("bet_size must be one of 0.02, 0.1, 0.2");
        }
        if (!BET_LEVELS.contains(betLevel)) throw new IllegalArgumentException("bet_level must be 1..10");
    }

    public static BigDecimal paidBet(BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        return betSize.multiply(BigDecimal.valueOf(20L * betLevel)).stripTrailingZeros();
    }

    public static List<Token> parse(List<String> rskl) {
        if (rskl == null || rskl.isEmpty()) throw new IllegalArgumentException("rskl is empty");
        List<Token> tokens = new ArrayList<>();
        int reel = 0, index = 0, heightSum = 0;
        for (String wire : rskl) {
            if (wire == null || wire.length() < 2 || !Character.isDigit(wire.charAt(0))) {
                throw new IllegalArgumentException("invalid token: " + wire);
            }
            int height = wire.charAt(0) - '0';
            String symbol = wire.substring(1);
            if (height < 1 || height > 4 || !ALL_SYMBOLS.contains(symbol)) {
                throw new IllegalArgumentException("invalid token: " + wire);
            }
            heightSum += height;
            boolean extra = reel > 0 && reel < 5 && index == 0;
            int coord = reel * 10 + index;
            tokens.add(new Token(symbol, height, reel, index, coord, extra));
            if (extra) {
                index++;
                heightSum = 0;
            } else {
                index++;
                if (heightSum == 5) {
                    heightSum = 0;
                    reel++;
                    index = 0;
                }
            }
        }
        if (reel != 6 || heightSum != 0) throw new IllegalArgumentException("rskl does not fill 5-6-6-6-6-5");
        int cells = tokens.stream().mapToInt(Token::height).sum();
        if (cells != 34) throw new IllegalArgumentException("board must occupy 34 cells");
        return List.copyOf(tokens);
    }

    public static List<String> toRskl(List<Token> tokens) {
        return tokens.stream().map(Token::wire).toList();
    }

    public static BoardResult evaluateBoard(List<String> rskl, BigDecimal betSize, int betLevel, int roundPayX) {
        validateBet(betSize, betLevel);
        if (roundPayX < 1 || roundPayX > MAX_RPX) throw new IllegalArgumentException("rpx out of range");
        List<Token> tokens = parse(rskl);
        Map<Integer, List<Token>> byReel = new LinkedHashMap<>();
        for (int c = 0; c < COLUMNS; c++) byReel.put(c, new ArrayList<>());
        for (Token token : tokens) byReel.get(token.reel()).add(token);

        List<WinMatch> matches = new ArrayList<>();
        BigDecimal unmultiplied = BigDecimal.ZERO;
        for (String symbol : PAYING_SYMBOLS) {
            List<List<Integer>> groups = new ArrayList<>();
            int length = 0;
            int ways = 1;
            for (int c = 0; c < COLUMNS; c++) {
                List<Integer> mainHits = new ArrayList<>();
                List<Integer> extraHits = new ArrayList<>();
                for (Token token : byReel.get(c)) {
                    if (token.symbol().equals(symbol) || token.symbol().equals("Wild")) {
                        if (token.extra()) extraHits.add(token.coord());
                        else mainHits.add(token.coord());
                    }
                }
                List<Integer> hits = new ArrayList<>(mainHits);
                hits.addAll(extraHits);
                if (hits.isEmpty()) break;
                groups.add(List.copyOf(hits));
                ways *= hits.size();
                length++;
            }
            if (length < 3) continue;
            int units = PAYTABLE.get(symbol).getOrDefault(length, 0);
            if (units <= 0) continue;
            BigDecimal raw = betSize.multiply(BigDecimal.valueOf(betLevel))
                .multiply(BigDecimal.valueOf(units)).multiply(BigDecimal.valueOf(ways));
            unmultiplied = unmultiplied.add(raw);
            matches.add(new WinMatch(symbol, groups, ways, raw.stripTrailingZeros()));
        }
        BigDecimal win = unmultiplied.multiply(BigDecimal.valueOf(roundPayX)).stripTrailingZeros();
        return new BoardResult(tokens, matches, win, unmultiplied.stripTrailingZeros());
    }

    public static int scatterCount(List<Token> tokens) {
        int count = 0;
        for (Token token : tokens) if (token.symbol().equals("Scat")) count++;
        return count;
    }

    public static boolean legalSpecials(List<String> rskl) {
        List<Token> tokens;
        try { tokens = parse(rskl); } catch (RuntimeException invalid) { return false; }
        int scat = 0, wild = 0, h1 = 0;
        int[] scatCol = new int[COLUMNS];
        int[] wildCol = new int[COLUMNS];
        for (Token token : tokens) {
            if (token.symbol().equals("Scat")) { scat++; scatCol[token.reel()]++; }
            if (token.symbol().equals("Wild")) {
                wild++;
                wildCol[token.reel()]++;
                if (token.reel() == 0 || token.reel() == 5) return false;
            }
            if (token.symbol().equals("H1")) h1++;
            if (token.height() > 1 && (token.reel() == 0 || token.reel() == 5)) return false;
        }
        if (scat > MAX_SCATTERS || wild > MAX_WILDS || h1 > MAX_H1) return false;
        for (int c = 0; c < COLUMNS; c++) {
            if (scatCol[c] > 2 || wildCol[c] > 1) return false;
        }
        return true;
    }

    public static CompleteRound materialize(BigDecimal paidBet, BigDecimal betSize, int betLevel, List<Step> facts) {
        validateBet(betSize, betLevel);
        if (paidBet.compareTo(paidBet(betSize, betLevel)) != 0) {
            throw new IllegalArgumentException("paid bet must be 20*bet_size*bet_level");
        }
        if (facts.isEmpty()) throw new IllegalArgumentException("facts empty");
        List<Step> steps = new ArrayList<>();
        BigDecimal rwa = BigDecimal.ZERO;
        int rpx = 1;
        int fsn = 0;
        int nfsc = 0;
        boolean freeStarted = false;
        List<BigDecimal> freeSpinWins = new ArrayList<>();
        BigDecimal currentFreeWin = BigDecimal.ZERO;
        int cascades = 0;
        for (int i = 0; i < facts.size(); i++) {
            Step fact = facts.get(i);
            if (fact.deliveryIndex() != i) throw new IllegalArgumentException("deliveryIndex must be contiguous");
            int stepRpx = rpx;
            BoardResult board = evaluateBoard(fact.tokens(), betSize, betLevel, stepRpx);
            boolean pays = board.winAmount().signum() > 0;
            int ss = pays ? 0 : 1;
            int scat = scatterCount(board.tokens());
            if (!freeStarted && scat >= 4) fsn = FREE_GRANT;
            int sgt = freeStarted ? 2 : 0;
            int gt = freeStarted ? 2 : 1;
            BigDecimal ba = i == 0 ? paidBet : BigDecimal.ZERO;
            rwa = rwa.add(board.winAmount());
            if (freeStarted) currentFreeWin = currentFreeWin.add(board.winAmount());
            BigDecimal frwa = sumPrevious(freeSpinWins);
            if (ss != fact.spinStatus() && fact.spinStatus() != 1 && fact.winAmount().signum() == 0
                && fact.roundPayX() == 1 && fact.tokens().equals(List.of())) {
                /* unused */
            }
            steps.add(new Step(i, ba, betSize, betLevel, fact.tokens(), fact.silver(), fact.gold(),
                ss, fsn, nfsc, sgt, gt, stepRpx, rwa.stripTrailingZeros(), board.winAmount(),
                frwa.stripTrailingZeros(), board.matches()));
            if (pays) {
                cascades++;
                if (cascades > MAX_CASCADES || stepRpx > MAX_RPX) {
                    throw new IllegalArgumentException("cascade/rpx cap exceeded");
                }
                rpx = Math.min(MAX_RPX, rpx + (freeStarted ? 2 : 1));
            } else {
                cascades = 0;
                if (fsn > nfsc) {
                    if (freeStarted) {
                        freeSpinWins.add(currentFreeWin);
                        currentFreeWin = BigDecimal.ZERO;
                        nfsc++;
                    } else {
                        freeStarted = true;
                        rpx = 2;
                        nfsc = 1;
                        currentFreeWin = BigDecimal.ZERO;
                        rwa = BigDecimal.ZERO;
                    }
                }
            }
        }
        Step last = steps.getLast();
        if (last.spinStatus() != 1 || last.freeSpinNum() != last.nowFreeSpinCount()) {
            throw new IllegalArgumentException("complete Round must end at ss=1 with fsn==nfsc");
        }
        BigDecimal payout = last.roundWinAmount();
        RoundMode mode = classify(steps, payout);
        BigDecimal multiplier = payout.divide(paidBet, 8, RoundingMode.HALF_UP).stripTrailingZeros();
        return new CompleteRound(RAW_GAME_ID, mode, paidBet, betSize, betLevel, steps, payout, multiplier);
    }

    public static RoundMode classify(List<Step> steps, BigDecimal payout) {
        if (steps.stream().anyMatch(step -> step.smallGameType() == 2 || step.freeSpinNum() > 0)) return RoundMode.FREE;
        return payout.signum() == 0 ? RoundMode.LOSS : RoundMode.WIN;
    }

    public static BigDecimal evaluateRound(CompleteRound round) {
        return round.steps().getLast().roundWinAmount();
    }

    private static BigDecimal sumPrevious(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Map<Integer, Integer>> paytable() {
        Map<String, Map<Integer, Integer>> map = new LinkedHashMap<>();
        map.put("H1", Map.of(3, 30, 4, 40, 5, 60, 6, 80));
        map.put("H2", Map.of(3, 20, 4, 25, 5, 50, 6, 70));
        map.put("H3", Map.of(3, 10, 4, 25, 5, 40, 6, 60));
        map.put("H4", Map.of(3, 8, 4, 15, 5, 20, 6, 30));
        map.put("H5", Map.of(3, 6, 4, 10, 5, 12, 6, 15));
        map.put("H6", Map.of(3, 6, 4, 10, 5, 12, 6, 15));
        map.put("A", Map.of(3, 4, 4, 6, 5, 8, 6, 10));
        map.put("K", Map.of(3, 4, 4, 6, 5, 8, 6, 10));
        map.put("Q", Map.of(3, 1, 4, 2, 5, 3, 6, 4));
        map.put("J", Map.of(3, 1, 4, 2, 5, 3, 6, 4));
        map.put("T", Map.of(3, 1, 4, 2, 5, 3, 6, 4));
        return Map.copyOf(map);
    }

    public record BoardResult(List<Token> tokens, List<WinMatch> matches, BigDecimal winAmount, BigDecimal unmultiplied) {
        public BoardResult {
            tokens = List.copyOf(tokens);
            matches = List.copyOf(matches);
            winAmount = winAmount.stripTrailingZeros();
            unmultiplied = unmultiplied.stripTrailingZeros();
        }
    }
}
