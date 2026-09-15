package com.cpgame.coinmastergo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"ba", "frwa", "fsn", "gfl", "gt", "nfsc", "pb", "rpx", "rskl", "rwa",
        "small_game_type", "ss", "wa", "wmkl", "wskl"})
public class SpinStep {
    public BigDecimal ba = BigDecimal.ZERO;
    public BigDecimal frwa = BigDecimal.ZERO;
    public int fsn;
    public List<Integer> gfl = new ArrayList<>();
    /** Explicit internal complement of gfl; never invent this as a provider wire field. */
    @JsonIgnore
    public List<Integer> silverCardCoordinates = new ArrayList<>();
    public int gt;
    public int nfsc;
    public String pb;
    public int rpx;
    public List<String> rskl = new ArrayList<>();
    public BigDecimal rwa = BigDecimal.ZERO;
    public int small_game_type;
    public int ss;
    public BigDecimal wa = BigDecimal.ZERO;
    public List<List<List<Integer>>> wmkl = new ArrayList<>();
    public List<String> wskl = new ArrayList<>();

    @JsonIgnore
    public List<WinMatch> matchDetails = new ArrayList<>();

    public SpinStep() { }
}
