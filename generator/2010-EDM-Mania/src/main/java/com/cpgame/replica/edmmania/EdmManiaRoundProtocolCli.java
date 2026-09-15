package com.cpgame.replica.edmmania;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaGridRules;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaWin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** HTTP-adapter bridge: emits one complete Round using the exact Core packaged with the Redis Loader. */
public final class EdmManiaRoundProtocolCli {
    private EdmManiaRoundProtocolCli() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        BigDecimal betSize = new BigDecimal(options.getOrDefault("bet-size", "0.01"));
        int level = Integer.parseInt(options.getOrDefault("level", "1"));
        boolean featureBuy = Boolean.parseBoolean(options.getOrDefault("feature-buy", "false"));
        int maxConsecutiveWins = Integer.parseInt(options.getOrDefault("max-consecutive-wins", "10"));
        int maxMarySpins = Integer.parseInt(options.getOrDefault("max-mary-spins", "30"));
        long seed = options.containsKey("seed") ? Long.parseLong(options.get("seed")) : new SecureRandom().nextLong();
        if (betSize.signum() <= 0 || level <= 0) throw new IllegalArgumentException("bet-size and level must be positive");
        BigDecimal unitBet = betSize.multiply(BigDecimal.valueOf(level));
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(seed);
        CompleteRoundFactory.GeneratedRound generated;
        while (true) {
            try {
                generated = factory.generate(random, featureBuy, maxConsecutiveWins, maxMarySpins);
                break;
            } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                // Discard the full candidate but keep advancing the seeded stream deterministically.
            }
        }
        System.out.print(protocol(generated.fact(), unitBet, level, seed));
    }

    public static String protocol(CompleteRoundFact fact, BigDecimal unitBet, int level, long seed) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode result = mapper.createArrayNode();
        int runningTt = 0;
        int freeMultiplier = CompleteRoundFactory.FREE_START_MULTIPLIER;
        BigDecimal cumulativeFreeWin = BigDecimal.ZERO;
        long now = Instant.now().getEpochSecond();
        long orderBase = System.currentTimeMillis();
        for (int spinIndex = 0; spinIndex < fact.spins().size(); spinIndex++) {
            boolean freeMode = spinIndex > 0;
            int multiplier = freeMode ? freeMultiplier : 1;
            int increment = 2;
            BigDecimal spinMultiplier = BigDecimal.ZERO;
            int awarded = 0;
            ArrayNode pages = mapper.createArrayNode();
            List<CompleteRoundFact.BoardFact> boards = fact.spins().get(spinIndex);
            EdmManiaBoard previous = null;
            EdmManiaEvaluation previousEval = null;
            for (int pageIndex = 0; pageIndex < boards.size(); pageIndex++) {
                CompleteRoundFact.BoardFact boardFact = boards.get(pageIndex);
                EdmManiaBoard board = new EdmManiaBoard(toArray(boardFact.prop()), toArray(boardFact.trl()),
                        boardFact.grids(), boardFact.gf(), boardFact.sl());
                int newBalls = EdmManiaGridRules.countNewBalls(previous, previousEval, board);
                EdmManiaEvaluation evaluation = EdmManiaResultUtil.evaluate(
                        board, unitBet, multiplier, increment, newBalls, freeMode);
                if (pageIndex == 0) awarded = evaluation.getAwardedFreeSpins();
                spinMultiplier = spinMultiplier.add(evaluation.getTotalMultiplier());
                multiplier = evaluation.getMultiplier();
                int displayM = EdmManiaResultUtil.displayMultiplier(evaluation, board, pageIndex == 0);
                pages.add(page(mapper, boardFact, evaluation, displayM));
                if (!evaluation.getWins().isEmpty()) {
                    previous = board;
                    previousEval = evaluation;
                }
            }
            if (freeMode) {
                runningTt += awarded;
                freeMultiplier = multiplier;
            } else {
                runningTt = awarded;
                freeMultiplier = Math.max(CompleteRoundFactory.FREE_START_MULTIPLIER, multiplier);
            }
            BigDecimal spinWin = unitBet.multiply(spinMultiplier).setScale(2, RoundingMode.HALF_UP);
            if (freeMode) cumulativeFreeWin = cumulativeFreeWin.add(spinWin);
            ObjectNode payload = mapper.createObjectNode();
            String oid = "JAVA-2010-" + orderBase + "-" + String.format("%03d", spinIndex + 1);
            int type = freeMode ? 2 : (awarded > 0 || fact.featureBuy() ? 3 : 1);
            payload.put("oid", oid).put("time", now).put("level", level).put("total_win", spinWin)
                    .put("win_gold", spinWin).put("type", type);
            payload.set("props", pages);
            payload.put("_ending_multiplier", multiplier).put("_awarded_free_spins", awarded)
                    .put("_free_index", spinIndex).put("_free_total", runningTt)
                    .put("_cumulative_free_win", cumulativeFreeWin).put("_unit_bet", unitBet)
                    .put("_feature_buy", fact.featureBuy()).put("_rulesVersion", EdmManiaRulesMetadata.VERSION)
                    .put("_rulesHash", EdmManiaRulesMetadata.HASH).put("_seed", seed);
            result.add(payload);
        }
        return mapper.writeValueAsString(result);
    }

    private static ObjectNode page(ObjectMapper mapper, CompleteRoundFact.BoardFact board, EdmManiaEvaluation evaluation,
                                   int displayMultiplier) {
        ObjectNode page = mapper.createObjectNode();
        page.set("prop", mapper.valueToTree(board.prop()));
        page.set("trl", mapper.valueToTree(board.trl()));
        page.set("grids", mapper.valueToTree(board.grids()));
        page.set("gf", mapper.valueToTree(board.gf()));
        page.set("sl", mapper.valueToTree(board.sl()));
        page.put("m", Integer.toString(displayMultiplier)).put("tw", evaluation.getTotalWin());
        ArrayNode wins = mapper.createArrayNode();
        for (EdmManiaWin win : evaluation.getWins()) {
            ObjectNode item = mapper.createObjectNode();
            ArrayNode mainPositions = mapper.createArrayNode();
            for (List<Integer> positions : win.getMainPositionGroups()) {
                mainPositions.add(mapper.valueToTree(positions));
            }
            item.set("p", mainPositions);
            item.set("h", mapper.valueToTree(win.getTopPositions()));
            int odd = win.getPayOdd().intValue() * win.getWays() * win.getMultiplier();
            item.put("wm", win.getWinMoney()).put("way", win.getWays()).put("m", win.getMultiplier())
                    .put("way_n", win.getReelCount()).put("pr", win.getSymbol())
                    .put("p_odd", win.getPayOdd()).put("odd", odd);
            wins.add(item);
        }
        page.set("win_arr", wins);
        return page;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()]; for (int i = 0; i < values.size(); i++) result[i] = values.get(i); return result;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("options must be --name value");
            result.put(args[i].substring(2), args[i + 1]);
        }
        return result;
    }
}
