package com.cpgame.wukong.server;

import com.cpgame.wukong.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** 将一个已校验 Redis 完整局投影为原厂 gameResult 字段；不判第二套规则。 */
final class WuKongProjector {
    private static final ObjectMapper JSON=new ObjectMapper();private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal MIN_BET=BigDecimal.ONE;
    private final GameRuleCore rules=new GameRuleCore();private final ResultUtil results=new ResultUtil(rules);
    ObjectNode project(CompleteRound round,BigDecimal bet,BigDecimal start,long oid){ResultUtil.Evaluation e=results.evaluate(round);BigDecimal total=money(bet.multiply(BigDecimal.valueOf(e.totalMultiplier()))),change=money(total.subtract(bet)),end=money(start.add(change));boolean third=thirdColumnUnlocked(bet);ObjectNode d=JSON.createObjectNode();d.put("begin_time",TIME.format(LocalDateTime.now()));put(d,"bet_gold",bet);d.put("bet_number","");d.put("bet_rate",1);put(d,"change_gold",change);ObjectNode chess=d.putObject("chess");chess.put("bonus",0);chess.put("extend",round.mode().wire());pair(chess.putArray("normal"),round.initial(),third);if(round.respin()==null)chess.put("respin","null");else pair(chess.putArray("respin"),round.respin(),third);ArrayNode result=chess.putArray("result");for(int x:e.resultMultipliers())put(result,money(bet.multiply(BigDecimal.valueOf(x))));put(chess,"win",money(bet.multiply(BigDecimal.valueOf(e.chessWinMultiplier()))));put(d,"end_gold",end);d.put("level",1);d.put("oid",oid);put(d,"profit_gold",change);d.put("round_id",oid);put(d,"start_gold",start);put(d,"total_win",total);put(d,"win_gold",total);return d;}
    ObjectNode idle(BigDecimal balance){return project(new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("null","null"),null),BigDecimal.ZERO,balance,0L);}
    /** 原前端 4 列：0/1 为两位倍率，2 仅在非最小押注解锁，3 为 extend。未给第 3 位时 GetResultIndex(undefined) 抛错，后列一直转。 */
    private static boolean thirdColumnUnlocked(BigDecimal bet){return bet.compareTo(MIN_BET)>0;}
    private static void pair(ArrayNode n,CompleteRound.ReelPair p,boolean thirdColumn){n.add(p.left()).add(p.right());if(thirdColumn)n.add("null");}
    private static BigDecimal money(BigDecimal v){return v.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros();}private static void put(ObjectNode n,String f,BigDecimal v){if(v.scale()<=0)n.put(f,v.longValueExact());else n.put(f,v.doubleValue());}private static void put(ArrayNode n,BigDecimal v){if(v.scale()<=0)n.add(v.longValueExact());else n.add(v.doubleValue());}
}
