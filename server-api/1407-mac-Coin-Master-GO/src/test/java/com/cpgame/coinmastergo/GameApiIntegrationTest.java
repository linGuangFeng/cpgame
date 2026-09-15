package com.cpgame.coinmastergo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpgame.coinmastergo.service.StateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

@SpringBootTest(properties = {
        "coin-master.state-file=target/test-state-${random.uuid}.json",
        "coin-master.demo-seed=1407"
})
@AutoConfigureMockMvc
class GameApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired StateStore store;

    @Test
    void fullProtocolFlowUsesFreshGeneratedRoundsAndSupportsResumeIdempotencyAndHistory() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("<html"));
        JsonNode auth = json(mvc.perform(post("/cp/api/v1/auth/verify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("ai", "luck_single_10229").param("btt", "1")
                        .param("t", "integration-launch-token").param("gid", "55"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn());
        String token = auth.at("/data/token").asText();

        mvc.perform(form("/cp/api/v1/go-master/config", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bll.length()").value(10))
                .andExpect(jsonPath("$.data.bsl.length()").value(3))
                .andExpect(jsonPath("$.data.last.rskl.length()").value(25))
                .andExpect(jsonPath("$.data.last.gfl").isArray())
                .andExpect(jsonPath("$.data.last.sfl").doesNotExist());

        MvcResult first = mvc.perform(spin(token, "generated-first"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ba").value(0.4))
                .andExpect(jsonPath("$.data.rskl.length()").value(25))
                .andExpect(jsonPath("$.data.gfl").isArray())
                .andExpect(jsonPath("$.data.sfl").doesNotExist())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();
        assertThat(mvc.perform(spin(token, "generated-first")).andReturn()
                .getResponse().getContentAsString()).isEqualTo(firstBody);

        java.util.Set<String> paidBoards = new java.util.HashSet<>();
        java.util.Set<String> scenarios = new java.util.HashSet<>();
        paidBoards.add(mapper.readTree(firstBody).at("/data/rskl").toString());
        int call = 0;
        while (store.read(state -> state.sessionsByToken.get(token).history.size()) < 100) {
            JsonNode data = json(mvc.perform(spin(token, "generated-" + (++call)))
                    .andExpect(jsonPath("$.code").value(200)).andReturn()).path("data");
            if (data.path("ba").decimalValue().signum() > 0) paidBoards.add(data.path("rskl").toString());
            if (call > 2_000) throw new AssertionError("generated Round did not terminate");
        }
        store.read(state -> {
            state.sessionsByToken.get(token).history.forEach(record -> scenarios.add(
                    record.round.scenario.startsWith("FREE") ? "FREE" :
                        (record.round.totalWin.signum() > 0 ? "WIN" : "LOSS")));
            return null;
        });
        assertThat(paidBoards).hasSizeGreaterThan(90);
        assertThat(scenarios).contains("LOSS", "WIN");

        JsonNode list = json(mvc.perform(post("/cp/api/v1/go-master/log-list")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("t", token).param("gid", "55").param("page_index", "1")
                        .param("begin_at", "0").param("end_at", "0"))
                .andExpect(jsonPath("$.data.lc").value(100)).andReturn());
        String transferId = list.at("/data/ll/0/tis").asText();
        mvc.perform(post("/cp/api/v1/go-master/log-view")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("t", token).param("gid", "55").param("transfer_id", transferId))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bsl.length()").isNumber())
                .andExpect(jsonPath("$.data.bsl[0].gfl").isArray())
                .andExpect(jsonPath("$.data.bsl[0].sfl").doesNotExist());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder form(String path, String token) {
        return post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("t", token).param("gid", "55");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder spin(String token, String key) {
        return form("/cp/api/v1/go-master/spin", token).param("bl", "1").param("bs", "0.02")
                .header("Idempotency-Key", key);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
