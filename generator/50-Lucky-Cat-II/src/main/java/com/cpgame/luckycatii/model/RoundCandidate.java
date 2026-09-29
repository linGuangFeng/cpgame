package com.cpgame.luckycatii.model;

import java.util.List;

/** One complete paid/final board pair with wheel multiplier and Lucky flag. */
public record RoundCandidate(List<String> paidBoard, List<String> finalBoard, int rpx, boolean luckyRespin) {}
