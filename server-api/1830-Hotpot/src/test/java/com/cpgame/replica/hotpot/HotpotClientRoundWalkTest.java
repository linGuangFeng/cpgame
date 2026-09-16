package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoardGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HotpotClientRoundWalkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capturedOrdinaryWinAndScatterArePlayable() throws Exception {
        assertNull(HotpotClientRoundWalk.hangReason(projectHistoryWin()));
        JsonNode scatter = JSON.readTree(Files.readAllBytes(
                Path.of("D:/work/hd/cpgame/captures/1830-Hotpot/history-detail-SCATTER_FREE_SPINS.json")));
        ObjectNode paid = JSON.createObjectNode();
        paid.set("bet", scatter.get("bet"));
        paid.set("bet_gold", scatter.get("bet_gold"));
        paid.set("change_gold", scatter.get("change_gold"));
        paid.set("end_gold", scatter.get("end_gold"));
        paid.set("start_gold", scatter.get("end_gold"));
        paid.set("total_win", scatter.get("total_win"));
        paid.set("frees", scatter.get("frees"));
        paid.set("props", scatter.get("result"));
        paid.put("type", 1);
        assertNull(HotpotClientRoundWalk.hangReason(paid), HotpotClientRoundWalk.hangReason(paid));
    }

    @Test
    void generatedScatterAndWinRoundsArePlayable() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(1830L);
        int[] opening = RedisDirectLoader.specialEntryOpeningWeights(HotpotBoardGenerator.defaultPaidStartWeights());
        int walked = 0;
        for (int i = 0; i < 2500 && walked < 12; i++) {
            try {
                CompleteRoundFactory.GeneratedRound round = factory.generate(random, 10, 30, opening,
                        HotpotBoardGenerator.defaultCascadeWeights(), HotpotBoardGenerator.defaultFreeStartWeights());
                HotpotSpinProjector projector = new HotpotSpinProjector();
                List<CompleteRoundFact.BoardFact> paidPages = round.fact().spins().get(0);
                JsonNode data = projector.project(paidPages, HotpotSpinMode.PAID,
                        new BigDecimal("0.02"), 1, new BigDecimal("10000.00"), 1L, 0, 0, BigDecimal.ZERO).data();
                assertNull(HotpotClientRoundWalk.hangReason(data), HotpotClientRoundWalk.hangReason(data));
                JsonNode serialized = JSON.readTree(JSON.writeValueAsBytes(data));
                assertTrue(serialized.get("change_gold").isNumber());
                assertTrue(serialized.get("total_win").isNumber());
                assertTrue(serialized.path("frees").path("st").isNumber());
                walked++;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
            } catch (Exception ex) {
                fail(ex);
            }
        }
        assertTrue(walked >= 8, "walked=" + walked);
    }

    @Test
    void nonLastPageWithoutHolesIsAHang() {
        ObjectNode data = JSON.createObjectNode();
        data.put("change_gold", -0.3);
        data.put("bet_gold", 0.4);
        data.put("total_win", 0.1);
        data.put("start_gold", 10000);
        data.put("end_gold", 9999.7);
        data.putObject("frees").put("st", 16).put("tt", 16);
        data.put("type", 1);
        ArrayNode props = data.putArray("props");
        ObjectNode first = props.addObject();
        ArrayNode prop = first.putArray("prop");
        for (int i = 0; i < 36; i++) prop.add(i == 0 || i == 6 || i == 12 ? 11 : i % 10 + 1);
        first.set("win_arr", JSON.createArrayNode());
        ObjectNode last = props.addObject();
        last.set("prop", prop.deepCopy());
        last.set("win_arr", JSON.createArrayNode());
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("SCATTERMOVE") || reason.contains("no holes"), reason);
    }

    @Test
    void sixScatterPaidSpinWithZeroFreesIsAHang() {
        ObjectNode data = JSON.createObjectNode();
        data.put("change_gold", -0.3);
        data.put("bet_gold", 0.4);
        data.put("total_win", 0.1);
        data.put("start_gold", 10000);
        data.put("end_gold", 9999.7);
        data.putObject("frees").put("st", 0).put("tt", 0);
        data.put("type", 1);
        ArrayNode props = data.putArray("props");
        ObjectNode page = props.addObject();
        ArrayNode prop = page.putArray("prop");
        int[] board = {11,1,2,3,4,5, 11,6,7,8,9,10, 11,1,2,3,4,5, 11,6,7,8,9,10, 11,1,2,3,4,5, 11,6,7,8,9,10};
        for (int symbol : board) prop.add(symbol);
        page.set("win_arr", JSON.createArrayNode());
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("Scatter") || reason.contains("st==0"), reason);
    }

    @Test
    void stringChangeGoldIsAHang() {
        ObjectNode data = JSON.createObjectNode();
        data.put("change_gold", "-0.30");
        data.put("bet_gold", 0.4);
        data.put("total_win", 0.1);
        data.put("start_gold", 10000);
        data.put("end_gold", 9999.7);
        data.putObject("frees").put("st", 0);
        ArrayNode props = data.putArray("props");
        ObjectNode page = props.addObject();
        ArrayNode prop = page.putArray("prop");
        for (int i = 0; i < 36; i++) prop.add((i % 7) + 1);
        page.set("win_arr", JSON.createArrayNode());
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("toFixed"), reason);
    }

    @Test
    void winArrayMustBeDerivedFromTheSixBySixMatrix() {
        ObjectNode data = playableEnvelope();
        ObjectNode page = data.putArray("props").addObject();
        ArrayNode prop = page.putArray("prop");
        for (int i = 0; i < 36; i++) prop.add(i < 8 ? 1 : (i % 9) + 2);
        page.putArray("win_arr");
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("does not match the 6x6 matrix"), reason);
    }

    @Test
    void cascadeMustPreserveEachColumnInFrontendGravityOrder() {
        ObjectNode data = playableEnvelope();
        ArrayNode pages = data.putArray("props");
        ObjectNode first = pages.addObject();
        ArrayNode before = first.putArray("prop");
        for (int i = 0; i < 36; i++) before.add(i < 8 ? 1 : (i % 9) + 2);
        first.putArray("win_arr").addObject().put("p", 1).put("n", 8).put("odd", 40);
        ObjectNode last = pages.addObject();
        ArrayNode after = last.putArray("prop");
        for (int i = 0; i < 36; i++) after.add(before.get(i).asInt());
        for (int i = 0; i < 6; i++) after.set(i, JSON.getNodeFactory().numberNode(i + 2));
        after.set(6, JSON.getNodeFactory().numberNode(8));
        after.set(7, JSON.getNodeFactory().numberNode(9));
        after.set(8, JSON.getNodeFactory().numberNode(10));
        after.set(9, before.get(8));
        after.set(10, before.get(9));
        after.set(11, before.get(10));
        last.putArray("win_arr");
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("breaks 6x6 column"), reason);
    }

    @Test
    void freeLastPageRetriggerIsAHangAfterGetFreeTimesView() {
        ObjectNode data = playableEnvelope();
        data.put("type", 2);
        data.put("change_gold", 0);
        data.put("total_win", 0);
        data.with("frees").put("st", 7).put("tt", 15);
        ObjectNode page = data.putArray("props").addObject();
        ArrayNode prop = page.putArray("prop");
        for (int i = 0; i < 36; i++) prop.add((i % 9) + 1);
        prop.set(2, JSON.getNodeFactory().numberNode(11));
        prop.set(14, JSON.getNodeFactory().numberNode(11));
        page.putArray("win_arr");
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("ADDSCATTER") || reason.contains("FreeSpinWon"), reason);
    }

    @Test
    void generatedFreeSpinsDoNotRetriggerOnTheOpeningPage() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(1830L);
        int seenFree = 0;
        for (int i = 0; i < 800 && seenFree < 40; i++) {
            try {
                CompleteRoundFactory.GeneratedRound round = factory.generate(random, 10, 30);
                HotpotSpinProjector projector = new HotpotSpinProjector();
                int remaining = 0;
                int totalAwarded = 0;
                java.math.BigDecimal feature = java.math.BigDecimal.ZERO;
                for (int spin = 0; spin < round.fact().spins().size(); spin++) {
                    HotpotSpinMode mode = spin == 0 ? HotpotSpinMode.PAID : HotpotSpinMode.FREE;
                    JsonNode data = projector.project(round.fact().spins().get(spin), mode,
                            new BigDecimal("0.02"), 1, new BigDecimal("10000.00"), 1L,
                            remaining, totalAwarded, feature).data();
                    assertNull(HotpotClientRoundWalk.hangReason(data), HotpotClientRoundWalk.hangReason(data));
                    if (spin > 0) {
                        seenFree++;
                        int scatter = 0;
                        JsonNode last = data.path("props").get(data.path("props").size() - 1).path("prop");
                        for (JsonNode cell : last) if (cell.asInt() == 11) scatter++;
                        assertTrue(scatter < 2, "free last page scatter=" + scatter);
                    }
                    remaining = data.path("frees").path("st").asInt();
                    totalAwarded = data.path("frees").path("tt").asInt();
                    feature = data.path("frees").path("twa").decimalValue();
                }
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
            }
        }
        assertTrue(seenFree >= 8, "seenFree=" + seenFree);
    }

    @Test
    void scatterMustNotRepeatInOneApiColumn() {
        ObjectNode data = playableEnvelope();
        data.with("frees").put("st", 10).put("tt", 10);
        ObjectNode page = data.putArray("props").addObject();
        ArrayNode prop = page.putArray("prop");
        for (int i = 0; i < 36; i++) prop.add((i % 10) + 1);
        prop.set(6, JSON.getNodeFactory().numberNode(11));
        prop.set(9, JSON.getNodeFactory().numberNode(11));
        prop.set(18, JSON.getNodeFactory().numberNode(11));
        page.putArray("win_arr");
        String reason = HotpotClientRoundWalk.hangReason(data);
        assertNotNull(reason);
        assertTrue(reason.contains("Scatter in API column"), reason);
    }

    private static ObjectNode playableEnvelope() {
        ObjectNode data = JSON.createObjectNode();
        data.put("change_gold", 0.1);
        data.put("bet_gold", 0.02);
        data.put("total_win", 0.12);
        data.put("start_gold", 1000.0);
        data.put("end_gold", 1000.1);
        data.put("type", 1);
        data.putObject("frees").put("st", 0);
        return data;
    }

    private static JsonNode projectHistoryWin() throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(
                Path.of("D:/work/hd/cpgame/captures/1830-Hotpot/history-detail-ORDINARY_WIN.json")));
        List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
        for (JsonNode page : root.path("result")) {
            List<Integer> prop = new ArrayList<>();
            page.path("prop").forEach(n -> prop.add(n.asInt()));
            pages.add(new CompleteRoundFact.BoardFact(prop));
        }
        return new HotpotSpinProjector().project(pages, HotpotSpinMode.PAID,
                new BigDecimal("0.02"), 1, new BigDecimal("47270.60"), 1L, 0, 0, BigDecimal.ZERO).data();
    }
}
