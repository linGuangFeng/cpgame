package com.cpgame.coinmastergo.api;

import com.cpgame.coinmastergo.model.PlayerSession;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.service.GameSessionService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/cp/api/v1")
public class GameController {
    private final GameSessionService sessions;
    private final ProtocolCodec codec;

    public GameController(GameSessionService sessions, ProtocolCodec codec) {
        this.sessions = sessions;
        this.codec = codec;
    }

    @PostMapping({"/auth/verify", "/auth/session"})
    public ApiEnvelope<Map<String, Object>> verify(
            @RequestParam("t") String launchToken,
            @RequestParam("gid") String gid,
            @RequestParam(value = "ai", required = false) String ai,
            @RequestParam(value = "btt", required = false) String btt) {
        return ApiEnvelope.ok(codec.auth(sessions.verify(launchToken, gid)));
    }

    @PostMapping("/go-master/config")
    public ApiEnvelope<Map<String, Object>> config(@RequestParam("t") String token,
                                                   @RequestParam("gid") String gid) {
        return ApiEnvelope.ok(codec.config(sessions.session(token, gid)));
    }

    @PostMapping("/go-master/spin")
    public ApiEnvelope<SpinStep> spin(@RequestParam("t") String token,
                                      @RequestParam("gid") String gid,
                                      @RequestParam("bl") int betLevel,
                                      @RequestParam("bs") BigDecimal betSize,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiEnvelope.ok(sessions.spin(token, gid, betLevel, betSize, idempotencyKey));
    }

    @PostMapping("/go-master/log-list")
    public ApiEnvelope<Map<String, Object>> history(@RequestParam("t") String token,
                                                    @RequestParam("gid") String gid,
                                                    @RequestParam(value = "page_index", defaultValue = "1") int pageIndex,
                                                    @RequestParam(value = "begin_at", defaultValue = "0") long beginAt,
                                                    @RequestParam(value = "end_at", defaultValue = "0") long endAt) {
        PlayerSession session = sessions.session(token, gid);
        return ApiEnvelope.ok(codec.historyList(session, pageIndex, beginAt, endAt));
    }

    @PostMapping("/go-master/log-view")
    public ApiEnvelope<Map<String, Object>> historyDetail(@RequestParam("t") String token,
                                                          @RequestParam("gid") String gid,
                                                          @RequestParam("transfer_id") String transferId) {
        return ApiEnvelope.ok(codec.historyDetail(sessions.session(token, gid), transferId));
    }

    @PostMapping("/ping")
    public ApiEnvelope<Map<String, Object>> ping(@RequestParam("t") String token,
                                                 @RequestParam("gid") String gid) {
        sessions.session(token, gid);
        return ApiEnvelope.ok(Map.of());
    }
}
