package com.cpgame.replica.hotpot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared identity embedded in the Redis Loader. Must not copy 1809/1407 board descriptors. */
public final class HotpotRulesMetadata {
    public static final String VERSION = "hotpot-1830-result-engine-r1-20260904";
    public static final String PROTOCOL_HASH = "01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01";
    private static final String DESCRIPTOR = """
            gameId=1830;board=6x6;win=count-anywhere-8;scatter=11;
            multipliers=12..23:[2,3,4,5,7,10,15,20,25,30,40,50];
            paidTrigger=3scatter->10+2;freeRetrigger=2scatter->5+2;
            buy=absent;wildSubstitute=false;grids=none;
            paidStartWeights=5351,5179,4917,4903,4788,4833,4790,4885,4692,4752,557,89,111,105,124,0,0,0,0,0,0,0,0;
            cascadeWeights=5432,5499,5735,5505,5432,5386,5418,5247,5510,5295,553,226,394,360,701,50,51,40,45,60,32,12,5;
            freeStartWeights=3846,3695,3779,3777,3645,3654,3618,3710,3732,3704,439,0,0,0,108,78,47,32,57,47,32,10,6;
            protocolHash=01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01
            """;
    public static final String HASH = sha256(DESCRIPTOR);

    private HotpotRulesMetadata() { }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
