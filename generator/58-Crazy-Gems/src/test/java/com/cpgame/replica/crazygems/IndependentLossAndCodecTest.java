package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsResultUtil;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IndependentLossAndCodecTest {
    @Test
    void independentLossFirstAttemptExceedsNinetyPercent() {
        Random random = new Random(58);
        CrazyGemsBoardGenerator boards = new CrazyGemsBoardGenerator(random);
        int success = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            if (CrazyGemsIndependentLossGenerator.isIndependentLoss(boards.generateIndependentLossCandidate())) {
                success++;
            }
        }
        assertTrue(success >= n * CrazyGemsIndependentLossGenerator.REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE,
                "first-attempt loss rate " + success + "/" + n);
    }

    @Test
    void codecRejectsJsonAndRestoresVisibleState() {
        CrazyGemsBoard board = new CrazyGemsBoard(
                new String[]{"WILD", "WILD", "WILD", "WILD", "H3", "H3", "WILD", "H7", "H7"}, 15);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String member = codec.encode(new CompleteRoundFact(1, board.rskl(), board.rpx()));
        assertEquals(10, member.length());
        assertTrue(member.charAt(0) != '{' && member.charAt(0) != '[');
        CompleteRoundFact decoded = codec.decode(member);
        assertEquals(15, decoded.rpx());
        assertEquals("WILD", decoded.rskl()[0]);
        assertEquals(CrazyGemsResultUtil.evaluate(board).multiplierDeci(), codec.verify(member).multiplierDeci());
    }

    @Test
    void factoryProducesBothLossAndMinecart() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        SecureRandom random = new SecureRandom();
        boolean loss = false;
        boolean special = false;
        boolean win = false;
        for (int i = 0; i < 400; i++) {
            var generated = factory.generate(random, i % 2 == 0);
            loss |= generated.evaluation().loss();
            win |= generated.evaluation().win();
            special |= generated.special();
        }
        assertTrue(loss, "natural loss missing");
        assertTrue(win, "natural win missing");
        assertTrue(special, "minecart special missing");
    }
}
