package com.hd.cpgame.riocarnival.server.web;

import com.hd.cpgame.riocarnival.core.GameRules;
import com.hd.cpgame.riocarnival.server.service.ProtocolProjection;
import com.hd.cpgame.riocarnival.server.service.RoundService;
import com.hd.cpgame.riocarnival.server.state.GameSession;
import com.hd.cpgame.riocarnival.server.state.SessionStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins="*")
@RequestMapping("/cp/api/v1")
public final class ProviderController {
    private final SessionStore sessions; private final RoundService rounds; private final ProtocolProjection projection;
    public ProviderController(SessionStore sessions,RoundService rounds,ProtocolProjection projection){this.sessions=sessions;this.rounds=rounds;this.projection=projection;}

    @PostMapping(value="/auth/verify",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope verify(@RequestParam Map<String,String> form){
        requireGid(form.get("gid"));String launch=require(form.get("t"),"t");SessionStore.Verification v=sessions.verify(launch);
        Map<String,Object> d=new LinkedHashMap<String,Object>();Map<String,Object> player=new LinkedHashMap<String,Object>();
        player.put("balance",RoundService.format(v.session.balance));player.put("id",v.session.playerId);d.put("player",player);d.put("token",v.token);
        Map<String,Object> ping=new LinkedHashMap<String,Object>();ping.put("enable",0);ping.put("seconds",0);d.put("ping",ping);
        Map<String,Object> rc=new LinkedHashMap<String,Object>();rc.put("on",0);rc.put("v",2);d.put("rc",rc);
        Map<String,Object> gc=new LinkedHashMap<String,Object>();gc.put("os",1);gc.put("om",1);d.put("gc",gc);return Envelope.ok(d);
    }

    @PostMapping(value="/rio-carnival/config",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope config(@RequestParam Map<String,String> form){GameSession s=authorized(form);synchronized(s){return Envelope.ok(projection.config(s));}}

    @PostMapping(value="/rio-carnival/spin",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope spin(@RequestParam Map<String,String> form,
                         @RequestHeader(value="Idempotency-Key",required=false) String key,
                         @RequestHeader(value="X-Request-Id",required=false) String requestId){
        GameSession s=authorized(form);BigDecimal bs=new BigDecimal(require(form.get("bs"),"bs"));int bl=Integer.parseInt(require(form.get("bl"),"bl"));
        String effective=key!=null?key:(requestId!=null?requestId:form.get("request_id"));return Envelope.ok(rounds.spin(s,bs,bl,effective));
    }

    @PostMapping(value="/rio-carnival/log-list",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope history(@RequestParam Map<String,String> form){GameSession s=authorized(form);int page=parseInt(form.get("page_index"),1);long begin=parseLong(form.get("begin_at"),0),end=parseLong(form.get("end_at"),0);synchronized(s){return Envelope.ok(projection.historyList(s,page,begin,end));}}

    @PostMapping(value="/rio-carnival/log-view",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope historyView(@RequestParam Map<String,String> form){GameSession s=authorized(form);synchronized(s){return Envelope.ok(projection.historyView(s,require(form.get("transfer_id"),"transfer_id")));}}

    @PostMapping(value="/ping",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Envelope ping(@RequestParam Map<String,String> form){authorized(form);return Envelope.ok(new LinkedHashMap<String,Object>());}

    @GetMapping("/session")
    public Envelope session(@RequestParam("t") String token,@RequestParam(value="gid",defaultValue="45") int gid){requireGid(String.valueOf(gid));GameSession s=sessions.require(token);synchronized(s){Map<String,Object>d=new LinkedHashMap<String,Object>();d.put("playerId",s.playerId);d.put("balance",RoundService.format(s.balance));d.put("active",s.activeRound!=null);d.put("roundKey",s.activeRound==null?null:s.activeRound.roundKey);d.put("deliveryIndex",s.deliveryIndex);return Envelope.ok(d);}}

    @GetMapping("/balance")
    public Envelope balance(@RequestParam("t") String token,@RequestParam(value="gid",defaultValue="45") int gid){requireGid(String.valueOf(gid));GameSession s=sessions.require(token);Map<String,Object>d=new LinkedHashMap<String,Object>();d.put("balance",RoundService.format(s.balance));d.put("id",s.playerId);return Envelope.ok(d);}

    private GameSession authorized(Map<String,String> f){requireGid(f.get("gid"));return sessions.require(require(f.get("t"),"t"));}
    private static void requireGid(String gid){if(!String.valueOf(GameRules.GAME_ID).equals(gid))throw new SessionStore.SessionException("C10001","invalid gid");}
    private static String require(String v,String name){if(v==null||v.trim().isEmpty())throw new IllegalArgumentException("missing "+name);return v;}
    private static int parseInt(String v,int d){return v==null?d:Integer.parseInt(v);}
    private static long parseLong(String v,long d){return v==null?d:Long.parseLong(v);}
}
