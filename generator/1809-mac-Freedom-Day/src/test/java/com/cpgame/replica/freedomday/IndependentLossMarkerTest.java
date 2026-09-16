package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    // User-supplied complete round: three cascade Spins, eight independent losses.
    static final String SAMPLE = "ABCB59977AAAC6644CC4335224B2298B5BZ5020050050200005005000000000000004BCB5A997766C6644CC4335224B2298B5BZ050208005020000500500000000000000|727844BBCB777AA22333AAA46683258747Z020003002020900300000000000000000|2A87A222AA4466B4444677BBB5847B434BZ300202080040000206000000000000000|949483333555577886669666695B4B5BB3Z400006005020600070000000000000000|AB2A62267725589AA222979994438A9B6BZ8005005000506000060000000000000004ABA62267755589AA888979994438A9B6BZ200500500050900006000000000000000|4B77B888889966A88886BBAAAA66678328Z400005080070000203000000000000000|37795AA3334B33393333255997336A969BZ80600003000400005050000000000000047795AA8884674B73559255997336A969BZ809000000000200050500000000000000|B53B8BB9BBAAA996663BAABB923C28739AZ500203002060000205000000000000000|A787757766B22224433399444483855964Z020200700020600506000000000000000|A88A722224992222888722999487729742Z400005060006000503000000000000000|6489699822777BB88AA2BBB9263B5752ABZ200206002050200300000000000000000";

    @Test void markerProducesRealLossAndOldZeroIsNotAMarker() {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var different = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            CompleteRoundFact fact = codec.decode("#");
            assertEquals(1, fact.spins().size());
            assertEquals(1, fact.spins().get(0).size());
            var evaluation = FreedomDayResultUtil.evaluate(board(fact.spins().get(0).get(0)), BigDecimal.ONE, 38, 2);
            assertEquals(0, evaluation.getTotalMultiplier().signum());
            assertTrue(evaluation.getWins().isEmpty());
            assertEquals(0, evaluation.getAwardedFreeSpins());
            assertEquals(38, evaluation.getMultiplier(), "no multiplier increment on an independent loss");
            assertEquals(0, FreedomDayResultUtil.countVisibleSymbol(
                    board(fact.spins().get(0).get(0)), FreedomDayResultUtil.BALL),
                    "marker board must not make the client display a phantom +2");
            assertEquals("#", codec.encode(fact));
            different.add(codec.encodeFull(fact));
        }
        assertTrue(different.size() > 1, "markers must generate fresh boards");
        assertEquals(0, codec.verify("#", 10).multiplier().signum());
        for (String malformed : List.of("0", "##", "#0", "#4", "#01", "|#", "#|",
                "#" + SAMPLE.split("\\|")[1])) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(malformed), malformed);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.verify("#|#", 10));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("#", 10, true));
    }

    @Test void numberedMarkersRecreateExactBallCountAndCarryMultiplierOnZeroWin() {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        FreedomDayIndependentLossGenerator generator = new FreedomDayIndependentLossGenerator();
        for (int balls = 0; balls <= FreedomDayIndependentLossGenerator.MAX_MARKER_BALLS; balls++) {
            String marker = balls == 0 ? "#" : "#" + balls;
            FreedomDayBoard generated = generator.generateMarkerLoss(new Random(1809L + balls), true, balls);
            CompleteRoundFact fact = new CompleteRoundFact(1, false, List.of(List.of(fact(generated))));
            assertEquals(marker, codec.encode(fact));

            FreedomDayBoard decoded = board(codec.decode(marker).spins().get(0).get(0));
            assertEquals(balls, FreedomDayResultUtil.countVisibleSymbol(decoded, FreedomDayResultUtil.BALL));
            var evaluation = FreedomDayResultUtil.evaluate(decoded, BigDecimal.ONE, 4, 2);
            assertTrue(evaluation.getWins().isEmpty());
            assertEquals(0, evaluation.getAwardedFreeSpins());
            assertEquals(4 + balls * 2, evaluation.getMultiplier());
            assertEquals(0, evaluation.getTotalWin().signum());
        }
    }

    @Test void numberedMarkersCarryIntoTheNextWinningSpin() throws Exception {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        FreedomDayBoard paidLoss = board(codec.decode("#").spins().get(0).get(0));
        FreedomDayBoard oneBallLoss = board(codec.decode("#1").spins().get(0).get(0));
        FreedomDayBoard twoBallLoss = board(codec.decode("#2").spins().get(0).get(0));

        int[] winningProp = noWinSymbols();
        removeExtraSymbol(winningProp, 9, 3);
        winningProp[0] = 9;
        winningProp[5] = 9;
        winningProp[10] = 9;
        FreedomDayBoard winning = new FreedomDayBoard(winningProp, new int[]{2, 3, 4, 5});

        CompleteRoundFact fact = new CompleteRoundFact(1, false, List.of(
                List.of(fact(paidLoss)), List.of(fact(oneBallLoss)),
                List.of(fact(twoBallLoss)), List.of(fact(winning))));
        JsonNode spins = protocol(fact);

        assertEquals(4, spins.get(1).get("_ending_multiplier").asInt(), "#1: x2 -> x4");
        assertEquals(8, spins.get(2).get("_ending_multiplier").asInt(), "#2: x4 -> x8");
        JsonNode wins = spins.get(3).get("props").get(0).get("win_arr");
        assertFalse(wins.isEmpty());
        for (JsonNode win : wins) assertEquals(8, win.get("m").asInt());
        assertEquals(8, spins.get(3).get("_ending_multiplier").asInt());
    }

    @Test void protocolCountsOneLongMultiplierFrameAsOneVisibleBall() throws Exception {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        FreedomDayBoard paidLoss = board(codec.decode("#").spins().get(0).get(0));
        int[] prop = noWinSymbols();
        prop[5] = FreedomDayResultUtil.BALL;
        prop[6] = FreedomDayResultUtil.BALL;
        prop[7] = FreedomDayResultUtil.BALL;
        FreedomDayBoard longBall = new FreedomDayBoard(prop, new int[]{2, 3, 4, 5},
                List.of(List.of(5, 6, 7)), List.of(), List.of());
        CompleteRoundFact fact = new CompleteRoundFact(1, false,
                List.of(List.of(fact(paidLoss)), List.of(fact(longBall))));

        JsonNode spins = protocol(fact);
        assertEquals(4, spins.get(1).get("_ending_multiplier").asInt(),
                "a three-cell multiplier frame is one visible x2 ball");
    }

    @Test void suppliedRoundPreservesCascadePagesFreeCountAndCarriedMultiplier() throws Exception {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        CompleteRoundFact original = codec.decode(SAMPLE);
        String compact = codec.encode(original);
        String[] oldTokens = SAMPLE.split("\\|");
        String[] tokens = compact.split("\\|");
        assertEquals(11, tokens.length);
        for (int index : List.of(0, 4, 6)) {
            assertEquals(oldTokens[index], tokens[index], "whole cascade including terminal page must stay intact");
        }
        for (int index : List.of(1, 2, 3, 5, 7, 8, 9, 10)) assertEquals("#", tokens[index]);
        CompleteRoundFact materialized = codec.decode(compact);
        assertEquals(codec.verify(original, 10, 30), codec.verify(materialized, 10, 30));
        assertEquals(11, materialized.spins().size());
        for (int index : List.of(1, 2, 3, 5, 7, 8, 9, 10)) {
            FreedomDayBoard markerBoard = board(materialized.spins().get(index).get(0));
            assertTrue(FreedomDayIndependentLossGenerator.isMarkerLoss(markerBoard), "spin " + index);
            assertEquals(4, FreedomDayResultUtil.evaluate(markerBoard, BigDecimal.ONE, 4, 2).getMultiplier(),
                    "a carried x4 must remain visually and arithmetically x4");
        }
        JsonNode before = protocol(original);
        JsonNode after = protocol(materialized);
        for (int i = 0; i < before.size(); i++) {
            for (String field : List.of("total_win", "win_gold", "type", "_ending_multiplier",
                    "_awarded_free_spins", "_free_index", "_free_total", "_cumulative_free_win")) {
                assertEquals(before.get(i).get(field), after.get(i).get(field), "spin " + i + " " + field);
            }
            if (!tokens[i].equals("#")) assertEquals(original.spins().get(i), materialized.spins().get(i));
        }
        System.out.printf("MARKER_SAMPLE originalBytes=%d compressedBytes=%d markers=8 multiplier=%s%n",
                SAMPLE.length(), compact.length(), codec.verify(original, 10, 30).multiplier());
    }

    @Test void scatterTriggerAndRetriggerNeverBecomeMarkers() {
        var boards = new FreedomDayBoardGenerator(new Random(1809));
        FreedomDayBoard trigger = boards.generateFeatureTrigger();
        var evaluation = FreedomDayResultUtil.evaluate(trigger, BigDecimal.ONE, 1, 2);
        assertTrue(evaluation.getWins().isEmpty());
        assertEquals(4, evaluation.getScatterCount());
        assertEquals(10, evaluation.getAwardedFreeSpins());
        assertFalse(FreedomDayIndependentLossGenerator.isIndependentLoss(trigger));
        CompleteRoundFact fact = new CompleteRoundFact(1, false, List.of(List.of(fact(trigger)), List.of(fact(trigger))));
        CompleteRoundCodec codec = new CompleteRoundCodec();
        assertEquals(codec.encodeFull(fact), codec.encode(fact));
        assertFalse(codec.encode(fact).contains("#"));
    }

    @Test void oldFramedAndUnframedMembersStillDecodeExactly() {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String framed = SAMPLE.split("\\|")[1];
        assertEquals(framed, codec.encodeFull(codec.decode(framed)));
        CompleteRoundFact legacy = codec.decode(framed.substring(0, 34));
        assertEquals(codec.decode(framed).spins().get(0).get(0).prop(), legacy.spins().get(0).get(0).prop());
        assertEquals(codec.decode(framed).spins().get(0).get(0).trl(), legacy.spins().get(0).get(0).trl());
    }

    @Test void sharedDecoderIsSafeUnderConcurrentUse() throws Exception {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                for (int j = 0; j < 250; j++) {
                    CompleteRoundFact loss = codec.decode("#");
                    assertEquals(0, codec.verify(loss, 10, 30).multiplier().signum());
                    assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(board(loss.spins().get(0).get(0))));
                }
                return true;
            });
            for (var result : executor.invokeAll(tasks)) assertTrue(result.get());
        } finally { executor.shutdownNow(); }
    }

    private static JsonNode protocol(CompleteRoundFact fact) throws Exception {
        return new ObjectMapper().readTree(FreedomDayRoundProtocolCli.protocol(fact, new BigDecimal("0.01"), 1, 0));
    }

    private static FreedomDayBoard board(CompleteRoundFact.BoardFact fact) {
        return new FreedomDayBoard(fact.prop().stream().mapToInt(Integer::intValue).toArray(),
                fact.trl().stream().mapToInt(Integer::intValue).toArray(), fact.grids(), fact.gf(), fact.sl());
    }

    private static CompleteRoundFact.BoardFact fact(FreedomDayBoard board) {
        return new CompleteRoundFact.BoardFact(Arrays.stream(board.getProp()).boxed().toList(),
                Arrays.stream(board.getTrl()).boxed().toList(), board.getGrids(), board.getGoldFrames(), board.getSilverFrames());
    }

    private static int[] noWinSymbols() {
        int[] prop = new int[30];
        for (int reel = 0; reel < 6; reel++) {
            for (int row = 0; row < 5; row++) prop[reel * 5 + row] = 2 + (reel + row * 3) % 10;
        }
        return prop;
    }

    private static void removeExtraSymbol(int[] prop, int symbol, int reelCount) {
        for (int reel = 0; reel < reelCount; reel++) {
            for (int row = 0; row < 5; row++) {
                int index = reel * 5 + row;
                if (prop[index] == symbol) prop[index] = 8;
            }
        }
    }
}
