package com.cpgame.curupira.model;

import java.util.List;

public record EvaluatedBoard(List<Integer> ps, int scatterCount, List<Award> awards,
                             int multiplierSum, List<Integer> expandingWildColumns) {}
