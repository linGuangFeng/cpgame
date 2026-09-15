package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotIndependentLossGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompleteRoundCodecTest {
    @Test void legacyAsciiRoundTripsAndIsNotJson() {
        CompleteRoundFactory.GeneratedRound generated =
                new CompleteRoundFactory().generateIndependentLoss(new Random(1830L));
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String member = codec.encodeFull(generated.fact());
        assertFalse(member.isEmpty());
        assertNotEquals('{', member.charAt(0));
        assertNotEquals('[', member.charAt(0));
        assertEquals(36, member.length());
        CompleteRoundFact decoded = codec.decode(member);
        assertEquals(generated.fact(), decoded);
        RoundVerification verification = codec.verify(member, 10, 30);
        assertEquals(0, verification.multiplier());
        assertFalse(verification.scatterFreeSpins());
    }

    @Test void naturalRoundVerifiesWithSameResultUtil() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(18300904L);
        CompleteRoundFactory.GeneratedRound generated = null;
        for (int i = 0; i < 200; i++) {
            try {
                generated = factory.generate(random, 10, 30);
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
                continue;
            }
            if (generated.kind() != HotpotRoundKind.ORDINARY_LOSS) break;
        }
        assertNotNull(generated);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String member = codec.encodeFull(generated.fact());
        RoundVerification verification = codec.verify(member, 10, 30);
        assertEquals(generated.multiplier(), verification.multiplier());
        assertEquals(generated.kind() == HotpotRoundKind.SCATTER_FREE_SPINS, verification.scatterFreeSpins());
        assertEquals(generated.fact(), codec.decode(member));
    }

    @Test void candidatePageOverflowIsRetryableButInvalidConfigurationIsNot() {
        List<Integer> prop = new ArrayList<>();
        for (int i = 0; i < 36; i++) prop.add((i % 10) + 1);
        for (int col = 0; col < 5; col++) prop.set(col * 6, 11);
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION,
                List.of(List.of(new CompleteRoundFact.BoardFact(prop))));
        assertThrows(CompleteRoundCodec.CandidateLimitException.class,
                () -> new CompleteRoundCodec().verify(fact, 10, 30));
        assertThrows(CompleteRoundFactory.RoundRejectedException.class,
                () -> CompleteRoundFactory.verifyGenerated(fact, 10, 30));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRoundFactory.verifyGenerated(fact, 0, 30));
    }

    @Test void repeatedCandidatesDiscardLimitsAndContinueToVerifiedRounds() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(183015L);
        int accepted = 0, rejected = 0, special = 0;
        for (int i = 0; i < 10000; i++) {
            try {
                var round = factory.generate(random, 2, 12);
                CompleteRoundFactory.verifyGenerated(round.fact(), 2, 12);
                accepted++;
                if (round.kind() == HotpotRoundKind.SCATTER_FREE_SPINS) special++;
            } catch (CompleteRoundFactory.RoundRejectedException limit) { rejected++; }
        }
        assertTrue(accepted > 100);
        assertTrue(rejected > 0);
        assertTrue(special > 0);
    }

    @Test void jsonMembersAreRejected() {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"prop\":[1]}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("[[1,2]]"));
    }

    @Test void independentLossBoardsStayIndependentAfterCodec() {
        assertTrue(HotpotIndependentLossGenerator.isIndependentLoss(
                new CompleteRoundFactory().generateIndependentLoss(new Random(7L)).fact().spins().get(0).get(0).toBoard()));
    }

    @Test void scatterPerColumnCapIsPartOfRedisMemberVerification() {
        List<Integer> prop = new ArrayList<>();
        for (int i = 0; i < 36; i++) prop.add((i % 10) + 1);
        prop.set(6, 11);
        prop.set(9, 11);
        prop.set(18, 11);
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION,
                List.of(List.of(new CompleteRoundFact.BoardFact(prop))));
        CompleteRoundCodec codec = new CompleteRoundCodec();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.verify(codec.encode(fact), 10, 30));
        assertTrue(error.getMessage().contains("per-column cap"), error.getMessage());
    }
}
