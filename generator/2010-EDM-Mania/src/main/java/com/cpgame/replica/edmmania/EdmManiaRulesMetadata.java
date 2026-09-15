package com.cpgame.replica.edmmania;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared identity embedded in both HTTP round output and the per-game Redis Loader. */
public final class EdmManiaRulesMetadata {
    public static final String VERSION = "edm-mania-2010-v1";
    private static final String DESCRIPTOR = """
            board=6x5+4;symbols=1..13;ball=1;scatter=12;wild=13;
            normalWeights=109,1334,1379,1294,1216,1184,1308,1248,1276,1265,1264,275,74;
            freeWeights=109,1334,1379,1294,1216,1184,1308,1248,1276,1265,1264,275,74;
            pay=1:30,40,50,80|2:20,25,30,50|3:10,25,30,40|4:8,15,20,30|
            5:6,10,12,15|6:6,10,12,15|7:4,6,8,10|8:4,6,8,10|
            9:1,2,3,4|10:1,2,3,4|11:1,2,3,4;
            scatterFreeSpins=visible-prop+trl-stacked=1-4:10,+2;ball=visible-main-new+2-even-no-win;freeStartMultiplier=2;maxFreeSpins=30;
            grids=contiguous-same-reel-height-2..4-incl-ball-scatter;openingGold=0;openingSilver~58;
            wildOuterReels=0;scatterBoardMax=5;wildBoardMax=4;maxConsecutiveWins=12;
            ordinaryLossBallPolicy=50pct-baseline-plus-50pct-no-ball
            """;
    public static final String HASH = sha256(DESCRIPTOR);

    private EdmManiaRulesMetadata() { }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
