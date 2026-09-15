package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayGridRules;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayWin;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Redis 极简 member：每页 68 字符（34 符号 + Z + 20 占格/框 + 补齐），付费/免费 Spin 用 {@code |}。
 * 独立、单页、无状态变化的无奖 Spin 编为 #，解码时生成真实零倍盘。
 * 连消链（包括结束页）和免费触发盘不压缩。旧 34/68 字符 member 仍可解码。
 */
public final class CompleteRoundCodec {
    private static final int DEFAULT_MAX_MARY_SPINS = 30;
    private static final int BOARD_SYMBOLS = 34;
    private static final int INNER_CELLS = 20;
    private static final char OCCUPANCY_MARK = 'Z';
    private static final int PAGE_WITH_FRAMES = BOARD_SYMBOLS * 2;
    public static final String INDEPENDENT_LOSS = "#";
    private static final SecureRandom LOSS_RANDOM = new SecureRandom();
    private static final FreedomDayIndependentLossGenerator LOSSES = new FreedomDayIndependentLossGenerator();
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(CompleteRoundFact fact) {
        return encode(fact, true);
    }

    /** 保留原盘用于精确回归和审计；Redis 正式写入使用 encode。 */
    public String encodeFull(CompleteRoundFact fact) {
        return encode(fact, false);
    }

    private String encode(CompleteRoundFact fact, boolean compactLosses) {
        // JILI compact ASCII: 34 symbol chars + 20 inner-reel frame chars per page.
        // Pages have no delimiter; paid/free Spin boundaries use '|'.
        StringJoiner spins = new StringJoiner("|");
        for (List<CompleteRoundFact.BoardFact> spin : fact.spins()) {
            if (compactLosses && spin.size() == 1
                    && FreedomDayIndependentLossGenerator.isIndependentLoss(board(spin.get(0)))) {
                spins.add(INDEPENDENT_LOSS);
                continue;
            }
            StringBuilder pages = new StringBuilder(spin.size() * PAGE_WITH_FRAMES);
            for (CompleteRoundFact.BoardFact page : spin) {
                appendSymbols(pages, page.prop());
                appendSymbols(pages, page.trl());
                pages.append(OCCUPANCY_MARK);
                appendFrames(pages, page);
                while (pages.length() % BOARD_SYMBOLS != 0) pages.append('0');
            }
            spins.add(pages);
        }
        return spins.toString();
    }

    public CompleteRoundFact decode(String value) {
        return decode(value, false);
    }

