package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.api.ProtocolCodec;
import com.cpgame.coinmastergo.model.*;
import com.cpgame.coinmastergo.service.GameSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigResumeProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    // Reproduced stuck demo board: H2 wins at [2], [10], [21]. The original
    // Config consumer explicitly reads each last.wmkl entry's .wmk field.
    private PlayerSession pausedSession() {
        PlayerSession s = new PlayerSession();
        s.balance = new BigDecimal("1027.60");
        s.lastStep = new SpinStep();
        s.lastStep.rskl = List.of("H5","H7","H1","H2","H7","H1","H2","H4","H6","H6","H2","H6","H2","H3","H4","H8","H3","H3","H5","H3","H2","H6","H1","H2","H8");
        s.lastStep.wmkl = List.of(List.of(List.of(2), List.of(10), List.of(21)));
        s.lastStep.wskl = List.of("H2");
        s.lastStep.rpx = 1;
        s.lastStep.gt = 1;
        s.lastStep.pb = "1027.60";
        s.lastStep.wa = new BigDecimal("0.20");
        s.activeRound = new RoundPlan();
        s.activeRound.betLevel = 1;
        s.activeRound.betSize = new BigDecimal("0.02");
        s.activeRound.deliveryIndex = 2;
        s.activeRound.stepIndex = 1;
        return s;
    }

    private JsonNode config(PlayerSession s) {
        GameSessionService service = mock(GameSessionService.class);
        when(service.lastOrNeutral(s)).thenReturn(s.lastStep);
        return mapper.valueToTree(new ProtocolCodec(service).config(s));
    }

    @Test void configRestoresWinObjectsAndBetWithoutMutatingSpinOrAdvancingRound() throws Exception {
        PlayerSession s = pausedSession();
        String before = mapper.writeValueAsString(s);
        JsonNode last = config(s).path("last");
        assertThat(last.path("bl").asInt()).isEqualTo(1);
        assertThat(last.path("bs").decimalValue()).isEqualByComparingTo("0.02");
        assertThat(last.at("/wmkl/0/wmk")).isEqualTo(mapper.readTree("[[2],[10],[21]]"));
        assertThat(last.at("/wmkl/0/sk").asText()).isEqualTo("H2");
        assertThat(last.at("/wmkl/0/wa").asText()).isEqualTo("0.20");
        assertThat(last.path("ss").asInt()).isZero();
        assertThat(mapper.writeValueAsString(s)).isEqualTo(before);
        assertThat(mapper.valueToTree(s.lastStep).at("/wmkl/0").isArray()).isTrue();
    }

    @Test void freeResumeAndCompletedRoundRestoreActualNonDefaultBet() {
        PlayerSession s = pausedSession();
        s.activeRound.betLevel = 7;
        s.activeRound.betSize = new BigDecimal("0.1");
        s.lastStep.gt = 2;
        s.lastStep.ba = BigDecimal.ZERO;
        s.lastStep.fsn = 12;
        s.lastStep.nfsc = 2;
        s.lastStep.rpx = 6;
        JsonNode last = config(s).path("last");
        assertThat(last.path("bl").asInt()).isEqualTo(7);
        assertThat(last.path("bs").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(last.path("fsn").asInt()).isEqualTo(12);
        assertThat(last.path("nfsc").asInt()).isEqualTo(2);
        assertThat(last.path("rpx").asInt()).isEqualTo(6);
        s.history.add(new HistoryRecord(s.activeRound, 1));
        s.activeRound = null;
        assertThat(config(s).path("last")).isEqualTo(last);
    }
}
