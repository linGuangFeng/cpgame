package com.cpgame.curupira;

import com.cpgame.curupira.api.RoundSource;
import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiIntegrationTest.TestRoundConfig.class)
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void originalRequestFamilySupportsSessionSpinIdempotencyRecoveryBalanceAndHistory() throws Exception {
        String token = "local-" + UUID.randomUUID();

        JsonNode config = json(mvc.perform(post("/cp/config/initialData")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .param("currency", "undefined").param("gid", "2350")
                .param("language", "pt-br").param("ai", "luck_single_10229"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(config.at("/data/language").asText()).isEqualTo("en-us");
        assertThat(config.at("/data/game_info/name").asText()).isEqualTo("Curupira");

        JsonNode userBefore = userInfo(token);
        assertThat(userBefore.at("/data/gold").decimalValue()).isEqualByComparingTo("1000.00");

        JsonNode initialRoom = initRoom(token);
        assertThat(initialRoom.at("/data/res/ps")).hasSize(15);
        assertThat(initialRoom.at("/data/bg").decimalValue()).isZero();

        JsonNode first = spin(token, "one-request");
        JsonNode repeated = spin(token, "one-request");
        assertThat(first.at("/data/rid").longValue()).isEqualTo(repeated.at("/data/rid").longValue());
        assertThat(first.at("/data/rid").longValue()).isGreaterThan(9_007_199_254_740_991L);
        assertThat(first.at("/data/res/ps")).hasSize(15);
        assertThat(first.at("/data/f")).isEmpty();
        assertThat(first.at("/data/gt").asInt()).isEqualTo(1);
        assertThat(first.at("/data/small_game_type").asInt()).isZero();

        JsonNode replay = initRoom(token);
        assertThat(replay.at("/data/rid").longValue()).isEqualTo(first.at("/data/rid").longValue());

        JsonNode userAfter = userInfo(token);
        assertThat(userAfter.at("/data/gold").decimalValue())
                .isEqualByComparingTo(first.at("/data/eg").decimalValue());

        JsonNode history = json(mvc.perform(post("/cp/Goldgame/user_game_history")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .param("token", token).param("gid", "2350").param("language", "en-us")
                .param("start", "0").param("end", "4102444800")
                .param("page", "1").param("page_size", "30").param("zone_time", "-480"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history.at("/data/totals/total").asInt()).isEqualTo(1);
        assertThat(history.at("/data/list/0/results").size()).isGreaterThanOrEqualTo(1);
        assertThat(history.at("/data/list/0/order_id").asText())
                .contains("-2350");
    }

    @Test
    void ordinaryPaidSpinsNeverInventUnknownSpecialModesAndBuyIsRejected() throws Exception {
        String token = "local-" + UUID.randomUUID();
        userInfo(token);
        for (int i = 0; i < 20; i++) {
            JsonNode spin = spin(token, "paid-" + i);
            assertThat(spin.path("code").asInt()).isEqualTo(0);
            assertThat(spin.at("/data/small_game_type").asInt()).isZero();
            assertThat(spin.at("/data/f")).isEmpty();
            assertThat(spin.at("/data/res/sc").asInt()).isLessThan(3);
        }
        JsonNode buy = json(mvc.perform(post("/cp/single_game.Game/gameResult")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .header("Idempotency-Key", "buy-hs")
                .param("token", token).param("gid", "2350").param("language", "en-us")
                .param("bet", "0.02").param("level", "1").param("type", "3").param("game_type", "3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(buy.path("code").asInt()).isEqualTo(4007);
    }

    @Test
    void unresolvedSpecialRequestsAreRejectedWithoutGeneratingARound() throws Exception {
        String token = "local-" + UUID.randomUUID();
        userInfo(token);
        JsonNode rejected = json(mvc.perform(post("/cp/single_game.Game/gameResult")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .header("Idempotency-Key", "special")
                .param("token", token).param("gid", "2350").param("language", "en-us")
                .param("bet", "0.02").param("level", "1").param("type", "2").param("game_type", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rejected.path("code").asInt()).isEqualTo(4007);
        assertThat(userInfo(token).at("/data/gold").decimalValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void healthPublishesAcceptedRulesIdentityAndAllInterfaceBinding() throws Exception {
        JsonNode health = json(mvc.perform(get("/health")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(health.path("bindAddress").asText()).isEqualTo("0.0.0.0");
        assertThat(health.path("unsupportedUnknownBehaviors").toString())
                .isEqualTo("[\"B007_FEATURE_BUY\"]");
    }

    private JsonNode spin(String token, String key) throws Exception {
        return json(mvc.perform(post("/cp/single_game.Game/gameResult")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .header("Idempotency-Key", key)
                .param("token", token).param("gid", "2350").param("language", "en-us")
                .param("bet", "0.02").param("level", "1").param("type", "1").param("game_type", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode initRoom(String token) throws Exception {
        return json(mvc.perform(post("/cp/single_game.Game/initRoom")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .param("token", token).param("gid", "2350").param("language", "en-us"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode userInfo(String token) throws Exception {
        return json(mvc.perform(post("/cp/account/getUserInfo")
                .contentType("application/x-www-form-urlencoded;charset=utf-8")
                .param("token", token).param("gid", "2350").param("language", "en-us")
                .param("ai", "local"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRoundConfig {
        @Bean
        RoundSource testRoundSource() {
            CompleteRoundFact loss = new MinimalFactCodec().decode("CU1PL;S111222333444AAA");
            return new RoundSource() {
                @Override public CompleteRoundFact peekLoss() { return loss; }
                @Override public CompleteRoundFact claimPaidAtOrBelow(int targetMultiplier) { return loss; }
                @Override public CompleteRoundFact claimMaryAtOrBelow(CompleteRoundFact.Kind kind,
                                                                       int targetMultiplier) {
                    throw new AssertionError("Mary must follow a cached trigger");
                }
            };
        }
    }
}
