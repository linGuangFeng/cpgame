package com.cpgame.luckycatii.model;

import java.util.List;

public record RoundStep(String id, boolean paid, List<String> board) {}
