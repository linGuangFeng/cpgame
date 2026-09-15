package com.cpgame.replica.freedomday;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class NoBallLossCandidateTest {
 @Test void repairingWaysNeverReintroducesDisabledBall() {
  int[] weights=FreedomDayBoardGenerator.defaultNormalWeights();weights[FreedomDayResultUtil.BALL-1]=0;
  var generator=new FreedomDayBoardGenerator(new Random(180915),weights,FreedomDayBoardGenerator.defaultFreeWeights());
  for(int i=0;i<10000;i++) {
   var board=generator.generateIndependentLossCandidate(false);
   assertFalse(FreedomDayOrdinaryLossPolicy.containsBall(board),"sample="+i);
   assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(board));
  }
 }
}
