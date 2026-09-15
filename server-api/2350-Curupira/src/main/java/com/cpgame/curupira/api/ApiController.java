package com.cpgame.curupira.api;

import com.cpgame.curupira.codec.SignaptCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.RulesContract;
import com.cpgame.curupira.session.SessionState;
import com.cpgame.curupira.session.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public final class ApiController {
    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
    private final SessionStore sessions;
    private final SignaptCodec signapt;
    private final RoundSource rounds;
    private final GameRuleCore core;
    private final boolean signatureRequired;

    public ApiController(SessionStore sessions, SignaptCodec signapt, RoundSource rounds, GameRuleCore core,
                         @Value("${curupira.signature.required}") boolean signatureRequired) {
        this.sessions = sessions;
        this.signapt = signapt;
        this.rounds = rounds;
        this.core = core;
        this.signatureRequired = signatureRequired;
    }

    @RequestMapping(path = {"/cp/activity/getActivity", "/activity/getActivity"},
            method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getActivity() {
        return ResponseFactory.success(Map.of("free", Map.of("act_list", java.util.List.of(),
                "invite_act_have", 0, "invite_end_time", 0)));
    }

    @RequestMapping(path = "/cp/config/initialData", method = RequestMethod.POST,
            consumes = FORM, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> initialData(@RequestParam Map<String, String> form) {
        validateCommon(form);
        return ResponseFactory.success(ResponseFactory.initialData(form.get("language")));
    }

    @RequestMapping(path = "/cp/account/getUserInfo", method = RequestMethod.POST,
            consumes = FORM, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getUserInfo(@RequestParam Map<String, String> form) {
        validateCommon(form);
        return ResponseFactory.success(ResponseFactory.userInfo(sessions.require(form.get("token"))));
    }

    @RequestMapping(path = "/cp/single_game.Game/initRoom", method = RequestMethod.POST,
            consumes = FORM, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> initRoom(@RequestParam Map<String, String> form) {
        validateCommon(form);
        SessionState session = sessions.require(form.get("token"));
        return ResponseFactory.success(session.roomProjection(rounds));
    }

    @RequestMapping(path = "/cp/single_game.Game/gameResult", method = RequestMethod.POST,
            consumes = FORM, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> gameResult(
            @RequestParam Map<String, String> form,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey) {
        validateCommon(form);
        int type = integer(form, "type");
        int gameType = integer(form, "game_type");
        SessionState session = sessions.require(form.get("token"));
        String idempotencyKey = idempotencyKey(form, headerIdempotencyKey);
        return ResponseFactory.success(session.play(type, gameType, decimal(form, "bet"),
                integer(form, "level"), idempotencyKey, rounds, core));
    }

    @RequestMapping(path = "/cp/Goldgame/user_game_history", method = RequestMethod.POST,
            consumes = FORM, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> history(@RequestParam Map<String, String> form) {
        validateCommon(form);
        SessionState session = sessions.require(form.get("token"));
        long start = longValue(form, "start", 0);
        long end = longValue(form, "end", Instant.now().plusSeconds(86_400).getEpochSecond());
        int page = Math.max(1, integer(form, "page", 1));
        int pageSize = Math.max(1, Math.min(100, integer(form, "page_size", 30)));
        return ResponseFactory.success(ResponseFactory.history(session.historyRounds(), start, end, page, pageSize));
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("gameId", RulesContract.GAME_ID);
        data.put("gameName", RulesContract.GAME_NAME);
        data.put("rulesVersion", RulesContract.RULES_VERSION);
        data.put("rulesHash", RulesContract.RULES_HASH);
        data.put("bindAddress", "0.0.0.0");
        data.put("unsupportedUnknownBehaviors", RulesContract.UNSUPPORTED_UNKNOWN_BEHAVIORS);
        return data;
    }

    private void validateCommon(Map<String, String> form) {
        String gid = form.get("gid");
        if (gid != null && !Integer.toString(RulesContract.GAME_ID).equals(gid)) {
            throw new IllegalArgumentException("gid must be 2350");
        }
        boolean supplied = form.containsKey("signapt") || form.containsKey("expire");
        if ((signatureRequired || supplied) && !signapt.matches(form)) {
            throw new InvalidSignatureException();
        }
    }

    private static String idempotencyKey(Map<String, String> form, String header) {
        if (header != null && !header.isBlank()) {
            return "header:" + header;
        }
        String signature = form.get("signapt");
        String expire = form.get("expire");
        if (signature != null && expire != null) {
            return "wire:" + expire + ":" + signature;
        }
        throw new IllegalArgumentException("gameResult requires signapt/expire or Idempotency-Key");
    }

    private static int integer(Map<String, String> form, String field) {
        String value = form.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return Integer.parseInt(value);
    }

    private static int integer(Map<String, String> form, String field, int defaultValue) {
        return form.containsKey(field) ? Integer.parseInt(form.get(field)) : defaultValue;
    }

    private static long longValue(Map<String, String> form, String field, long defaultValue) {
        return form.containsKey(field) ? Long.parseLong(form.get(field)) : defaultValue;
    }

    private static BigDecimal decimal(Map<String, String> form, String field) {
        String value = form.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return new BigDecimal(value);
    }
}
