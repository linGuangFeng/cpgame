package com.cpgame.clubgoddess.api.controller;

import com.cpgame.clubgoddess.api.service.GameService;
import com.cpgame.clubgoddess.api.service.GameService.SpinOutcome;
import com.cpgame.clubgoddess.api.state.SessionState;
import com.cpgame.clubgoddess.core.GameModels.GameResult;
import com.cpgame.clubgoddess.core.GameRuleCore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path="/cp", produces=MediaType.APPLICATION_JSON_VALUE)
public class GameApiController {
    private final GameService service;
    public GameApiController(GameService service) { this.service=service; }

    @PostMapping(path="/config/initialData", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> initialData(@RequestParam Map<String,String> p) {
        requireGid(p.get("gid"));
        Map<String,Object> info=GameService.map("bet_gold",List.of(0.01,0.04,0.2),"buy_free_max_bet",-1,
                "default_bet_gold",0,"default_level",10,"game_way",List.of(GameService.map("max_bet_gold","0.00","min_bet_gold","0.00","way_id",206010000,"win_multi","1.00")),
                "gid",2060,"least_gold",0,"name","Club Goddess","status","1");
        Map<String,Object> config=GameService.map("bd_bet_count",2,"current_sys_time",Instant.now().getEpochSecond(),"is_debug",false,"is_stopgs",0,"user_on_hook_time",600,"version",1745909504);
        Map<String,Object> ship=GameService.map("770","https://luckyairships.net/","780","https://luckyairships.net/","790","https://luckyairships.net/","800","https://luckyairships.net/");
        Map<String,Object> servers=GameService.map("gos_host","","gos_port","8976","gos_sport","","gs_host","","gs_host1","","gs_port","","gs_port1","",
                "gs_push_host","","gs_push_port","","gs_push_sport","28966","gs_sport","","gs_sport1","","ngs_switch",0,"ps_host","","ps_port","",
                "snake_gs_host","","snake_gs_port","","snake_gs_sport","","snake_gs_url","");
        return ok(GameService.map("game_address",GameService.map("ship_address_config",ship),"game_info",info,"game_server",servers,"initial_config",config,
                "language",resolveLanguage(p.get("language")),"r",1,"zone",0));
    }

    @PostMapping(path="/account/getUserInfo", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> getUserInfo(@RequestParam Map<String,String> p) {
        requireGid(p.get("gid")); SessionState s=service.bootstrap(p.get("token"));
        return ok(GameService.map("currency_symbol",s.currencySymbol,"day_first_login",0,"first_gold",null,"gid",2060,"gold",s.balance,
                "is_guide",0,"nickname",s.nickname,"token",s.token,"total_recharge","0","uid",Math.abs(s.token.hashCode()),"user_config",""));
    }

    @PostMapping(path="/single_game.Game/initRoom", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> initRoom(@RequestParam Map<String,String> p) { requireGid(p.get("gid")); return ok(service.snapshot(p.get("token"))); }

    @PostMapping(path="/single_game.Game/gameResult", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> spin(@RequestParam Map<String,String> p, @RequestHeader(value="X-Idempotency-Key",required=false) String headerKey) {
        requireGid(p.get("gid"));
        String key=headerKey!=null?headerKey:p.getOrDefault("request_id",p.get("signapt"));
        SpinOutcome outcome=service.spin(p.get("token"),new BigDecimal(p.get("bet_gold")),Integer.parseInt(p.get("level")),p.getOrDefault("act_id","0"),key,p.get("scenario"));
        return ok(outcome.result());
    }

    @PostMapping(path="/goldgame/single_game_user_gold_history", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> aggregate(@RequestParam Map<String,String> p) { requireGid(p.get("gid")); return ok(service.aggregateHistory(p.get("token"))); }

    @PostMapping(path="/goldgame/single_game_user_history", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> detail(@RequestParam Map<String,String> p) { requireGid(p.get("gid")); return ok(service.detailHistory(p.get("token"),Long.parseLong(p.get("day")),Integer.parseInt(p.getOrDefault("page","1")),Integer.parseInt(p.getOrDefault("page_size","30")))); }

    @PostMapping(path="/account/getBalance", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> balance(@RequestParam Map<String,String> p) { SessionState s=service.session(p.get("token")); return ok(GameService.map("gold",s.balance,"currency_symbol",s.currencySymbol)); }

    @PostMapping(path="/account/session", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> session(@RequestParam Map<String,String> p) { SessionState s=service.session(p.get("token")); return ok(GameService.map("gid",2060,"active",true,"gold",s.balance,"roundKey",s.lastRoundKey,"deliveryIndex",s.deliveryIndex,"rulesHash",GameRuleCore.RULES_HASH)); }

    @PostMapping(path="/activity/getActivity", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,Object> activity(@RequestParam Map<String,String> p) { return ok(GameService.map("free",GameService.map("act_list",List.of(),"invite_act_have",0,"invite_end_time",0))); }

    private void requireGid(String gid) { if(!"2060".equals(gid)) throw new IllegalArgumentException("gid must be 2060"); }
    private String resolveLanguage(String language) { return List.of("bn-bd","en-us","es-es","fr-fr","id-id","ko-ko","pt-pt","th-th","tr-tr","hi-in","zh-cn","zh-hk","vi-vn","in-telugu","in-marathi","pt-br").contains(language)?language:"en-us"; }
    private Map<String,Object> ok(Object data) { return GameService.map("code",0,"data",data,"msg","success","time",Long.toString(Instant.now().getEpochSecond())); }
}
