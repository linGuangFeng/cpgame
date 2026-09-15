package com.cpgame.sharpshooter.core;
public final class RedisKeys {
 private RedisKeys(){}
 public static long id(String game){
  long id = Long.parseLong(game);
        if (id < 1 || id > 99_999_999) throw new IllegalArgumentException("redis.game-id must be 1..99999999");
        return id;
 }
 public static String normalIndex(String game){return String.format("PerKeyList_%09d", id(game));}
 public static String maryIndex(String game){return String.format("MaryKeyList_%09d", id(game));}
 public static String normalList(String game,int multiplier){return String.format("BetLog:0%08d:%06d", id(game), multiplier);}
 public static String maryList(String game,int multiplier){return String.format("MaryLog:%09d:%06d", id(game), multiplier);}
 public static boolean special(ResultUtil.Outcome outcome){return outcome==ResultUtil.Outcome.FREE_SPINS;}
 public static String index(String game, ResultUtil.Outcome outcome){return special(outcome)?maryIndex(game):normalIndex(game);}
 public static String list(String game, ResultUtil.Outcome outcome, int multiplier){return special(outcome)?maryList(game,multiplier):normalList(game,multiplier);}
 public static String prefix(String game){return normalIndex(game);}
}
