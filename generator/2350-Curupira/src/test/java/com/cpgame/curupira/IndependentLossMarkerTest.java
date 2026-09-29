package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GenerationPolicy;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    @Test void zeroMultiplierStoresFullGeneratedFactsAndRejectsOldMarker() {
        MinimalFactCodec codec = new MinimalFactCodec();
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(235000L),
                GenerationPolicy.ordinaryPaidDefaults());
        Set<String> lossMembers = new HashSet<>();
        for (int i = 0; i < 2_000 && lossMembers.size() < 100; i++) {
            CompleteRoundFact fact = core.generatePaidCandidate();
            if (fact.kind() != Kind.LOSS) continue;
            String member = codec.encodeFact(fact);
            assertFalse(member.contains("#"));
            assertTrue(member.startsWith("CU1PL;S"));
            assertEquals(fact.steps(), codec.decode(member).steps());
            assertEquals(0, fact.redisMultiplier());
            lossMembers.add(member);
        }
        assertTrue(lossMembers.size() >= 100, "自然 0 倍完整局不足：" + lossMembers.size());
        assertThrows(IllegalArgumentException.class, () -> codec.decode("CU1PL;#"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("CU1PT;#"));
    }
}
