package com.cpgame.wukong.redis;

public final class RedisKeys {
    private RedisKeys(){}
    public static String normalIndex(int gid){return "PerKeyList_"+String.format("%09d",gid);}
    public static String specialIndex(int gid){return "MaryKeyList_"+String.format("%09d",gid);}
    public static String normalList(int gid,int multiplier){return "BetLog:0"+String.format("%08d",gid)+":"+String.format("%06d",multiplier);}
    public static String specialList(int gid,int multiplier){return "MaryLog:"+String.format("%09d",gid)+":"+String.format("%06d",multiplier);}
}
