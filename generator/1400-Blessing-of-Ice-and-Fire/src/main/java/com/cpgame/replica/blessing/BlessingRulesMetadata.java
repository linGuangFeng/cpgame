package com.cpgame.replica.blessing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class BlessingRulesMetadata {
    public static final String VERSION = "blessing-1400-realtime-multiplier-r1";
    private static final String DESCRIPTOR = """
            gameId=1400;name=Blessing of Ice and Fire;
            board=3x3-column-major;reels=fire+ice;win=middle-row-only;
            symbols=0-empty,1-high,2-mid,3-low;noWild;noScatter;noMali;noBuy;noFree;
            prop_odds=1:100,2:50,3:25,4:any-5;
            bet_type=1-fire,2-ice,3-both;bothCost=2x;bothWin=x2-on-sum;
            generation=realtime-map-odd-to-middle-patterns;cache=none;
            round=single-paid-spin
            """;
    public static final String HASH = sha256(DESCRIPTOR);

    private BlessingRulesMetadata() { }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
