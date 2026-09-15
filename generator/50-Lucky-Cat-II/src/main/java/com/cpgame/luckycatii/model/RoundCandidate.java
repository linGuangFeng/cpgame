package com.cpgame.luckycatii.model;

import java.util.List;

/** One complete joint state sampled from the training kernel. */
public record RoundCandidate(List<String> paidBoard, List<String> finalBoard, int rpx, boolean luckyRespin) {}
