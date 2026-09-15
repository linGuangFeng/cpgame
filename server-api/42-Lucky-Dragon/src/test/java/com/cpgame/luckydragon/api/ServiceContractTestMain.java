package com.cpgame.luckydragon.api;

import com.cpgame.luckydragon.core.GameRuleCore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import com.cpgame.luckydragon.core.RoundFacts;
import com.cpgame.luckydragon.core.ResultUtil;

public final class ServiceContractTestMain {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var state = Files.createTempDirectory("gid42-controller-test-");
        var facts = new RoundFacts(new BigDecimal("0.5"), 1, List.of("H0", "H2", "H3"), 0, "42-test-member");
        var result = ResultUtil.analyze(facts);
        int[] claims = {0};
        LuckyDragonService service = new LuckyDragonService(state, new BigDecimal("1000"), request -> {
            claims[0]++;
            return new LuckyDragonService.ClaimedRound(facts.roundKey(), result, "v2|test-member");
        });
        Map<String,Object> auth = service.auth(Map.of("gid", "42"), "browser-launch");
        String token = (String) ((Map<String,Object>) auth.get("data")).get("token");
        Map<String,String> session = Map.of("gid", "42", "t", token);
        Map<String,Object> config = service.config(session);
        Map<String,Object> data = (Map<String,Object>) config.get("data");
        if (!GameRuleCore.SYMBOL_PAY_MULTIPLIERS.equals(data.get("spl"))) throw new AssertionError("config paytable mismatch");
        Map<String,String> spinForm = Map.of("gid", "42", "t", token, "bs", "0.5", "bl", "1");
        Map<String,Object> first = service.spin(spinForm, "same-request");
        Map<String,Object> replay = service.spin(spinForm, "same-request");
        if (!first.equals(replay)) throw new AssertionError("idempotent replay changed complete round");
        if (claims[0] != 1) throw new AssertionError("paid Round claimed Redis member more than once");
        Map<String,Object> history = service.historyList(Map.of("gid", "42", "t", token, "page_index", "1"));
        Map<String,Object> historyData = (Map<String,Object>) history.get("data");
        if (!Integer.valueOf(1).equals(historyData.get("lc"))) throw new AssertionError("history not 1:1 with paid start");
        Map<String,Object> row = (Map<String,Object>) ((java.util.List<?>) historyData.get("ll")).get(0);
        Map<String,Object> detail = service.historyDetail(Map.of("gid", "42", "t", token,
            "transfer_id", row.get("tis").toString()));
        Map<String,Object> detailData = (Map<String,Object>) detail.get("data");
        if (!Boolean.TRUE.equals(detailData.get("terminal")) || !Integer.valueOf(0).equals(detailData.get("deliveryIndex"))) {
            throw new AssertionError("complete Round delivery state mismatch");
        }
        Map<String,Object> balanceBeforeRestart = service.balance(session);
        LuckyDragonService restarted = new LuckyDragonService(state, new BigDecimal("1000"), request -> {
            claims[0]++;
            return new LuckyDragonService.ClaimedRound(facts.roundKey(), result, "v2|unexpected-second-claim");
        });
        Map<String,Object> resumedAuth = restarted.auth(Map.of("gid", "42"), "browser-launch");
        String resumedToken = (String) ((Map<String,Object>) resumedAuth.get("data")).get("token");
        Map<String,String> resumedSession = Map.of("gid", "42", "t", resumedToken);
        Map<String,Object> resumedHistory = restarted.historyList(Map.of(
            "gid", "42", "t", resumedToken, "page_index", "1"));
        if (!Integer.valueOf(1).equals(((Map<String,Object>) resumedHistory.get("data")).get("lc"))) {
            throw new AssertionError("cross-process History recovery failed");
        }
        Map<String,Object> resumedReplay = restarted.spin(Map.of(
            "gid", "42", "t", resumedToken, "bs", "0.5", "bl", "1"), "same-request");
        if (!first.equals(resumedReplay) || claims[0] != 1) {
            throw new AssertionError("cross-process idempotent replay claimed or changed the complete Round");
        }
        if (!balanceBeforeRestart.equals(restarted.balance(resumedSession))) {
            throw new AssertionError("cross-process balance recovery failed");
        }
        boolean refused = false;
        try { ServerMain.main(new String[]{state.resolve("server.properties").toString()}); }
        catch (IllegalStateException expected) { refused = expected.getMessage().contains("platform-managed"); }
        if (!refused) throw new AssertionError("standalone listener guard missing");
        String property = "com.sun.net.httpserver.HttpServerProvider";
        String previous = System.getProperty(property);
        try {
            for (String provider : List.of("com.cpgame.admin.CpgameSharedHttpServerProvider",
                    "com.lgf.agentai.service.CpgameSharedHttpServerProvider")) {
                System.setProperty(property, provider);
                boolean accepted = false;
                try { ServerMain.main(new String[0]); }
                catch (IllegalArgumentException expected) { accepted = expected.getMessage().contains("--config"); }
                if (!accepted) throw new AssertionError("managed provider rejected: " + provider);
            }
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        System.out.println("ServiceContractTestMain PASS same-process + cross-process resume/idempotency/history/balance");
    }
}
