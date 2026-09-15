package com.cpgame.fiesta;

public final class RedisKeys {
    private RedisKeys(){}

    public static long id(String gameId){return Long.parseLong(gameId);}
    public static String normalIndex(String gameId){return String.format("PerKeyList_%09d", id(gameId));}
    public static String maryIndex(String gameId){return String.format("MaryKeyList_%09d", id(gameId));}
    public static String normalList(String gameId,int multiplier){return String.format("BetLog:0%08d:%06d", id(gameId), multiplier);}
    public static String maryList(String gameId,int multiplier){return String.format("MaryLog:%09d:%06d", id(gameId), multiplier);}
    public static boolean special(RoundOutcome outcome){
        return outcome==RoundOutcome.RESPIN_UNTIL_WIN || outcome==RoundOutcome.MULTIPLIER_STICKY;
    }
    public static String index(String gameId, RoundOutcome outcome){
        return special(outcome)?maryIndex(gameId):normalIndex(gameId);
    }
    public static String list(String gameId, RoundOutcome outcome, int multiplier){
        return special(outcome)?maryList(gameId, multiplier):normalList(gameId, multiplier);
    }
    /** @deprecated kept so old call sites fail closed if game id is wrong */
    public static String prefix(String gameId){
        if(gameId==null||gameId.isBlank())throw new IllegalArgumentException("game id required");
        return "PerKeyList_%09d".formatted(id(gameId));
    }
}
