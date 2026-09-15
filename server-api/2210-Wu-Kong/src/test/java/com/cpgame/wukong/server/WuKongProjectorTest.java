package com.cpgame.wukong.server;

import static org.junit.jupiter.api.Assertions.*;
import com.cpgame.wukong.core.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WuKongProjectorTest {
    @Test void projectsProviderFieldTypesAndAccounting(){var round=new CompleteRound(CompleteRound.Mode.RESPIN,new CompleteRound.ReelPair("null","1"),new CompleteRound.ReelPair("10","5"));var d=new WuKongProjector().project(round,BigDecimal.ONE,new BigDecimal("100.00"),42);assertEquals("respin",d.at("/chess/extend").asText());assertTrue(d.at("/chess/respin").isArray());assertEquals(2,d.at("/chess/normal").size());assertEquals(2,d.at("/chess/respin").size());assertEquals(2,d.at("/chess/result").size());assertEquals(106,d.path("total_win").asInt());assertEquals(205,d.path("end_gold").asInt());assertEquals(0,d.path("start_gold").decimalValue().add(d.path("change_gold").decimalValue()).compareTo(d.path("end_gold").decimalValue()));}
    @Test void minBetKeepsTwoNormalPositions(){var round=new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("10","5"),null);var d=new WuKongProjector().project(round,BigDecimal.ONE,new BigDecimal("100.00"),1);assertEquals(2,d.at("/chess/normal").size());assertEquals("10",d.at("/chess/normal/0").asText());assertEquals("5",d.at("/chess/normal/1").asText());assertEquals("null",d.at("/chess/respin").asText());assertEquals(105,d.path("total_win").asInt());}
    @Test void raisedBetPadsThirdColumnSoUnlockedReelCanStop(){var round=new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("10","5"),null);var d=new WuKongProjector().project(round,new BigDecimal("100"),new BigDecimal("1000.00"),1);assertEquals(3,d.at("/chess/normal").size());assertEquals("10",d.at("/chess/normal/0").asText());assertEquals("5",d.at("/chess/normal/1").asText());assertEquals("null",d.at("/chess/normal/2").asText());assertEquals(10500,d.path("total_win").asInt());}
    @Test void raisedBetPadsThirdRespinPositionWithoutChangingAward(){var round=new CompleteRound(CompleteRound.Mode.RESPIN,new CompleteRound.ReelPair("null","1"),new CompleteRound.ReelPair("10","5"));var min=new WuKongProjector().project(round,BigDecimal.ONE,new BigDecimal("100.00"),1);var raised=new WuKongProjector().project(round,new BigDecimal("5"),new BigDecimal("100.00"),1);assertEquals(2,min.at("/chess/respin").size());assertEquals(3,raised.at("/chess/normal").size());assertEquals(3,raised.at("/chess/respin").size());assertEquals("null",raised.at("/chess/respin/2").asText());assertEquals(min.path("total_win").asInt()*5,raised.path("total_win").asInt());}
}
