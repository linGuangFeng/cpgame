package com.cpgame.luckynightmarket;

import java.util.List;
import java.util.Properties;
import java.security.SecureRandom;

/** Batch-scoped per-symbol weights used directly for rule-based cell generation. */
final class SymbolWeightSchedule {
    static final List<String> SYMBOL_NAMES = List.of("WILD", "H1", "H2", "L1", "L2", "L3", "L4");
    static final double[] EMPIRICAL_DEFAULTS = {1769, 676, 6774, 6179, 5937, 5932, 6078};

    private final double[] baseWeights = new double[SYMBOL_NAMES.size()];
    private final double[] boostMultipliers = new double[SYMBOL_NAMES.size()];
    private int boostedSymbol = -1;

    SymbolWeightSchedule(Properties properties) {
        this(properties, EMPIRICAL_DEFAULTS);
    }

    SymbolWeightSchedule(Properties properties, double[] sampleWeights) {
        if (sampleWeights.length != SYMBOL_NAMES.size()) throw new IllegalArgumentException("Expected seven sample symbol weights");
        for (int symbol = 0; symbol < baseWeights.length; symbol++) {
            String key = "symbol.weight." + SYMBOL_NAMES.get(symbol);
            double sampleWeight = positiveFinite(sampleWeights[symbol], "sample weight " + SYMBOL_NAMES.get(symbol));
            baseWeights[symbol] = positiveFinite(properties, key, sampleWeight);
            boostMultipliers[symbol] = positiveFinite(properties, key + "_boost_mul", 3.0d);
        }
    }

    void useBatch(long batchIndex) {
        int phase = Math.floorMod(batchIndex, SYMBOL_NAMES.size() + 1);
        boostedSymbol = phase == 0 ? -1 : phase - 1;
    }

    double weight(int symbol) {
        if (symbol < 0 || symbol >= baseWeights.length) throw new IllegalArgumentException("Symbol out of range: " + symbol);
        double result = baseWeights[symbol];
        if (symbol == boostedSymbol) result *= boostMultipliers[symbol];
        if (!Double.isFinite(result) || result <= 0.0d) throw new IllegalArgumentException("Symbol weight overflow: " + symbol);
        return result;
    }

    int pick(SecureRandom random, int[] allowedSymbols) {
        if (allowedSymbols.length == 0) throw new IllegalArgumentException("No allowed symbols");
        double total = 0.0d;
        for (int symbol : allowedSymbols) total += weight(symbol);
        double draw = random.nextDouble() * total;
        int last = allowedSymbols[allowedSymbols.length - 1];
        for (int symbol : allowedSymbols) {
            draw -= weight(symbol);
            if (draw < 0.0d) return symbol;
        }
        return last;
    }

    int boostedSymbol() { return boostedSymbol; }
    String boostedSymbolName() { return boostedSymbol < 0 ? "DEFAULT" : SYMBOL_NAMES.get(boostedSymbol); }

    private static double positiveFinite(Properties properties, String key, double fallback) {
        double value;
        try {
            value = Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).trim());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid numeric configuration: " + key, error);
        }
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new IllegalArgumentException(key + " must be a positive finite number");
        }
        return value;
    }

    private static double positiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0d) throw new IllegalArgumentException(name + " must be a positive finite number");
        return value;
    }
}
