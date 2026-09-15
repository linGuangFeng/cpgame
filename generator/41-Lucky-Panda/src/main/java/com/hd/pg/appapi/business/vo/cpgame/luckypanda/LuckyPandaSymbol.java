package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

/** Wire symbol names from rskl tokens. Paying set, Wild and Scat are mutually exclusive roles. */
public enum LuckyPandaSymbol {
    PAN("Pan", true),
    H1("H1", true),
    H2("H2", true),
    H3("H3", true),
    H4("H4", true),
    H5("H5", true),
    A("A", true),
    K("K", true),
    Q("Q", true),
    J("J", true),
    T("T", true),
    WILD("Wild", false),
    SCAT("Scat", false);

    private final String wireName;
    private final boolean paying;

    LuckyPandaSymbol(String wireName, boolean paying) {
        this.wireName = wireName;
        this.paying = paying;
    }

    public String wireName() { return wireName; }
    public boolean paying() { return paying; }

    public static LuckyPandaSymbol fromWire(String token) {
        if (token == null) throw new IllegalArgumentException("symbol token is required");
        return switch (token) {
            case "Pan" -> PAN;
            case "H1" -> H1;
            case "H2" -> H2;
            case "H3" -> H3;
            case "H4" -> H4;
            case "H5" -> H5;
            case "A" -> A;
            case "K" -> K;
            case "Q" -> Q;
            case "J" -> J;
            case "T" -> T;
            case "Wild" -> WILD;
            case "Scat" -> SCAT;
            default -> throw new IllegalArgumentException("unknown Lucky Panda symbol: " + token);
        };
    }
}
