package com.hd.cpgame.riocarnival.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import java.nio.file.Paths;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"rio.state-directory=target/contract-test-state","rio.publish-directory=../../../publish/45-rio-carnival"})
@AutoConfigureMockMvc
class ProviderContractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeAll static void cleanState(){FileSystemUtils.deleteRecursively(Paths.get("target/contract-test-state").toFile());}

    @Test void originalFrontendChainHistoryResumeAndIdempotency() throws Exception {
        JsonNode verify=postForm("/cp/api/v1/auth/verify","ai=local&btt=1&t=integration-launch&gid=45",null);
        assertEquals(200,verify.path("code").asInt());String token=verify.path("data").path("token").asText();assertFalse(token.isEmpty());
        JsonNode config=postForm("/cp/api/v1/rio-carnival/config","t="+token+"&gid=45",null);
        assertEquals(10,config.path("data").path("bll").size());
        assertEquals(4,config.path("data").path("bsl").size());assertTrue(config.path("data").path("last").isNull());

        JsonNode activation=null;String activationKey=null;
        for(int i=0;i<300;i++){
            activationKey="paid-"+i;JsonNode step=postForm("/cp/api/v1/rio-carnival/spin","bl=1&bs=0.02&t="+token+"&gid=45",activationKey);
            assertEquals(15,step.path("data").path("rskl").size());
            if(step.path("data").path("fsn").asInt()>step.path("data").path("nfsc").asInt()){activation=step;break;}
        }
        assertNotNull(activation,"正式随机链路应在合理次数内产生 FREE_SPINS 激活局");
        JsonNode duplicate=postForm("/cp/api/v1/rio-carnival/spin","bl=1&bs=0.02&t="+token+"&gid=45",activationKey);
        assertEquals(activation.path("data").path("rskl"),duplicate.path("data").path("rskl"));assertEquals(0,duplicate.path("data").path("nfsc").asInt());

        JsonNode resumed=postForm("/cp/api/v1/rio-carnival/config","t="+token+"&gid=45",null);
        assertFalse(resumed.path("data").path("last").isNull());assertEquals(activation.path("data").path("fsn"),resumed.path("data").path("last").path("fsn"));
        JsonNode session=getJson("/cp/api/v1/session?t="+token+"&gid=45");assertTrue(session.path("data").path("active").asBoolean());assertEquals(1,session.path("data").path("deliveryIndex").asInt());

        BigDecimal heldBalance=new BigDecimal(activation.path("data").path("pl").path("balance").asText());
        int prior=0;JsonNode step=null;
        for(int i=1;i<200;i++){
            step=postForm("/cp/api/v1/rio-carnival/spin","bl=1&bs=0.02&t="+token+"&gid=45","free-"+i);
            assertEquals(0,step.path("data").path("ba").decimalValue().signum());int now=step.path("data").path("nfsc").asInt();assertEquals(prior+1,now);prior=now;
            boolean terminal=step.path("data").path("ss").asInt()==1&&now==step.path("data").path("fsn").asInt();
            BigDecimal deliveredBalance=new BigDecimal(step.path("data").path("pl").path("balance").asText());
            if(terminal){
                assertEquals(0,deliveredBalance.compareTo(heldBalance.add(step.path("data").path("rwa").decimalValue())));
                break;
            }
            assertEquals(0,deliveredBalance.compareTo(heldBalance),"余额只能在完整局合法终点结算");
        }
        assertNotNull(step);assertEquals(1,step.path("data").path("ss").asInt());assertEquals(step.path("data").path("fsn").asInt(),step.path("data").path("nfsc").asInt());
        JsonNode ended=getJson("/cp/api/v1/session?t="+token+"&gid=45");assertFalse(ended.path("data").path("active").asBoolean());

        JsonNode list=postForm("/cp/api/v1/rio-carnival/log-list","page_index=1&begin_at=0&end_at=9999999999&t="+token+"&gid=45",null);
        assertTrue(list.path("data").path("lc").asInt()>0);JsonNode item=list.path("data").path("ll").get(0);assertTrue(item.has("ca")&&item.has("tis")&&item.has("gt")&&item.has("baf"));
        JsonNode view=postForm("/cp/api/v1/rio-carnival/log-view","transfer_id="+item.path("tis").asText()+"&t="+token+"&gid=45",null);
        assertTrue(view.path("data").path("bsl").isArray());assertTrue(view.path("data").path("fsl").isArray());assertTrue(view.path("data").path("bsl").get(0).has("small_game_type"));
        assertEquals(200,getJson("/cp/api/v1/balance?t="+token+"&gid=45").path("code").asInt());
        assertEquals("C10001",postForm("/cp/api/v1/rio-carnival/config","t=bad&gid=45",null).path("code").asText());
    }

    private JsonNode postForm(String path,String body,String key)throws Exception{
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r=post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body);
        if(key!=null)r.header("Idempotency-Key",key);return mapper.readTree(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String path)throws Exception{return mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
}
