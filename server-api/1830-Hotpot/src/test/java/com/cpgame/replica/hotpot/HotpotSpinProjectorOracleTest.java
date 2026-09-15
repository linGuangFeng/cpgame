package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HotpotSpinProjectorOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WIN_ORACLE = Path.of("D:/work/hd/cpgame/captures/1830-Hotpot/history-detail-ORDINARY_WIN.json");

    @Test
    void ordinaryWinMatchesCapturedHistoryDetail() throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(WIN_ORACLE));
        List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
        for (JsonNode page : root.path("result")) {
            List<Integer> prop = new ArrayList<>();
            page.path("prop").forEach(n -> prop.add(n.asInt()));
            pages.add(new CompleteRoundFact.BoardFact(prop));
        }
        HotpotSpinProjector projector = new HotpotSpinProjector();
        HotpotSpinProjector.ProjectedSpin spin = projector.project(pages, HotpotSpinMode.PAID,
                new BigDecimal("0.02"), 1, new BigDecimal("47270.60"), 1L, 0, 0, BigDecimal.ZERO);
        JsonNode data = spin.data();
        JsonNode page0 = root.path("result").get(0);
        JsonNode out0 = data.path("props").get(0);
        assertEquals(page0.path("win_arr").get(0).path("p").asInt(), out0.path("win_arr").get(0).path("p").asInt());
        assertEquals(page0.path("win_arr").get(0).path("n").asInt(), out0.path("win_arr").get(0).path("n").asInt());
        assertEquals(page0.path("win_arr").get(0).path("odd").asInt(), out0.path("win_arr").get(0).path("odd").asInt());
        assertEquals(0.16d, out0.path("win_arr").get(0).path("wm").asDouble(), 0.0001);
        JsonNode lastOracle = root.path("result").get(root.path("result").size() - 1);
        JsonNode lastOut = data.path("props").get(data.path("props").size() - 1);
        assertEquals(8, lastOracle.path("mult").path("4").asInt()
                + lastOracle.path("mult").path("9").asInt()
                + lastOracle.path("mult").path("29").asInt());
        assertEquals(2, lastOut.path("mult").path("4").asInt());
        assertEquals(3, lastOut.path("mult").path("9").asInt());
        assertEquals(3, lastOut.path("mult").path("29").asInt());
        assertEquals(27.52d, lastOut.path("tw").asDouble(), 0.0001);
        assertEquals(27.52d, data.path("total_win").asDouble(), 0.0001);
        assertEquals(27.12d, data.path("change_gold").asDouble(), 0.0001);
        assertEquals(0.4d, data.path("bet_gold").asDouble(), 0.0001);
        assertEquals(68.8d, data.path("odds").asDouble(), 0.0001);
        assertEquals(0, data.path("frees").path("st").asInt());
        assertEquals(1376, spin.integerMultiplier());
    }

    @Test
    void idleSnapshotIsOrdinaryLossAndNotADeal() {
        HotpotSpinProjector projector = new HotpotSpinProjector();
        int[] prop = projector.idleProp();
        assertEquals(36, prop.length);
        assertArrayEquals(HotpotSpinProjector.IDLE_FROM_CAPTURED_INITROOM, prop);
        assertNotEquals(1, prop[0]);
        assertEquals(0, projector.core().awardedFreeSpins(
                projector.core().evaluate(new com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard(prop)).getScatterCount(),
                HotpotSpinMode.PAID));
    }
}
