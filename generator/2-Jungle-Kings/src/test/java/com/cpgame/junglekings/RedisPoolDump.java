package com.cpgame.junglekings;

public final class RedisPoolDump {
    private RedisPoolDump() { }

    public static void main(String[] args) throws Exception {
        try (RedisRoundStore store = new RedisRespRoundStore("192.168.10.3", 6379, "", 15, 3000, 3000)) {
            for (int betType : new int[]{0, 1}) {
                System.out.println("INDEX " + RedisKeys.index(betType));
                for (String ratio : store.ratios(betType)) {
                    int r = Integer.parseInt(ratio);
                    long n = store.listLength(betType, r);
                    System.out.println("  " + RedisKeys.list(betType, r) + " llen=" + n);
                }
            }
        }
    }
}