    /** Reads the new minimal array and remains compatible with old wrapped members. */
    public CompleteRoundFact decode(String value, boolean featureBuy) {
        try {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) throw new IllegalArgumentException("empty compact round");
            if (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') {
                return decodeCompactAscii(trimmed, featureBuy);
            }
            JsonNode root = mapper.readTree(trimmed);
            if (root.isObject()) return mapper.treeToValue(root, CompleteRoundFact.class);
            if (!root.isArray() || root.isEmpty()) throw new IllegalArgumentException("empty compact round");
            List<List<CompleteRoundFact.BoardFact>> spins;
            if (root.get(0).isObject()) {
                List<CompleteRoundFact.BoardFact> pages = mapper.convertValue(root,
                        new TypeReference<List<CompleteRoundFact.BoardFact>>() { });
                spins = List.of(pages);
            } else {
                spins = mapper.convertValue(root,
                        new TypeReference<List<List<CompleteRoundFact.BoardFact>>>() { });
            }
            return new CompleteRoundFact(CompleteRoundFact.VERSION, featureBuy, spins);
        }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("cannot decode round", ex); }
    }

    private CompleteRoundFact decodeCompactAscii(String value, boolean featureBuy) {
        String[] encodedSpins = value.split("\\|", -1);
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>(encodedSpins.length);
        for (String encodedSpin : encodedSpins) {
            // 只接受整个 Spin token；绝不替换连消页或牌面中的字符 0。
            if (INDEPENDENT_LOSS.equals(encodedSpin)) {
                FreedomDayBoard loss = LOSSES.generate(LOSS_RANDOM, !spins.isEmpty());
                spins.add(List.of(new CompleteRoundFact.BoardFact(
                        Arrays.stream(loss.getProp()).boxed().toList(),
                        Arrays.stream(loss.getTrl()).boxed().toList(),
                        loss.getGrids(), loss.getGoldFrames(), loss.getSilverFrames())));
                continue;
            }
            if (encodedSpin.isEmpty() || encodedSpin.length() % BOARD_SYMBOLS != 0) {
                throw new IllegalArgumentException("invalid compact spin length");
            }
            List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
            int offset = 0;
            while (offset < encodedSpin.length()) {
                boolean framed = offset + PAGE_WITH_FRAMES <= encodedSpin.length()
                        && encodedSpin.charAt(offset + BOARD_SYMBOLS) == OCCUPANCY_MARK;
                int pageSize = framed ? PAGE_WITH_FRAMES : BOARD_SYMBOLS;
                List<Integer> prop = new ArrayList<>(30);
                List<Integer> trl = new ArrayList<>(4);
                for (int i = 0; i < 30; i++) prop.add(decodeSymbol(encodedSpin.charAt(offset + i)));
                for (int i = 30; i < BOARD_SYMBOLS; i++) trl.add(decodeSymbol(encodedSpin.charAt(offset + i)));
                List<List<Integer>> grids = new ArrayList<>();
                List<List<Integer>> gold = new ArrayList<>();
                List<List<Integer>> silver = new ArrayList<>();
                if (framed) {
                    applyOccupancy(encodedSpin.substring(offset + BOARD_SYMBOLS + 1,
                            offset + BOARD_SYMBOLS + 1 + INNER_CELLS), grids, gold, silver);
                } else {
                    grids.addAll(FreedomDayGridRules.inferMergedGroups(toArray(prop)));
                }
                pages.add(new CompleteRoundFact.BoardFact(prop, trl, grids, gold, silver));
                offset += pageSize;
            }
            spins.add(List.copyOf(pages));
        }
        return new CompleteRoundFact(CompleteRoundFact.VERSION, featureBuy, spins);
    }

    private void appendFrames(StringBuilder target, CompleteRoundFact.BoardFact page) {
        for (int reel = 1; reel <= 4; reel++) {
            for (int row = 0; row < 5; row++) {
                int index = reel * 5 + row;
                List<Integer> group = FreedomDayGridRules.groupAt(page.grids(), index);
                if (group == null || group.get(0) != index) {
                    target.append('0');
                    continue;
                }
                int height = group.size();
                boolean gold = page.gf().contains(group);
                boolean silver = page.sl().contains(group);
                target.append(occupancyChar(height, gold, silver));
            }
        }
    }

    private void applyOccupancy(String overlay, List<List<Integer>> grids,
                                List<List<Integer>> gold, List<List<Integer>> silver) {
        if (overlay.length() != INNER_CELLS) throw new IllegalArgumentException("invalid occupancy overlay");
        for (int reel = 1; reel <= 4; reel++) {
            int row = 0;
            while (row < 5) {
                char mark = overlay.charAt((reel - 1) * 5 + row);
                if (mark == '0') {
                    row++;
                    continue;
                }
                int height = occupancyHeight(mark);
                if (row + height > 5) throw new IllegalArgumentException("occupancy height exceeds reel");
                List<Integer> group = new ArrayList<>(height);
                for (int offset = 0; offset < height; offset++) group.add(reel * 5 + row + offset);
                List<Integer> frozen = List.copyOf(group);
                grids.add(frozen);
                int frame = occupancyFrame(mark);
                if (frame == 2) gold.add(frozen);
                else if (frame == 1) silver.add(frozen);
                row += height;
            }
        }
    }

    private static char occupancyChar(int height, boolean gold, boolean silver) {
        if (height < 2 || height > 4) throw new IllegalArgumentException("merged grid height must be 2..4");
        if (gold && silver) throw new IllegalArgumentException("one grid cannot be both gold and silver");
        int base = height;
        if (gold) base += 6;
        else if (silver) base += 3;
        return Character.toUpperCase(Character.forDigit(base, 11));
    }

    private static int occupancyHeight(char mark) {
        int value = Character.digit(mark, 11);
        if (value < 2 || value > 10) throw new IllegalArgumentException("invalid occupancy mark: " + mark);
        return (value - 2) % 3 + 2;
    }

    private static int occupancyFrame(char mark) {
        int value = Character.digit(mark, 11);
        return (value - 2) / 3; // 0 none, 1 silver, 2 gold
    }

    private void appendSymbols(StringBuilder target, List<Integer> symbols) {
        for (int symbol : symbols) {
            if (symbol < 1 || symbol > 13) throw new IllegalArgumentException("symbol out of compact range: " + symbol);
            target.append(Character.toUpperCase(Character.forDigit(symbol, 14)));
        }
    }

    private int decodeSymbol(char encoded) {
        int symbol = Character.digit(encoded, 14);
        if (symbol < 1 || symbol > 13) throw new IllegalArgumentException("invalid compact symbol: " + encoded);
        return symbol;
    }

    public RoundVerification verify(String value, int maxConsecutiveWins) {
        return verify(value, maxConsecutiveWins, false, DEFAULT_MAX_MARY_SPINS);
    }

    public RoundVerification verify(String value, int maxConsecutiveWins, boolean featureBuy) {
        return verify(value, maxConsecutiveWins, featureBuy, DEFAULT_MAX_MARY_SPINS);
    }

    public RoundVerification verify(String value, int maxConsecutiveWins, boolean featureBuy, int maxMarySpins) {
        return verify(decode(value, featureBuy), maxConsecutiveWins, maxMarySpins);
    }

    /** 直接验证实际牌面，避免 encode 的无奖压缩掩盖原始候选问题或重复随机生成。 */
    public RoundVerification verify(CompleteRoundFact fact, int maxConsecutiveWins, int maxMarySpins) {
        if (maxMarySpins < 1) throw new IllegalArgumentException("max-mary-spins must be >= 1");
        BigDecimal total = BigDecimal.ZERO;
        int freeTotal = 0;
        int freeDelivered = Math.max(0, fact.spins().size() - 1);
        int freeMultiplier = 2;
        int maxObserved = 0;
        int pages = 0;
        for (int spinIndex = 0; spinIndex < fact.spins().size(); spinIndex++) {
            List<CompleteRoundFact.BoardFact> spin = fact.spins().get(spinIndex);
            if (spin.isEmpty()) throw new IllegalArgumentException("spin has no pages");
            boolean freeMode = spinIndex > 0;
            int multiplier = freeMode ? freeMultiplier : 1;
            int increment = 2;
            int consecutive = 0;
            for (int pageIndex = 0; pageIndex < spin.size(); pageIndex++) {
                FreedomDayBoard board = board(spin.get(pageIndex));
                FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, multiplier, increment);
                total = total.add(evaluation.getTotalMultiplier());
                multiplier = evaluation.getMultiplier();
                pages++;
                if (pageIndex == 0) {
                    if (spinIndex == 0) freeTotal = evaluation.getAwardedFreeSpins();
                    else freeTotal += evaluation.getAwardedFreeSpins();
                    if (freeTotal > maxMarySpins) {
                        throw new IllegalArgumentException("max Mary spins exceeded");
                    }
                }
                boolean last = pageIndex == spin.size() - 1;
                if (evaluation.getWins().isEmpty()) {
                    if (!last) throw new IllegalArgumentException("page after terminal no-win");
                } else {
                    consecutive++;
                    if (last) throw new IllegalArgumentException("winning spin has no terminal no-win page");
                    FreedomDayBoard next = board(spin.get(pageIndex + 1));
                    verifyCascade(board, evaluation, next);
                }
            }
            if (consecutive > maxConsecutiveWins) throw new IllegalArgumentException("max consecutive wins exceeded");
            maxObserved = Math.max(maxObserved, consecutive);
            if (freeMode) freeMultiplier = multiplier;
        }
        int expectedFree = freeTotal;
        if (freeDelivered != expectedFree) {
            throw new IllegalArgumentException("free spin count mismatch: expected=" + expectedFree + ", actual=" + freeDelivered);
        }
        if (fact.featureBuy() && expectedFree == 0) throw new IllegalArgumentException("feature-buy round did not trigger free spins");
        return new RoundVerification(total.stripTrailingZeros(), fact.spins().size(), pages, maxObserved, fact.featureBuy());
    }

    private FreedomDayBoard board(CompleteRoundFact.BoardFact fact) {
        return new FreedomDayBoard(toArray(fact.prop()), toArray(fact.trl()), fact.grids(), fact.gf(), fact.sl());
    }

    private int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private void verifyCascade(FreedomDayBoard current, FreedomDayEvaluation evaluation, FreedomDayBoard next) {
        Set<Integer> removedMain = new HashSet<>();
        Set<Integer> removedTop = new HashSet<>();
        for (FreedomDayWin win : evaluation.getWins()) {
            removedMain.addAll(win.getMainPositions());
            removedTop.addAll(win.getTopPositions());
        }
        Set<Integer> transformedMain = new HashSet<>();
        for (List<Integer> frame : current.getGoldFrames()) {
            if (frame.stream().anyMatch(removedMain::contains)) transformedMain.addAll(frame);
        }
        for (List<Integer> frame : current.getSilverFrames()) {
            if (frame.stream().anyMatch(removedMain::contains)) transformedMain.addAll(frame);
        }
        removedMain.removeAll(transformedMain);
        int[] before = current.getProp();
        int[] after = next.getProp();
        for (int reel = 0; reel < FreedomDayBoard.REEL_COUNT; reel++) {
            final int currentReel = reel;
            List<Integer> survivorIndexes = new ArrayList<>();
            for (int row = 0; row < FreedomDayBoard.ROW_COUNT; row++) {
                int index = reel * FreedomDayBoard.ROW_COUNT + row;
                if (!removedMain.contains(index)) survivorIndexes.add(index);
            }
            int start = reel * FreedomDayBoard.ROW_COUNT + FreedomDayBoard.ROW_COUNT - survivorIndexes.size();
            Set<Integer> mappedSurvivorCells = new HashSet<>();
            for (int i = 0; i < survivorIndexes.size(); i++) {
                int oldIndex = survivorIndexes.get(i);
                int newIndex = start + i;
                mappedSurvivorCells.add(newIndex);
                if (!transformedMain.contains(oldIndex) && after[newIndex] != before[oldIndex]) {
                    throw new IllegalArgumentException("invalid main cascade continuity");
                }
            }
            for (List<Integer> oldGroup : current.getGrids()) {
                if (oldGroup.get(0) / FreedomDayBoard.ROW_COUNT != reel || oldGroup.stream().anyMatch(removedMain::contains)) continue;
                List<Integer> mapped = oldGroup.stream().map(old -> start + survivorIndexes.indexOf(old)).toList();
                if (!next.getGrids().contains(mapped)) {
                    throw new IllegalArgumentException("merged grid lost or changed occupancy across cascade");
                }
                boolean transformed = oldGroup.stream().anyMatch(transformedMain::contains);
                if (!transformed && current.getGoldFrames().contains(oldGroup) != next.getGoldFrames().contains(mapped)) {
                    throw new IllegalArgumentException("gold frame changed without a win transition");
                }
                if (!transformed && current.getSilverFrames().contains(oldGroup) != next.getSilverFrames().contains(mapped)) {
                    throw new IllegalArgumentException("silver frame changed without a win transition");
                }
            }
            for (List<Integer> newGroup : next.getGrids()) {
                if (newGroup.get(0) / FreedomDayBoard.ROW_COUNT != reel) continue;
                if (newGroup.stream().anyMatch(mappedSurvivorCells::contains)) {
                    boolean expected = current.getGrids().stream().anyMatch(oldGroup -> {
                        if (oldGroup.get(0) / FreedomDayBoard.ROW_COUNT != currentReel
                                || oldGroup.stream().anyMatch(removedMain::contains)) return false;
                        List<Integer> mapped = oldGroup.stream().map(old -> start + survivorIndexes.indexOf(old)).toList();
                        return mapped.equals(newGroup);
                    });
                    if (!expected) throw new IllegalArgumentException("new merged grid consumed an unchanged survivor");
                }
            }
        }
        List<Integer> topSurvivors = new ArrayList<>();
        int[] oldTop = current.getTrl();
        int[] nextTop = next.getTrl();
        for (int i = 0; i < oldTop.length; i++) if (!removedTop.contains(i)) topSurvivors.add(oldTop[i]);
        for (int i = 0; i < topSurvivors.size(); i++) {
            if (nextTop[i] != topSurvivors.get(i)) throw new IllegalArgumentException("invalid top cascade continuity");
        }
    }
}
