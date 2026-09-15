package com.cpgame.g2110.core;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultUtilEvidenceTest {
    @Test void independentlyRecalculateEveryIndexedOriginalResponse() throws Exception {
        assertEquals(1476,ResultUtilEvidenceCheck.verify(Path.of("../../fixtures/2110-Bee-Workshop/spin")));
    }
}
