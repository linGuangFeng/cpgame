package com.cpgame.wukong.core;

import java.util.Objects;

/** 一个付费起点以及其全部自动动画状态；本游戏每局只有一个 HTTP delivery。 */
public record CompleteRound(Mode mode, ReelPair initial, ReelPair respin) {
    public CompleteRound {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(initial, "initial");
        if (mode == Mode.RESPIN) Objects.requireNonNull(respin, "RESPIN requires redeal");
        if (mode != Mode.RESPIN && respin != null) throw new IllegalArgumentException("non-RESPIN cannot carry redeal");
    }
    public enum Mode { NONE("null"), X2("2"), X5("5"), RESPIN("respin"); private final String wire; Mode(String wire){this.wire=wire;} public String wire(){return wire;} }
    public enum Outcome { ORDINARY_LOSS, ORDINARY_WIN, SPECIAL_X2, SPECIAL_X5, SPECIAL_RESPIN }
    public record ReelPair(String left, String right) {
        public ReelPair { left=normalize(left); right=normalize(right); }
        private static String normalize(String value){return value==null||value.isBlank()||"~".equals(value)?"null":value;}
    }
}
