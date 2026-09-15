package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundCodec;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeaderlessBetProjectionTest {
    @Test void requestBetControlsEachPayoutForCompactMembers() {
        var codec = new CompleteRoundCodec();
        for (var kind : RoundClass.values()) {
            var source = TestMembers.generate(kind, new Random(41915 + kind.ordinal()), 12000).fact();
            var compact = codec.decode(codec.encode(source));
            for (String bs : new String[]{"0.02", "0.1", "1"}) {
                for (int bl : new int[]{1, 3, 10}) {
                    var expected = SpinProjector.flatten(source, new BigDecimal(bs), bl);
                    var actual = SpinProjector.flatten(compact, new BigDecimal(bs), bl);
                    assertEquals(expected.size(), actual.size());
                    for (int i = 0; i < expected.size(); i++) {
                        var a = expected.get(i); var b = actual.get(i);
                        assertEquals(a.wa(), b.wa());
                        assertEquals(a.rwa(), b.rwa());
                        assertEquals(a.rpx(), b.rpx());
                        assertEquals(a.ss(), b.ss());
                        assertEquals(a.fsn(), b.fsn());
                        assertEquals(a.nfsc(), b.nfsc());
                        assertEquals(a.roundTerminal(), b.roundTerminal());
                        if (a.wa().signum() > 0) {
                            assertEquals(a.rskl(), b.rskl());
                            assertEquals(a.wmkl(), b.wmkl());
                            assertEquals(a.gfl(), b.gfl());
                            assertEquals(a.sfl(), b.sfl());
                        }
                    }
                }
            }
        }
    }
}
