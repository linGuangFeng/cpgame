package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameRules {
    public static final int GAME_ID = 45;
    public static final String GAME_NAME = "Rio Carnival";
    public static final String RULES_HASH = "sha256:515ffa3a4f803e36d44a1a50da1ce79b4c1df2e75a79893a18cd404aecc0565c";
    public static final int REELS = 5;
    public static final int ROWS = 3;
    public static final int PAYLINE_COUNT = 25;
    public static final String WILD = "Wild";
    public static final String SCATTER = "Scat";

    public static final List<String> SYMBOLS = Collections.unmodifiableList(Arrays.asList(
        "9", "A", "H1", "H2", "H3", "H4", "H5", "J", "K", "Q", "Scat", "T", "Wild"));
    public static final List<BigDecimal> BET_SIZES = Collections.unmodifiableList(Arrays.asList(
        new BigDecimal("0.02"), new BigDecimal("0.12"), new BigDecimal("0.4"), new BigDecimal("0.8")));
    public static final List<Integer> BET_LEVELS = Collections.unmodifiableList(Arrays.asList(1,2,3,4,5,6,7,8,9,10));
    public static final List<Integer> AUTO_SPINS = Collections.unmodifiableList(Arrays.asList(10,30,50,100,500));

    /**
     * 原厂结果权重没有出现在已验收协议证据中。这里是 Rio Carnival 本地复刻的显式正数权重，
     * 只用于本地正式生成链路，不代表原厂 RTP；正式 Loader 可通过 generator.properties 调整。
     */
    public static final Map<String, Integer> DEFAULT_NORMAL_WEIGHTS = weights(
        12,10,5,6,7,8,8,12,10,12,4,11,2);
    public static final Map<String, Integer> DEFAULT_FREE_WEIGHTS = weights(
        12,10,5,6,7,8,8,12,10,12,3,11,2);

    public static final int[][] PAYLINES = {
        {1,1,1,1,1},{0,0,0,0,0},{2,2,2,2,2},{0,1,2,1,0},{2,1,0,1,2},
        {1,0,0,0,1},{1,2,2,2,1},{0,0,1,2,2},{2,2,1,0,0},{1,2,1,0,1},
        {1,0,1,2,1},{0,1,1,1,0},{2,1,1,1,2},{0,1,0,1,0},{2,1,2,1,2},
        {1,1,0,1,1},{1,1,2,1,1},{0,0,2,0,0},{2,2,0,2,2},{0,2,2,2,0},
        {2,0,0,0,2},{1,2,0,2,1},{1,0,2,0,1},{0,2,0,2,0},{2,0,2,0,2}
    };

    public static final Map<String, Map<Integer, Integer>> PAYTABLE = buildPaytable();

    private GameRules() {}

    private static Map<String, Map<Integer, Integer>> buildPaytable() {
        Map<String, Map<Integer, Integer>> p = new LinkedHashMap<String, Map<Integer, Integer>>();
        put(p,"9",3,5,4,25,5,100); put(p,"A",3,10,4,50,5,150);
        put(p,"H1",2,10,3,50,4,250,5,750); put(p,"H2",2,5,3,40,4,200,5,500);
        put(p,"H3",3,30,4,150,5,400); put(p,"H4",3,25,4,100,5,250);
        put(p,"H5",3,25,4,100,5,250); put(p,"J",3,5,4,25,5,100);
        put(p,"K",3,10,4,50,5,150); put(p,"Q",3,5,4,25,5,100);
        put(p,"Scat",2,0,3,0,4,0,5,0); put(p,"T",3,5,4,25,5,100);
        put(p,"Wild",2,25,3,150,4,1000,5,2500);
        return Collections.unmodifiableMap(p);
    }

    private static void put(Map<String, Map<Integer, Integer>> table, String symbol, int... pairs) {
        Map<Integer, Integer> values = new LinkedHashMap<Integer, Integer>();
        for (int i=0; i<pairs.length; i+=2) values.put(pairs[i], pairs[i+1]);
        table.put(symbol, Collections.unmodifiableMap(values));
    }

    private static Map<String, Integer> weights(int... values) {
        if (values.length != SYMBOLS.size()) throw new IllegalArgumentException("权重数量与符号数量不一致");
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < values.length; i++) result.put(SYMBOLS.get(i), values[i]);
        return Collections.unmodifiableMap(result);
    }

    public static int scatterAward(int scatters) {
        if (scatters == 3) return 8;
        if (scatters == 4) return 12;
        if (scatters >= 5) return 20;
        return 0;
    }

    public static int[] initialChoices(int scatters) {
        if (scatters == 3) return new int[]{8,12,20};
        if (scatters == 4) return new int[]{12,16,24};
        return new int[]{20,24,32};
    }
}
