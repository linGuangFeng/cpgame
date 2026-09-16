package com.cpgame.replica.freedomday;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared identity embedded in both HTTP round output and the per-game Redis Loader. */
public final class FreedomDayRulesMetadata {
    public static final String VERSION = "freedom-day-1809-v3-multiplier-carry";
    private static final String DESCRIPTOR = """
            board=6x5+4;symbols=1..13;ball=1;scatter=12;wild=13;
            normalWeights=466,4431,4436,4124,4263,4038,4120,4187,4138,4145,4082,754,200;
            freeWeights=746,7736,7706,7598,7524,7702,7796,7545,7449,7688,7675,1573,482;
            pay=1:30,40,50,80|2:20,25,30,50|3:10,25,30,40|4:8,15,20,30|
            5:6,10,12,15|6:6,10,12,15|7:4,6,8,10|8:4,6,8,10|
            9:1,2,3,4|10:1,2,3,4|11:1,2,3,4;
            scatterFreeSpins=visible-stacked=1-4:10,+2;oneTriggerPerReel;oneTriggerOnTopStrip;triggerBoardNoWin;
            normalStartMultiplier=1;freeStartMultiplier=2;ballIncrement=2;visibleBallCollectsOnZeroWin=true;
            compactIndependentLoss=#,#1,#2,#3;maxFreeSpins=30;
            grids=contiguous-same-reel-height-2..4-including-scatter-and-ball;gf=gold-grid;sl=silver-grid;
            ordinaryLossBallPolicy=50pct-baseline-plus-50pct-no-ball
            """;
    public static final String HASH = sha256(DESCRIPTOR);

    private FreedomDayRulesMetadata() { }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
