package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundFacts;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.SpinStep;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureOracleTest {
    private final RoundFactory factory = new RoundFactory();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void holdoutOneHundredRoundsMatchIndependentOracle() throws Exception {
        int restored = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/saci-holdout-kernels.txt")),
                StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                RoundCandidate candidate = KernelCodec.decodeCandidate(line);
                RoundResult round = factory.restore(new RoundFacts("holdout-" + restored, 1,
                        new BigDecimal("0.02"), candidate), new BigDecimal("10000"));
                verifier.verify(round);
                for (SpinStep step : round.steps()) {
                    assertEquals(ResultUtil.expectedWa(step.rskl(), step.bl(), step.bs()), step.wa());
                    assertEquals(ResultUtil.expectedWmkl(step.rskl()), step.wmkl());
                }
                restored++;
            }
        }
        assertEquals(100, restored);
        assertTrue(restored >= 100);
    }

    @Test void trainingKernelsRestoreWithoutOracleFailure() throws Exception {
        int restored = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/saci-joint-kernels.txt")),
                StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                RoundCandidate candidate = KernelCodec.decodeCandidate(line);
                RoundResult round = factory.create(candidate, 10, new BigDecimal("0.02"), new BigDecimal("100000"));
                verifier.verify(round);
                restored++;
            }
        }
        assertTrue(restored >= 1000, "training=" + restored);
    }
}
