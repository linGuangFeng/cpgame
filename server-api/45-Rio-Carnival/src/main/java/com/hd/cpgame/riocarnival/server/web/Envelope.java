package com.hd.cpgame.riocarnival.server.web;

public final class Envelope {
    public final Object code;
    public final Object data;
    public final String info;
    private Envelope(Object code,Object data,String info){this.code=code;this.data=data;this.info=info;}
    public static Envelope ok(Object data){return new Envelope(200,data,"ok");}
    public static Envelope error(Object code,String info){return new Envelope(code,null,info);}
}
